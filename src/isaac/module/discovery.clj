(ns isaac.module.discovery
  "Manifest discovery, builtin indexes, classpath planning, and module listing."
  (:require
    [c3kit.apron.schema :as cs]
    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.module.classpath :as classpath]
    [isaac.module.coords :as coords]
    [isaac.module.manifest :as manifest]
    [isaac.schema.lexicon :as lexicon]
    [isaac.schema.registered-in :as registered-in]))

(declare foundation-index builtin-index invalidate-builtin-index!)

(defn resource-urls [resource-name]
  (let [loader (or (.getContextClassLoader (Thread/currentThread))
                   (clojure.lang.RT/baseLoader))]
    (enumeration-seq (.getResources loader resource-name))))

(defn manifest-resource [id]
  (some (fn [url]
          (when (= id (:id (coords/read-manifest-edn url)))
            url))
        (resource-urls "isaac-manifest.edn")))

(defn read-module-deps-edn [coord context]
  (when-let [dir (coords/coord-directory coord context)]
    (let [fs*       (coords/runtime-fs)
          deps-file (str dir "/deps.edn")]
      (when (fs/exists? fs* deps-file)
        (try
          (:deps (edn/read-string (fs/slurp fs* deps-file)))
          (catch Exception _ nil))))))

(defn module-id-from-dep-coord [coord context]
  (when (map? coord)
    (let [fs* (coords/runtime-fs)]
      (when-let [path (coords/coord-directory coord context)]
        (when-let [manifest-path (coords/local-manifest-path path fs*)]
          (try
            (:id (manifest/read-manifest manifest-path fs*))
            (catch Exception _ nil)))))))

