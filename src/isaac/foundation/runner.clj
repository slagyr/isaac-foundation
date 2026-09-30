(ns isaac.foundation.runner
  "Process runner: activate configured modules, start their components, and
   stop components/modules in reverse lifecycle order. Transport modules remain
   ordinary component contributors."
  (:require
    [isaac.foundation.cli.host :as host]
    [isaac.foundation.component.runtime :as components]
    [isaac.foundation.component.supervisor :as supervisor]
    [isaac.foundation.config.api :as config-api]
    [isaac.foundation.config.watch :as config-watch]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.logger :as log]
    [isaac.foundation.module.berths :as berths]
    [isaac.foundation.module.discovery :as discovery]
    [isaac.foundation.module.lifecycle :as modules]
    [isaac.foundation.nexus :as nexus]
    [isaac.foundation.runner.lifecycle :as lifecycle]
    [isaac.foundation.scheduler.runtime :as scheduler]))

(defonce state (atom nil))

(def ^:dynamic *before-components* (constantly nil))
(def ^:dynamic *after-components* (constantly nil))
(def ^:dynamic *before-stop* (constantly nil))
(def ^:dynamic *after-stop* (constantly nil))

(declare stop!)

(defn running? []
  (some? @state))

(defn- resolve-config [{:keys [config root fs]}]
  (or config
      (when root
        (:config (config-api/load-resolved {:root root :fs fs})))
      {}))

(defn- resolve-module-index [config opts]
  (or (:module-index opts)
      (let [{:keys [index]} (discovery/discover! config {:root (:root opts)
                                                         :cwd  (host/cwd)})]
        index)))

(defn start!
  "Boot configured modules and components. Returns the runner state."
  [opts]
  (when (running?)
    (stop!))
  (let [fs*          (or (:fs opts) (nexus/get :fs) (fs/real-fs))
        config       (resolve-config (assoc opts :fs fs*))
        module-index (resolve-module-index config opts)
        scheduler*   (when (:root opts)
                       (-> (scheduler/create {}) scheduler/start!))]
    (nexus/init! (cond-> {:fs fs* :root (:root opts)}
                   scheduler* (assoc :scheduler scheduler*)))
    (lifecycle/reset-hello!)
    (lifecycle/emit-hello! (:root opts) (boolean (:dev opts)))
    (log/info :server/started)
    (config-api/dangerously-install-config! config "runner boot")
    (modules/reconcile-modules! module-index)
    (modules/activate-modules! module-index)
    (berths/process-manifest-berths! module-index)
    (*before-components* {:config config :module-index module-index :opts opts})
    (components/start-all! module-index {:config config :root (:root opts) :opts opts})
    (*after-components* {:config config :module-index module-index :opts opts})
    (supervisor/reset-health!)
    (supervisor/start! components/started-components)
    ;; The process owns noticing that config changed — not whichever transport
    ;; module happened to load (isaac-1pi2).
    (let [watch (config-watch/start! {:config    config
                                      :root      (:root opts)
                                      :fs        fs*
                                      :source    (:config-change-source opts)
                                      :reloader? (:start-config-reloader? opts)
                                      :host      {:module-index module-index
                                                  :root         (:root opts)}})
          started {:config       config
                   :module-index module-index
                   :scheduler    scheduler*
                   :config-watch watch
                   :components   (count (components/started-components))}]
      (reset! state started)
      (log/info :runner/started :components (:components started))
      started)))

(defn stop! []
  (when-let [running @state]
    (*before-stop* running)
    (config-watch/stop! (:config-watch running))
    (supervisor/stop!)
    (components/stop-all!)
    (modules/shutdown-modules!)
    (some-> (:scheduler running) scheduler/shutdown!)
    (*after-stop* running)
    (reset! state nil)
    (log/info :runner/stopped))
  :stopped)
