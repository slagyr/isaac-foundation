(ns isaac.module.loader
  "Boot orchestration — list/compose configured modules and JVM launch deps.

   Production logic lives in isaac.module.{coords,classpath,discovery,lifecycle,
   berths,versions}. This namespace owns list/compose/launch-deps. Function vars
   below forward to those namespaces so published agent/server/hail modules that
   still require isaac.module.loader keep loading until they cut over.
   Foundation-internal callers require the owning namespace directly."
  (:require
    [clojure.set :as set]
    [isaac.module.berths :as berths]
    [isaac.module.classpath :as classpath]
    [isaac.module.coords :as coords]
    [isaac.module.discovery :as discovery]
    [isaac.module.lifecycle :as lifecycle]
    [isaac.module.versions :as versions]))

;; Dynamic vars kept for modules that bind them via isaac.module.loader.
;; Foundation binds isaac.module.classpath/* and discovery/* directly.
(def ^:dynamic *resolve-classpath?* true)
(def ^:dynamic *planned-classpath-pairs* nil)
(def ^:dynamic *skip-preload-planned?* false)
(def ^:dynamic *foundation-index-override* nil)
(def ^:dynamic *berth-registration-counts* nil)

(def register-handler! lifecycle/register-handler!)
(def user-config lifecycle/user-config)
(def activate! lifecycle/activate!)
(def activate-foundation! lifecycle/activate-foundation!)
(def deactivate-foundation! lifecycle/deactivate-foundation!)
(def activate-server! lifecycle/activate-server!)
(def activate-modules! lifecycle/activate-modules!)
(def topological-order lifecycle/topological-order)
(def load-modules! lifecycle/load-modules!)
(def boot-stats lifecycle/boot-stats)
(def reconcile-modules! lifecycle/reconcile-modules!)
(def shutdown-modules! lifecycle/shutdown-modules!)
(def start-modules! lifecycle/start-modules!)
(def clear-activations! lifecycle/clear-activations!)

(def register-builtin-berth-entry! berths/register-builtin-berth-entry!)
(def process-manifest-berths! berths/process-manifest-berths!)
(def validate-contributions! berths/validate-contributions!)

(def supporting-module-id discovery/supporting-module-id)
(def foundation-index discovery/foundation-index)
(def builtin-index discovery/builtin-index)
(def discover! discovery/discover!)
(def clear-caches! discovery/clear-caches!)
(def plan-module-classpath-pairs discovery/plan-module-classpath-pairs)
(def duplicate-berth-declaration-errors discovery/duplicate-berth-declaration-errors)
(def resolve-manifest-resource discovery/resolve-manifest-resource)

(def apply-module-classpath-pairs! classpath/apply-module-classpath-pairs!)
(def without-preload-planned! classpath/without-preload-planned!)
(def compose-module-deps-map classpath/compose-module-deps-map)

(def valid-module-coord? coords/valid-module-coord?)

(defn list-configured-modules
  "Returns {:modules [{:id :coord :status :version :required-by}]} for explicit
   config entries plus transitive module deps (deps.edn-native). Resolves the
   unified classpath via discover! so :version reflects the manifest that won
   resolution; explicit :coord values are echoed from config as written."
  [config context]
  (binding [classpath/*resolve-classpath?* true]
    (let [declared (get config :modules {})]
      (if (or (nil? declared) (not (map? declared)))
        {:modules [] :conflicts [] :drift []}
        (let [cwd              (or (:cwd context) (System/getProperty "user.dir"))
              _                (discovery/preload-planned-module-deps! declared cwd)
              explicit-modules (discovery/explicit-module-map declared context)
              explicit-ids     (set (keys explicit-modules))
              requirers        (discovery/required-by-map explicit-modules context)
              {:keys [index]}  (discovery/discover! config context)
              allowed-ids      (discovery/resolved-module-ids explicit-modules context)
              implied-ids      (sort-by coords/id-str (set/difference allowed-ids explicit-ids))
              explicit-rows
              (vec
                (for [[raw-id coord] (sort-by (fn [[k _]] (coords/id-str (or (coords/->module-id k) k))) declared)
                      :let [id (or (coords/->module-id raw-id) raw-id)
                            manifest (get-in index [id :manifest])]]
                  (cond-> {:id          id
                           :status      (versions/inspect-module-status context id (if (map? coord) coord nil))
                           :required-by []}
                    (map? coord) (assoc :coord coord)
                    (:version manifest) (assoc :version (:version manifest)))))
              implied-rows
              (vec
                (for [id implied-ids
                      :let [entry (get index id)
                            manifest (:manifest entry)]]
                  (cond-> {:id          id
                           :status      :ok
                           :required-by (vec (sort (get requirers id #{})))}
                    (:version manifest) (assoc :version (:version manifest))
                    (:coord entry) (assoc :coord (:coord entry)))))
              {:keys [conflicts drift]} (versions/module-version-divergences explicit-modules context)]
          (cond-> {:modules (vec (concat explicit-rows implied-rows))}
            (seq conflicts) (assoc :conflicts conflicts)
            (seq drift)     (assoc :drift drift)))))))

(defn compose-config-modules!
  "Adds every valid :modules coordinate plus deps.edn-implied module deps to
   the runtime classpath in one tools.deps resolution pass. :local/root paths
   are resolved relative to `cwd` (default user.dir) so packaged launchers can
   live outside the checkout. Foundation is excluded from module transitive
   deps — the seed on the classpath is authoritative."
  ([config] (compose-config-modules! config (System/getProperty "user.dir")))
  ([config cwd]
   (when-let [modules (and (map? (:modules config)) (seq (:modules config)))]
     (discovery/preload-planned-module-deps! modules cwd))))

(defn foundation-seed-path
  "Absolute path to foundation's own source root — the dir holding its
   isaac-manifest.edn. This is the seed `:paths` entry a JVM launch needs so
   `isaac.*` namespaces are on the classpath without a materialized deps.edn."
  []
  (some (fn [url]
          (when (= coords/foundation-module-id (:id (coords/read-manifest-edn url)))
            (let [f (try (java.io.File. (.toURI url)) (catch Exception _ nil))]
              (when f (.getParent f)))))
        (discovery/resource-urls "isaac-manifest.edn")))

(defn config->launch-deps
  "The `-Sdeps` map to launch isaac on the JVM from `config`: foundation's seed
   `:paths` plus every resolved module coord (`:deps`), mirroring the exact
   dependency set `compose-config-modules!` adds to bb's dynamic classpath.
   No `org.clojure/clojure` is injected — the clojure CLI's root deps supply it."
  ([config] (config->launch-deps config (System/getProperty "user.dir")))
  ([config cwd]
   (let [raw-modules (when (map? (:modules config)) (:modules config))
         pairs       (or (discovery/plan-module-classpath-pairs raw-modules cwd) [])]
     (cond-> {:deps (classpath/compose-module-deps-map pairs)}
       (foundation-seed-path) (assoc :paths [(foundation-seed-path)])))))
