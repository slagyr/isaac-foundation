(ns isaac.module.lifecycle
  "Module activation and lifecycle — handlers, activate!, load/unload, topo order."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [isaac.logger :as log]
    [isaac.module.classpath :as classpath]
    [isaac.module.coords :as coords]
    [isaac.module.discovery :as discovery]
    [isaac.module.protocol :as module]))

(declare activate!)

(defonce ^:private activated-modules* (atom #{}))
(defonce ^:private started-modules* (atom []))

;; ----- Registry handler injection -----
;; module.lifecycle needs to call into registries (isaac.api, tool.registry,
;; slash.registry, server.routes) and a config-snapshot reader (config.loader)
;; during activation, but those nses transitively require module.lifecycle. To
;; break the cycle, each one self-registers a handler at load time and
;; module.lifecycle dispatches through this table instead of compile-time
;; requires.
(defonce ^:private handlers* (atom {}))

(def ^:private multi-handler-kinds #{:clear-registrations})

(defn register-handler!
  "Registers a handler fn that module.lifecycle will invoke during activation.
   Called by registry namespaces at their load time.

   Known kinds:
     :clear-registrations (fn [] => any)                  — clears module-contributed registrations
     :user-config         (fn [root-key entry-id] => map) — reads user config for an extension

   Every other extension kind has migrated to a :isaac.server/* berth
   processed by `process-manifest-berths!` (phases 4–8 of brth):
   :isaac/cli (phase 4), :route (phase 5), :tools (phase 6),
   :slash-commands / :llm/api / :hook / :provider (phase 7), :comm
   (phase 8)."
  [kind handler-fn]
  (swap! handlers*
         (fn [handlers]
           (if (contains? multi-handler-kinds kind)
             (update handlers kind (fnil conj []) handler-fn)
             (assoc handlers kind handler-fn)))))

(defn handler-for [kind]
  (or (get @handlers* kind)
      (throw (ex-info (str "no module-loader handler registered for kind " kind
                           " (registry namespace must self-register at load time)")
                      {:kind kind :registered-kinds (vec (sort (keys @handlers*)))}))))

(defn handlers-for [kind]
  (get @handlers* kind []))

(def server-module-id :isaac.server)

(defn activate-foundation! []
  (activate! coords/foundation-module-id (discovery/foundation-index)))

(defn deactivate-foundation! []
  (swap! activated-modules* disj coords/foundation-module-id))

(defn activate-server! []
  (activate! server-module-id (discovery/builtin-index)))

(defn resolve-symbol! [sym]
  (requiring-resolve sym))

(defn user-config
  "Reads the user-supplied config slot at `[root-key entry-id]` from
   the live config snapshot. Returns {} when nothing is configured.
   Public so berth factories (e.g. tool.registry/register-tool-entry!
   for the :isaac.server/tools berth) can read their per-entry
   user config without re-implementing the lookup."
  [root-key entry-id]
  (or ((handler-for :user-config) root-key entry-id) {}))

(defn- register-extensions! [_manifest]
  ;; Phases 4–8 of the berth epic moved every extension kind into
  ;; :isaac.server/* berths processed by process-manifest-berths!.
  ;; activate! still runs this for backwards compat with old call
  ;; sites; it's now a no-op.
  nil)

(defn- call-bootstrap! [bootstrap]
  (when bootstrap
    ((resolve-symbol! bootstrap))))

(defn activate! [module-id module-index]
  (let [id          (or (coords/->module-id module-id) module-id)
        module-meta (get module-index id)
        manifest    (:manifest module-meta)
        bootstrap   (:bootstrap manifest)
        coord       (:coord module-meta)]
    (cond
      (contains? @activated-modules* id)
      :already-active

      (nil? manifest)
      (let [error (ex-info (str "module activation failed: " (coords/id-str id))
                           {:type      :module/activation-failed
                            :module-id id
                            :bootstrap bootstrap
                            :reason    :missing-manifest})]
        (log/error :module/activation-failed :module (coords/id-str id) :reason :missing-manifest)
        (throw error))

      :else
      (try
        (when (:path module-meta)
          (classpath/ensure-module-deps! id coord))
        (register-extensions! manifest)
        (call-bootstrap! bootstrap)
        (swap! activated-modules* conj id)
        (log/info :module/activated :bootstrap (some-> bootstrap str) :module (coords/id-str id))
        :activated
        (catch Exception e
          (let [error (ex-info (str "module activation failed: " (coords/id-str id))
                               {:type      :module/activation-failed
                                :module-id id
                                :bootstrap bootstrap}
                               e)]
            (log/error :module/activation-failed
                       :bootstrap (some-> bootstrap str)
                       :error  (.getMessage e)
                       :module (coords/id-str id))
            (throw error)))))))

(defn lifecycle-error
  [message data cause]
  (ex-info message (assoc data :type :module/lifecycle-failed) cause))

(defn lifecycle-deps [module-index module-id]
  (->> (keys (get-in module-index [module-id :manifest :deps] {}))
       (filter #(contains? module-index %))
       (sort-by coords/id-str)))

(defn cycle-path [stack module-id]
  (conj (vec (drop-while #(not= % module-id) stack)) module-id))

(defn topological-order
  "Module ids in dependency order — deps before dependents, alphabetical
   tie-break. The order activation processes berths in; the config gather
   orders contributions by it too so both agree on last-wins ownership."
  [module-index]
  (let [visiting (atom #{})
        visited  (atom #{})
        order    (atom [])]
    (letfn [(visit [module-id stack]
              (cond
                (contains? @visited module-id)
                nil

                (contains? @visiting module-id)
                (let [cycle   (cycle-path stack module-id)
                      message (str "module dependency cycle detected: "
                                   (str/join " -> " (map coords/id-str cycle)))]
                  (throw (lifecycle-error message
                                          {:reason    :dependency-cycle
                                           :module-id module-id
                                           :cycle     cycle}
                                          nil)))

                :else
                (do
                  (swap! visiting conj module-id)
                  (doseq [dep (lifecycle-deps module-index module-id)]
                    (visit dep (conj stack module-id)))
                  (swap! visiting disj module-id)
                  (swap! visited conj module-id)
                  (swap! order conj module-id))))]
      (doseq [module-id (sort-by coords/id-str (keys module-index))]
        (visit module-id []))
      @order)))

(defn activate-modules!
  "Activate every module in `module-index` in topological order. Idempotent —
   already-active modules are skipped without re-logging."
  [module-index]
  (doseq [module-id (topological-order module-index)]
    (activate! module-id module-index))
  :activated)

(defn- resolve-module-factory! [module-id factory-sym]
  (try
    (resolve-symbol! factory-sym)
    (catch Exception e
      (throw (lifecycle-error (str "module factory resolution failed for " (coords/id-str module-id)
                                   ": " factory-sym)
                              {:reason    :resolve-factory
                               :module-id module-id
                               :factory   factory-sym}
                              e)))))

(defn- instantiate-module! [module-id {:keys [manifest coord path]}]
  (let [factory-sym (:factory manifest)
        _           (when path
                      (classpath/ensure-module-deps! module-id coord))
        factory     (resolve-module-factory! module-id factory-sym)
        instance    (try
                      (factory)
                      (catch Exception e
                        (throw (lifecycle-error (str "module factory threw for " (coords/id-str module-id))
                                                {:reason    :factory-threw
                                                 :module-id module-id
                                                 :factory   factory-sym}
                                                e))))]
    (when-not (module/module? instance)
      (throw (lifecycle-error (str "module factory returned non-Module for " (coords/id-str module-id))
                              {:reason    :not-a-module
                               :module-id module-id
                               :factory   factory-sym
                               :value-type (some-> instance class str)}
                              nil)))
    instance))

(defn- eager-load? [module-id {:keys [path coord manifest]}]
  (and (:factory manifest)
       (or (contains? coords/platform-module-ids module-id)
           (:builtin? manifest)
           (some? path)
           (:local/root coord))))

(defn eager-load-module-index [module-index]
  (into {} (filter (fn [[id entry]] (eager-load? id entry)) module-index)))

(defn loaded-module-ids []
  (set (map :id @started-modules*)))

(defn- rollback-loaded-modules! [started]
  (doseq [{:keys [id instance]} (reverse started)]
    (try
      (module/run-unload! instance)
      (log/info :module/unloaded :module (coords/id-str id))
      (catch Exception e
        (log/error :module/unload-failed
                   :error  (.getMessage e)
                   :module (coords/id-str id))))))

(defn- unload-module-ids! [ids]
  (when (seq ids)
    (let [to-unload (vec (reverse (filter #(contains? ids (:id %)) @started-modules*)))]
      (rollback-loaded-modules! to-unload)
      (swap! started-modules* (fn [started]
                                (vec (remove #(contains? ids (:id %)) started)))))))

(defn load-modules!
  "Instantiate each eager-load Module in `module-index` (topological order)
   and run on-load. Classpath builtin contributions without a user
   :modules declaration stay lazy (activate! on first use). Idempotent —
   already-loaded module ids are skipped."
  [module-index]
  (let [index     (eager-load-module-index module-index)
        already   (loaded-module-ids)
        order     (topological-order index)
        pending   (vec (remove already order))]
    (if (empty? pending)
      :loaded
      (let [instances (mapv (fn [module-id]
                              {:id       module-id
                               :instance (instantiate-module! module-id (get index module-id))})
                            pending)
            started   (atom [])]
        (try
          (doseq [{:keys [id instance] :as loaded-module} instances]
            (try
              (module/run-load! instance)
              (log/info :module/loaded :module (coords/id-str id))
              (swap! started conj loaded-module)
              (catch Exception e
                (throw (lifecycle-error (str "module load failed for " (coords/id-str id))
                                        {:reason    :load-failed
                                         :module-id id}
                                        e)))))
          (swap! started-modules* into @started)
          :loaded
          (catch Exception e
            (rollback-loaded-modules! @started)
            (throw e)))))))

(defn boot-stats
  "Snapshot of module boot progress for summary logging — :modules in the
   index, :loaded via factory on-load, :activated via activate!, :failed
   reserved for callers (defaults 0 on a clean boot)."
  [module-index]
  {:modules   (count module-index)
   :loaded    (count (loaded-module-ids))
   :activated (count @activated-modules*)
   :failed    0})

(defn reconcile-modules!
  "Unload eager-load modules removed from `module-index`, then load any
   new ones. Idempotent when the eager-load set is unchanged."
  [module-index]
  (let [index   (eager-load-module-index module-index)
        loaded  (loaded-module-ids)
        target  (set (keys index))
        removed (set/difference loaded target)]
    (unload-module-ids! removed)
    (load-modules! module-index)))

(defn shutdown-modules! []
  (rollback-loaded-modules! @started-modules*)
  (reset! started-modules* [])
  :stopped)

(defn start-modules!
  "Deprecated alias for `load-modules!`. Resets loaded modules first so
   callers that expect a fresh boot still get one — prefer
   `reconcile-modules!` for config-load paths."
  [module-index]
  (shutdown-modules!)
  (load-modules! module-index))

(defn clear-activations! []
  (discovery/clear-caches!)
  (reset! activated-modules* #{})
  (reset! started-modules* [])
  (doseq [handler (handlers-for :clear-registrations)]
    (handler)))
