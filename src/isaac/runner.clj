(ns isaac.runner
  "Process runner: activate configured modules, start their components, and
   stop components/modules in reverse lifecycle order. Transport modules remain
   ordinary component contributors."
  (:require
    [isaac.component.runtime :as components]
    [isaac.component.supervisor :as supervisor]
    [isaac.config.api :as config-api]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.module.berths :as berths]
    [isaac.module.discovery :as discovery]
    [isaac.module.lifecycle :as modules]
    [isaac.nexus :as nexus]
    [isaac.runner.lifecycle :as lifecycle]
    [isaac.scheduler.runtime :as scheduler]))

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
                                                         :cwd  (System/getProperty "user.dir")})]
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
    (config-api/dangerously-install-config! config "runner boot")
    (modules/reconcile-modules! module-index)
    (modules/activate-modules! module-index)
    (berths/process-manifest-berths! module-index)
    (*before-components* {:config config :module-index module-index :opts opts})
    (components/start-all! module-index {:config config :root (:root opts) :opts opts})
    (*after-components* {:config config :module-index module-index :opts opts})
    (supervisor/reset-health!)
    (supervisor/start! components/started-components)
    (let [started {:config       config
                   :module-index module-index
                   :scheduler    scheduler*
                   :components   (count (components/started-components))}]
      (reset! state started)
      (log/info :runner/started :components (:components started))
      started)))

(defn stop! []
  (when-let [running @state]
    (*before-stop* running)
    (supervisor/stop!)
    (components/stop-all!)
    (modules/shutdown-modules!)
    (some-> (:scheduler running) scheduler/shutdown!)
    (*after-stop* running)
    (reset! state nil)
    (log/info :runner/stopped))
  :stopped)
