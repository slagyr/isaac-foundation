(ns isaac.config.schema-compose
  (:require
    [isaac.config.berths :as berths]
    [isaac.schema.meta :as meta-schema]
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

(defn- collision-error [config-key contributors]
  (ex-info (str "config-schema collision at " config-key)
           {:config-key   config-key
            :contributors contributors
            :type         :config-schema/collision}))

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

(defn- inline-schema [{:keys [schema]}]
  (if (map? schema)
    (meta-schema/conform-spec! schema)
    (throw (ex-info (str "config-schema contribution :schema must be an inline map, got: " (pr-str schema))
                    {:schema schema :type :config-schema/invalid-schema :value schema}))))

(defn- merge-contributions [module-index]
  (reduce
    (fn [{:keys [fields descriptors owners]} {:keys [config-key descriptor module-id]}]
      (let [fragment (try
                       (inline-schema descriptor)
                       (catch Throwable t
                         (throw (invalid-schema-error config-key module-id descriptor
                                                      (ex-message t)))))]
        (if-let [existing (get owners config-key)]
          (throw (collision-error config-key [existing {:module-id module-id}]))
          {:fields       (assoc fields config-key fragment)
           :descriptors  (assoc descriptors config-key descriptor)
           :owners       (assoc owners config-key {:module-id module-id})})))
    {:fields {} :descriptors {} :owners {}}
    (contribution-entries module-index)))

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

(defn comm-base-fields
  [root-schema]
  (set (keys (:schema (get-in root-schema [:schema :comms :value-spec])))))

(defn provider-entity-schema
  [root-schema]
  (schema-for-kind root-schema :providers))

(defn clear-cache!
  []
  (reset! last-composed* nil)
  (reset! last-descriptors* nil))