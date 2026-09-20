(ns isaac.config.watch
  "Config hot-reload, owned by the process.

   Foundation owns config and owns the daemon, so it owns noticing that config
   changed. This used to live in isaac-http, which meant a host running any
   other transport — or no transport — silently never reloaded anything, and a
   host that simply never set :hot-reload got the same silence while the
   resolver claimed the default was on (isaac-1pi2).

   Every file the config layer recognises counts: isaac.edn, every
   <kind>/<id>.edn under config/, and the markdown souls and ledgers beside
   them. The watcher is recursive over the config root, so a file created after
   boot is seen like any other."
  (:require
    [isaac.config.loader :as loader]
    [isaac.config.runtime :as runtime]
    [isaac.logger :as log]))

(def ^:private retired-path [:server :hot-reload])

(defn hot-reload?
  "Is the config watcher wanted? Default TRUE — a host says nothing and gets
   hot reload. `:hot-reload false` turns it off. The retired
   [:server :hot-reload] still works and says so once."
  [config]
  (let [current (:hot-reload config)
        retired (get-in config retired-path)]
    (cond
      (boolean? current) current

      (boolean? retired)
      (do (log/warn :config.watch/retired-key
                    :key retired-path
                    :message "use the top-level :hot-reload; [:server :hot-reload] is retired")
          retired)

      :else true)))

(def ^:private optional-registry-syms
  "Config-driven registries contributed by modules. Resolved by name because a
   module may not be installed; absent ones are skipped. TODO: invert this into
   a berth so modules declare their registry instead of foundation naming them."
  '[isaac.hail.bands/registry
    isaac.hooks/registry
    isaac.cron.service/registry])

(defn- resolve-registry [sym]
  (when-let [v (try (requiring-resolve sym) (catch Throwable _ nil))]
    (if (var? v) @v v)))

(defn registries []
  (vec (keep resolve-registry optional-registry-syms)))

(defn- reload-loop! [{:keys [source root fs host registries]}]
  (future
    (loop []
      (when-let [path (runtime/poll! source 5000)]
        (runtime/reload! {:root       root
                          :fs         fs
                          :old-config (loader/snapshot "reload: previous config for the reconcile diff")
                          :registries registries
                          :host       host
                          :path       path}))
      (recur))))

(defn start!
  "Start watching the config root, if this host wants it. Returns a handle to
   pass to stop!, or nil when watching is off or impossible. Always says which
   way it went: a watcher that silently does not exist is how isaac-1pi2 hid."
  [{:keys [config root fs host]}]
  (cond
    (not root)
    (do (log/info :config.watch/disabled :reason :no-root) nil)

    (not (hot-reload? config))
    (do (log/info :config.watch/disabled :reason :configured-off) nil)

    :else
    (let [source (runtime/watch-service-source root)]
      (runtime/start! source)
      {:source source
       :reloader (reload-loop! {:source source :root root :fs fs
                                :host host :registries (registries)})})))

(defn stop!
  "Stop the watcher and its reload loop."
  [handle]
  (when-let [{:keys [source reloader]} handle]
    (some-> reloader future-cancel)
    (some-> source runtime/stop!)
    (log/info :config.watch/stopped))
  nil)