(defn transitive-module-requirements
  "Module ids reachable from `coord` via deps.edn edges that ship isaac-manifest.edn."
  [coord context]
  (loop [pending [coord]
         seen    #{}
         found   #{}]
    (if (empty? pending)
      found
      (let [c (first pending)]
        (if (contains? seen c)
          (recur (rest pending) seen found)
          (let [seen*      (conj seen c)
                module-id  (module-id-from-dep-coord c context)
                platform?  (and module-id (contains? coords/platform-module-ids module-id))
                found*     (if (and module-id (not platform?))
                             (conj found module-id)
                             found)
                parent-dir (coords/coord-directory c context)
                child-deps (if platform?
                             []
                             (->> (vals (or (read-module-deps-edn c context) {}))
                                  (map #(coords/resolve-nested-dep-coord parent-dir %))))]
            (recur (into (vec (rest pending)) child-deps) seen* found*)))))))

(defn loadable-coord [context coord]
  (if-let [root (coords/local-root-path context coord)]
    (assoc coord :local/root root)
    coord))

(defn explicit-module-map [raw-modules context]
  (into {}
        (keep (fn [[raw-id coord]]
                (when-let [id (coords/->module-id raw-id)]
                  (when (map? coord)
                    [id (loadable-coord context coord)])))
              raw-modules)))

(defn resolved-module-ids [explicit-modules context]
  (let [explicit-ids (set (keys explicit-modules))
        implied      (reduce (fn [acc coord]
                                 (into acc (transitive-module-requirements coord context)))
                               #{}
                               (vals explicit-modules))]
    (set/union explicit-ids implied)))

(defn find-dep-coord-for-module [target-id coord context]
  (let [parent-dir (coords/coord-directory coord context)
        deps       (or (read-module-deps-edn coord context) {})]
    (or (some (fn [[_ dep-coord]]
                (let [resolved (coords/resolve-nested-dep-coord parent-dir dep-coord)]
                  (when (= target-id (module-id-from-dep-coord resolved context))
                    resolved)))
              deps)
        (some (fn [[_ dep-coord]]
                (find-dep-coord-for-module target-id
                                            (coords/resolve-nested-dep-coord parent-dir dep-coord)
                                            context))
              deps))))

(defn classpath-module-index []
  (->> (resource-urls "isaac-manifest.edn")
       (keep (fn [url]
               (when-let [manifest (coords/read-manifest-edn url)]
                 (when-let [id (:id manifest)]
                   [id {:coord {} :manifest manifest :path nil}]))))
       (into {})))

(defn required-by-map [explicit-modules context]
  (let [explicit-ids (set (keys explicit-modules))]
    (reduce
      (fn [req [explicit-id coord]]
        (let [required (transitive-module-requirements coord context)]
          (reduce (fn [m implied-id]
                    (if (contains? explicit-ids implied-id)
                      m
                      (update m implied-id (fnil conj #{}) explicit-id)))
                  req
                  required)))
      {}
      explicit-modules)))

(defn resolve-manifest-resource [id coord]
  (let [fs* (coords/runtime-fs)]
    (or (when-let [root (:local/root coord)]
          (when-not (fs/exists? fs* (str root "/deps.edn"))
            (coords/local-manifest-path root fs*)))
        (when classpath/*resolve-classpath?*
          (manifest-resource id)))))

(defn discover-resolved [id coord path]
  (try
    (let [fs*      (coords/runtime-fs)
          resource (resolve-manifest-resource id coord)]
      (if (nil? resource)
        {:errors [{:key (coords/mod-error-key id) :value "manifest: could not read"}]}
        {:entry {id {:coord    coord
                     :manifest (manifest/read-manifest resource fs*)
                     :path     path}}}))
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (cond
          ;; Custom manifest validators (see isaac.module.manifest) emit
          ;; pre-formatted error rows under this key so they can carry the
          ;; exact module-index["id"]... key shape c3kit's nested
          ;; message-map can't reproduce cleanly.
          (:isaac/manifest-errors data)
          {:errors (:isaac/manifest-errors data)}

          (cs/error? data)
          {:errors (coords/manifest-errors id data)}

          :else
          {:errors [{:key (coords/mod-error-key id) :value (.getMessage e)}]})))
    (catch Exception e
      {:errors [{:key (coords/mod-error-key id) :value (.getMessage e)}]})))

(defn discover-one [context id coord]
  (cond
    ;; Route the foundation module through `foundation-index` so the override seam
    ;; (`*foundation-index-override*`) is the single source of truth — instead
    ;; of having `discover!` re-resolve isaac-manifest.edn from disk.
    (= coords/foundation-module-id id)
    (if-let [entry (get (foundation-index) coords/foundation-module-id)]
      {:entry {coords/foundation-module-id entry}}
      {:errors [{:key (coords/mod-error-key id) :value "manifest: could not read"}]})

    (not (coords/coord-shape-valid? coord))
    {:errors [{:key (coords/mod-error-key id) :value "invalid coordinate"}]}

    :else
    (if-let [error (coords/local-root-error context id coord)]
      {:errors [error]}
      (discover-resolved id (loadable-coord context coord) (:local/root coord)))))

(defn discover-implied-entry [target-id explicit-modules context]
  (some (fn [[_ coord]]
          (when-let [dep-coord (find-dep-coord-for-module target-id coord context)]
            (let [{:keys [entry]} (discover-resolved target-id dep-coord (:local/root dep-coord))]
              (get entry target-id))))
        explicit-modules))

(defn implied-module-pairs [explicit-modules context implied-ids]
  (vec (keep (fn [id]
               (some (fn [[_ coord]]
                       (when-let [dep-coord (find-dep-coord-for-module id coord context)]
                         [id (loadable-coord context dep-coord)]))
                     explicit-modules))
             implied-ids)))

(defn explicit-preload-pairs [raw-modules cwd]
  (let [ctx {:cwd cwd}]
    (vec (keep (fn [[raw-id coord]]
                 (when-let [id (coords/->module-id raw-id)]
                   (when (map? coord)
                     (let [abs-coord (coords/absolutize-local-root coord cwd)]
                       (when (and (coords/needs-classpath-preload? abs-coord)
                                  (not (coords/local-root-error ctx id abs-coord)))
                         [id abs-coord])))))
               raw-modules))))

(defn classpath-preload-pairs [pairs ctx]
  (vec (keep (fn [[id coord]]
               (when (and (coords/needs-classpath-preload? coord)
                          (not (coords/local-root-error ctx id coord)))
                 [id coord]))
             pairs)))

(defn plan-module-classpath-pairs
  "Every explicit :modules entry plus deps.edn-implied module coords, deduped
   to one coordinate per module id before the single tools.deps pass."
  [raw-modules cwd]
  (when (seq raw-modules)
    (let [ctx              {:cwd cwd}
          explicit-modules (explicit-module-map raw-modules ctx)
          explicit-pairs   (explicit-preload-pairs raw-modules cwd)
          implied-ids      (set/difference (resolved-module-ids explicit-modules ctx)
                                           (set (keys explicit-modules)))
          implied-pairs    (implied-module-pairs explicit-modules ctx implied-ids)]
      (classpath-preload-pairs (classpath/dedupe-module-pairs (vec (concat explicit-pairs implied-pairs)))
                               ctx))))

(defn preload-planned-module-deps! [raw-modules cwd]
  (when (and classpath/*resolve-classpath?* (not classpath/*skip-preload-planned?*))
    (let [pairs (if classpath/*planned-classpath-pairs*
                  classpath/*planned-classpath-pairs*
                  (plan-module-classpath-pairs raw-modules cwd))]
      (classpath/preload-module-pairs! (or pairs [])))))

(defn merge-resolved-classpath-modules [index explicit-modules context]
  (if-not classpath/*resolve-classpath?*
    index
    (let [explicit-ids (set (keys explicit-modules))
          implied-ids  (set/difference (resolved-module-ids explicit-modules context)
                                       explicit-ids)]
      (reduce
        (fn [idx id]
          (if-let [entry (discover-implied-entry id explicit-modules context)]
            (assoc idx id (merge (get idx id {}) entry))
            idx))
        index
        implied-ids))))

(defn cycle-errors [index]
  (let [id->requires (into {} (map (fn [[id e]] [id (keys (get-in e [:manifest :deps] {}))]) index))
        white        (atom (set (keys id->requires)))
        gray         (atom #{})
        found        (atom [])]
    (letfn [(dfs [node]
              (swap! white disj node)
              (swap! gray conj node)
              (doseq [req (get id->requires node [])]
                (when (contains? id->requires req)
                  (cond
                    (contains? @gray req)
                    (swap! found conj {:key   (str "modules[\"" (coords/id-str req) "\"]")
                                       :value (str "dependency cycle detected involving " (coords/id-str req))})

                    (contains? @white req)
                    (dfs req))))
              (swap! gray disj node))]
      (doseq [node (keys id->requires)]
        (when (contains? @white node)
          (dfs node)))
      @found)))

(defn supporting-module-id [module-index berth-id capability]
  (let [cap-key (cond
                  (keyword? capability) capability
                  (string? capability)  (keyword capability)
                  :else                 (keyword (str capability)))]
    (some (fn [[module-id entry]]
            (when (get-in entry [:manifest berth-id cap-key])
              module-id))
          module-index)))

(defonce ^:private foundation-index-cache (atom nil))

(defonce ^:private builtin-index-cache (atom nil))

(defn invalidate-builtin-index! []
  (reset! builtin-index-cache nil))

(def ^:dynamic *foundation-index-override* nil)

(defn index-entry [resource]
  (let [manifest (manifest/read-manifest resource (fs/instance))]
    [(:id manifest) {:coord {} :manifest manifest :path nil}]))

(defn foundation-index []
  (or *foundation-index-override*
      @foundation-index-cache
      (let [result (if-let [resource (manifest-resource coords/foundation-module-id)]
                     (let [manifest (manifest/read-manifest resource (fs/instance))]
                        {coords/foundation-module-id {:coord {} :manifest manifest :path nil}})
                      {})]
        (reset! foundation-index-cache result)
        result)))

(defn builtin-manifest-resource? [resource]
  (true? (:builtin? (coords/read-manifest-edn resource))))

(defn classpath-builtin-index []
  (->> (resource-urls "isaac-manifest.edn")
       (filter builtin-manifest-resource?)
       (map index-entry)
       (into {})))

(defn builtin-index []
  (or *foundation-index-override*
      @builtin-index-cache
      (let [result (merge (foundation-index) (classpath-builtin-index))]
        (reset! builtin-index-cache result)
        result)))

(defn pending-deps
  "Pairs of [consumer-id dep-id coord] for deps not yet in `index`."
  [index]
  (mapcat (fn [[consumer-id entry]]
            (let [deps (get-in entry [:manifest :deps])]
              (when (map? deps)
                (keep (fn [[dep-id coord]]
                        (when (and (not (contains? index dep-id))
                                   (map? coord))
                          [consumer-id dep-id coord]))
                      deps))))
          index))

(defn dep-resolution-error [consumer-id dep-id]
  {:key   (str "module-index[\"" (coords/id-str consumer-id) "\"].deps[" dep-id "]")
   :value "failed to resolve coordinate"})

(defn resolve-deps!
  "Iteratively walks each loaded manifest's `:deps` and resolves any
   modules not already in `index`. Each pass batches every pending dep
   onto the classpath in one tools.deps resolution before reading
   manifests. Reports each failed resolution as
   `module-index[\"<consumer>\"].deps[<dep-id>]` so the user can see
   which consumer dragged the offending dep in. Index membership
   doubles as a cycle guard — A → B → A stops when B sees A already
   resolved."
  [context initial-index]
  (loop [index  initial-index
         errors []]
    (let [pending (pending-deps index)]
      (if (empty? pending)
        {:index index :errors errors}
        (let [preload-pairs
              (vec (keep (fn [[consumer-id dep-id coord]]
                            (when (and (not (contains? index dep-id))
                                       (map? coord))
                              [dep-id (loadable-coord context coord)]))
                          pending))
              _ (when classpath/*resolve-classpath?*
                  (classpath/preload-module-pairs! preload-pairs))
              {:keys [new-entries new-errors]}
              (reduce
                (fn [{:keys [new-entries new-errors]} [consumer-id dep-id coord]]
                  (cond
                    (contains? index dep-id)        {:new-entries new-entries
                                                     :new-errors  new-errors}
                    (contains? new-entries dep-id)  {:new-entries new-entries
                                                     :new-errors  new-errors}
                    :else
                    (let [{:keys [entry] mod-errors :errors} (discover-one context dep-id coord)]
                      (if (seq mod-errors)
                        {:new-entries new-entries
                         :new-errors  (conj new-errors (dep-resolution-error consumer-id dep-id))}
                        {:new-entries (merge new-entries entry)
                         :new-errors  new-errors}))))
                {:new-entries {} :new-errors []}
                pending)]
          (if (empty? new-entries)
            ;; No forward progress — stop. Any remaining unresolved deps
            ;; landed in new-errors this pass.
            {:index index :errors (into errors new-errors)}
            (recur (merge index new-entries) (into errors new-errors))))))))

(defn duplicate-berth-declaration-errors
  "A berth-id may be declared by only one module. Two modules declaring
   the same berth would silently first-win (find-berth-decl takes the
   first), validating contributions against whichever won the walk — so
   flag it as a located error at discovery."
  [module-index]
  (->> module-index
       (mapcat (fn [[module-id entry]]
                 (for [berth-id (keys (get-in entry [:manifest :berths] {}))]
                   [berth-id module-id])))
       (group-by first)
       (sort-by key)
       (keep (fn [[berth-id pairs]]
               (let [modules (distinct (map second pairs))]
                 (when (> (count modules) 1)
                   {:key   (str "berths[" berth-id "]")
                    :value (str "berth declared by multiple modules: "
                                (str/join ", " (map coords/id-str (sort-by coords/id-str modules))))}))))
       vec))

(defn discover!
  "Resolves module coordinates from config :modules and returns
   {:index {...} :errors [...]}."
  [config context]
  (let [declared    (get config :modules {})
        raw-modules (when (map? declared) declared)]
    (preload-planned-module-deps! raw-modules (or (:cwd context) (System/getProperty "user.dir")))
    (if (and (some? declared) (not (map? declared)))
      {:index  (builtin-index)
       :errors [{:key "modules"
                 :value "must be a map of id to coordinate (legacy vector shape)"}]}
      (let [{init-index :index init-errors :errors}
            (reduce-kv (fn [{:keys [index errors]} raw-id coord]
                         (let [id (coords/->module-id raw-id)]
                           (if (or (nil? id) (not (map? coord)))
                             {:index  index
                              :errors (conj errors {:key   (coords/mod-error-key (or id raw-id))
                                                    :value "invalid coordinate"})}
                             (let [{entry :entry mod-errors :errors} (discover-one context id coord)]
                               {:index  (merge index entry)
                                :errors (into errors (or mod-errors []))}))))
                       {:index (builtin-index) :errors []}
                       raw-modules)
            explicit-modules (explicit-module-map raw-modules context)
            merged-index   (merge-resolved-classpath-modules init-index explicit-modules context)
            {:keys [index errors]} (resolve-deps! context merged-index)]
        ;; Note: manifest-only berth processing (per-entry :factory
        ;; invocation, isaac-8yxs) must run OUTSIDE the load's
        ;; nested-nexus wrap or the wrap's restore discards any
        ;; nexus registrations the factories make. Callers invoke
        ;; process-manifest-berths! after load returns.
        {:index  index
         :errors (into (into init-errors errors)
                       (concat (cycle-errors index)
                               (duplicate-berth-declaration-errors index)
                               ((requiring-resolve 'isaac.module.berths/validate-contributions!) index)))}))))



(defn clear-caches! []
  (reset! foundation-index-cache nil)
  (reset! builtin-index-cache nil))

(classpath/set-on-modules-loaded! #(invalidate-builtin-index!))
