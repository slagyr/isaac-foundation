(ns isaac.module.versions
  "Module version divergence detection — conflict vs drift classification."
  (:require
    [isaac.fs :as fs]
    [isaac.module.classpath :as classpath]
    [isaac.module.coords :as coords]
    [isaac.module.discovery :as discovery]
    [isaac.module.manifest :as manifest]))

(defn inspect-module-status [context id coord]
  (cond
    (not (map? coord)) :invalid
    (not (coords/coord-shape-valid? coord)) :invalid
    (some? (coords/local-root-error context id coord)) :invalid
    :else :ok))

(defn manifest-version-at-coord [coord context]
  (when (map? coord)
    (let [fs* (coords/runtime-fs)]
      (when-let [path (coords/coord-directory coord context)]
        (when-let [manifest-path (coords/local-manifest-path path fs*)]
          (try
            (:version (manifest/read-manifest manifest-path fs*))
            (catch Exception _ nil)))))))

(defn collect-module-version-requests
  "Every module dep edge in each explicit module's deps.edn tree, tagged with
   the configuring module that introduced the requirement."
  [explicit-modules context]
  (vec
    (mapcat
      (fn [[explicit-id coord]]
        (loop [pending [[coord explicit-id]]
               seen    #{}
               found   []]
          (if (empty? pending)
            found
            (let [[c requirer] (first pending)]
              (if (contains? seen c)
                (recur (rest pending) seen found)
                (let [seen*      (conj seen c)
                      parent-dir (coords/coord-directory c context)
                      platform?  (when-let [mid (discovery/module-id-from-dep-coord c context)]
                                   (contains? coords/platform-module-ids mid))
                      child-deps (if platform?
                                   []
                                   (->> (vals (or (discovery/read-module-deps-edn c context) {}))
                                        (map #(coords/resolve-nested-dep-coord parent-dir %))))
                      records    (keep (fn [dep-coord]
                                         (let [module-id (discovery/module-id-from-dep-coord dep-coord context)
                                               version   (manifest-version-at-coord dep-coord context)]
                                           (when (and module-id
                                                      (not (contains? coords/platform-module-ids module-id))
                                                      version)
                                             {:module-id   module-id
                                              :version     version
                                              :required-by requirer
                                              :coord       dep-coord})))
                                       child-deps)
                      found*     (into found records)]
                  (recur (into (vec (rest pending))
                                (map (fn [dep] [dep requirer]) child-deps))
                         seen*
                         found*)))))))
      explicit-modules)))

(defn parse-version-parts [version]
  (mapv #(Long/parseLong %)
        (re-seq #"\d+" (str version))))

(defn compare-module-versions [a b]
  (compare (parse-version-parts a)
           (parse-version-parts b)))

(defn requested-version-entry [version reqs]
  (let [required-by (vec (sort (keep :required-by (filter #(= (:version %) version) reqs))))]
    {:version     version
     :required-by required-by}))

(defn explicit-version-request [module-id explicit-modules context]
  (when-let [coord (get explicit-modules module-id)]
    (when-let [version (manifest-version-at-coord coord context)]
      {:module-id   module-id
       :version     version
       :required-by nil
       :coord       coord
       :explicit?   true})))

(defn request-bucket [version chosen reqs]
  (let [cmp (compare-module-versions version chosen)]
    (cond
      (pos? cmp) :conflicts
      (and (neg? cmp)
           (seq (keep :required-by (filter #(= (:version %) version) reqs)))) :drift
      :else nil)))

(defn divergent-requested-versions [chosen reqs bucket]
  (->> (distinct (map :version reqs))
       (sort compare-module-versions)
       (keep (fn [version]
               (when (= bucket (request-bucket version chosen reqs))
                 (requested-version-entry version reqs))))
       vec))

(defn module-version-divergences
  "Version mediation rows for modules requested at multiple versions across the
   configured set. :chosen matches the unified resolution, including an explicit
   configured coordinate for the same module id when present. Returns grouped
   severity buckets with only divergent requested rows."
  [explicit-modules context]
  (let [requests (distinct (collect-module-version-requests explicit-modules context))
        by-id    (group-by :module-id requests)]
    (reduce (fn [{:keys [conflicts drift] :as acc} [module-id reqs]]
              (let [explicit   (explicit-version-request module-id explicit-modules context)
                    all-reqs   (cond-> (vec reqs)
                                 explicit (conj explicit))
                    versions   (distinct (keep :version all-reqs))]
                (if (<= (count versions) 1)
                  acc
                  (let [winner-coord    (or (:coord explicit)
                                            (reduce classpath/prefer-module-coord (map :coord reqs)))
                        chosen         (manifest-version-at-coord winner-coord context)
                        conflict-rows  (divergent-requested-versions chosen all-reqs :conflicts)
                        drift-rows     (divergent-requested-versions chosen all-reqs :drift)
                        conflict-entry (when (seq conflict-rows)
                                         {:id module-id :chosen chosen :requested conflict-rows})
                        drift-entry    (when (seq drift-rows)
                                         {:id module-id :chosen chosen :requested drift-rows})]
                    (cond-> acc
                      conflict-entry (update :conflicts conj conflict-entry)
                      drift-entry    (update :drift conj drift-entry))))))
            {:conflicts [] :drift []}
            (sort-by (comp coords/id-str key) by-id))))
