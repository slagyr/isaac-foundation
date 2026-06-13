(ns isaac.config.schema-compose
  (:require
    [isaac.config.berths :as berths]
    [isaac.config.schema-base :as schema-base]
    [isaac.module.loader :as module-loader]))

(def ^:private berth-key :isaac.config/schema)

(defonce ^:private last-composed* (atom nil))
(defonce ^:private last-descriptors* (atom nil))

(defn- id-str [value]
  (cond
    (keyword? value) (str value)
    (symbol? value)  (str value)
    :else            (pr-str value)))

(defn- collision-error [config-key path a b]
  (ex-info (str "config-schema collision at " config-key
                (when (seq path) (str " " (vec path)))
                ": " (pr-str a) " vs " (pr-str b))
           {:config-key config-key
            :path       (vec path)
            :a          a
            :b          b
            :type       :config-schema/collision}))

(defn- invalid-schema-error [config-key module-id descriptor value]
  (ex-info (str "config-schema contribution for " config-key " must carry a valid inline :schema map: " value)
           {:config-key config-key
            :descriptor descriptor
            :module-id  module-id
            :type       :config-schema/invalid-schema
            :value      value}))

(defn contribution-entries [module-index]
  (->> module-index
       (mapcat (fn [[module-id entry]]
                 (for [[config-key descriptor] (sort-by key (get-in entry [:manifest berth-key] {}))]
                   {:config-key config-key
                    :descriptor descriptor
                    :module-id  module-id})))
       (sort-by (juxt #(id-str (:module-id %)) #(id-str (:config-key %))))))

(defn- inline-schema [{:keys [schema] :as descriptor} module-index]
  (if (map? schema)
    ;; meta-conform + :dynamic-schema gather (map-form), shared with the
    ;; reconcile engine so the effective root and the node schema agree.
    (berths/compose-config-table-schema descriptor module-index)
    (throw (ex-info (str "config-schema contribution :schema must be an inline map, got: " (pr-str schema))
                    {:schema schema :type :config-schema/invalid-schema :value schema}))))

(defn- merge-descriptors
  "Recursively deep-merge two :isaac.config/schema descriptors for the
   same config key. Maps merge key-wise; a non-map leaf present in both
   must be equal — otherwise two modules disagree on the same field,
   which is a collision. Distinct keys (e.g. one module adding its tool
   to another's :tools table) just accrete."
  ([config-key a b] (merge-descriptors config-key [] a b))
  ([config-key path a b]
   (cond
     (and (map? a) (map? b))
     (reduce-kv (fn [acc k bv]
                  (if (contains? acc k)
                    (assoc acc k (merge-descriptors config-key (conj path k) (get acc k) bv))
                    (assoc acc k bv)))
                a b)
     (= a b) a
     :else   (throw (collision-error config-key path a b)))))

(defn- merge-contributions [module-index]
  ;; Group every contribution by config key (deep-merging descriptors so
  ;; several modules can extend one table), THEN compose each merged
  ;; descriptor once — a module's partial fragment (no :type) only has to
  ;; meta-conform after it is folded into the owning table's shell.
  (let [grouped (reduce (fn [acc {:keys [config-key descriptor]}]
                          (update acc config-key
                                  (fn [existing]
                                    (if existing
                                      (merge-descriptors config-key existing descriptor)
                                      descriptor))))
                        {}
                        (contribution-entries module-index))]
    (reduce-kv
      (fn [acc config-key descriptor]
        (let [fragment (try
                         (inline-schema descriptor module-index)
                         (catch Throwable t
                           (throw (invalid-schema-error config-key nil descriptor (ex-message t)))))]
          (-> acc
              (assoc-in [:fields config-key] fragment)
              (assoc-in [:descriptors config-key] descriptor))))
      {:fields {} :descriptors {}}
      grouped)))

(defn compose-root-schema
  [module-index]
  (let [{:keys [fields]} (merge-contributions module-index)]
    (assoc schema-base/base-root :schema
           (merge (schema-base/schema-fields schema-base/base-root) fields))))

(defn descriptors
  ([module-index]
   (:descriptors (merge-contributions module-index)))
  ([] (or @last-descriptors* (descriptors (module-loader/builtin-index)))))

(defn effective-root-schema
  [module-index]
  (berths/effective-root-schema (compose-root-schema module-index) module-index))

(defn cache-composed!
  [module-index]
  (let [{:keys [descriptors]} (merge-contributions module-index)
        root                  (effective-root-schema module-index)]
    (reset! last-composed* root)
    (reset! last-descriptors* descriptors)
    root))

(defn cached-root-schema
  []
  (or @last-composed* (effective-root-schema (module-loader/builtin-index))))

(defn entity-dir-names
  []
  (->> (vals (descriptors)) (keep :entity-dir) distinct vec))

(defn frontmatter-entity-dirs
  []
  (->> (descriptors)
       vals
       (filter :frontmatter?)
       (map :entity-dir)
       set))

(defn merge-root-entity-kinds
  []
  (->> (descriptors)
       (filter (fn [[_ descriptor]] (:merge-root-entity? descriptor)))
       (map key)
       vec))

(defn normalized-config-keys
  []
  (into #{:defaults}
        (keep (fn [[kind descriptor]]
                (when (:merge-root-entity? descriptor) kind))
              (descriptors))))

(defn schema-for-kind
  [root-schema kind]
  (let [field (get-in root-schema [:schema kind])]
    (if (= kind :defaults)
      field
      (:value-spec field))))

(defn descriptor-for
  [kind]
  (get (descriptors) kind))

(defn provider-entity-schema
  [root-schema]
  (schema-for-kind root-schema :providers))

(defn clear-cache!
  []
  (reset! last-composed* nil)
  (reset! last-descriptors* nil))