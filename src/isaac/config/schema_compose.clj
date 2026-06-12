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

(defn- collision-error [config-key contributors]
  (ex-info (str "config-schema collision at " config-key)
           {:config-key   config-key
            :contributors contributors
            :type         :config-schema/collision}))

(defn- invalid-fragment-error [config-key module-id descriptor value]
  (ex-info (str "config-schema fragment for " config-key " must resolve to a schema map")
           {:config-key config-key
            :descriptor descriptor
            :module-id  module-id
            :type       :config-schema/invalid-fragment
            :value      value}))

(defn contribution-entries [module-index]
  (->> module-index
       (mapcat (fn [[module-id entry]]
                 (for [[config-key descriptor] (sort-by key (get-in entry [:manifest berth-key] {}))]
                   {:config-key config-key
                    :descriptor descriptor
                    :module-id  module-id})))
       (sort-by (juxt #(id-str (:module-id %)) #(id-str (:config-key %))))))

(defn- resolve-fragment [{:keys [fragment]}]
  (let [sym  (cond
               (symbol? fragment) fragment
               (keyword? fragment) (symbol (namespace fragment) (name fragment))
               (string? fragment) (symbol fragment)
               :else (symbol (str fragment)))
        resolved (requiring-resolve sym)
        spec     (if (var? resolved) @resolved resolved)]
    (if (map? spec)
      spec
      (throw (ex-info (str "config-schema fragment must resolve to a map: " sym)
                      {:fragment fragment :type :config-schema/unresolved-fragment :value spec})))))

(defn- merge-contributions [module-index]
  (reduce
    (fn [{:keys [fields descriptors owners]} {:keys [config-key descriptor module-id]}]
      (let [fragment (try
                       (resolve-fragment descriptor)
                       (catch Throwable t
                         (throw (invalid-fragment-error config-key module-id descriptor
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