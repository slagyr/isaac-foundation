(ns isaac.schema.dynamic
  (:require
    [c3kit.apron.schema :as schema]))

(defn- id-str [value]
  (cond
    (keyword? value) (str value)
    (symbol? value)  (str value)
    :else            (pr-str value)))

(defn contribution-entries
  [module-index berth-key]
  (->> module-index
       (mapcat (fn [[module-id entry]]
                 (for [[entry-id contribution] (sort-by key (get-in entry [:manifest berth-key] {}))]
                   {:entry      contribution
                    :entry-id   entry-id
                    :module-id  module-id})))
       (sort-by (juxt #(id-str (:module-id %))
                      #(id-str (:entry-id %))))))

(defn- collision-error [berth-key field contributors]
  (ex-info (str "dynamic-schema collision for " berth-key " at " field)
           {:berth-key    berth-key
            :contributors contributors
            :field        field
            :type         :dynamic-schema/collision}))

(defn- invalid-fragment-error [berth-key path module-id entry-id value]
  (ex-info (str "dynamic-schema path " (pr-str path) " for " berth-key
                " must resolve to a schema map")
           {:berth-key berth-key
            :entry-id  entry-id
            :module-id module-id
            :path      path
            :type      :dynamic-schema/invalid-fragment
            :value     value}))

(defn- merge-dynamic-fields [base-fields berth-key path entries]
  (reduce (fn [{:keys [fields owners]} {:keys [entry entry-id module-id]}]
            (let [fragment (get-in entry path ::missing)]
              (cond
                (= ::missing fragment)
                {:fields fields :owners owners}

                (not (map? fragment))
                (throw (invalid-fragment-error berth-key path module-id entry-id fragment))

                :else
                (reduce-kv
                  (fn [{:keys [fields owners]} field spec]
                    (if-let [existing (get owners field)]
                      (throw (collision-error berth-key field [existing {:entry-id  entry-id
                                                                         :module-id module-id}]))
                      {:fields (assoc fields field spec)
                       :owners (assoc owners field {:entry-id  entry-id
                                                    :module-id module-id})}))
                  {:fields fields :owners owners}
                  fragment))))
          {:fields base-fields
           :owners (into {} (map (fn [field] [field {:entry-id :base-schema
                                                     :module-id :base-schema}]))
                         (keys base-fields))}
          entries))

(declare compose)

(defn- compose-map [spec berth-key module-index]
  (let [schema-fields  (:schema spec)
        dynamic-path   (:dynamic-schema spec)
        composed-fields (cond-> {}
                          schema-fields
                          (into (map (fn [[field child]]
                                       (if (= :* field)
                                         [field child]
                                         [field (compose child berth-key module-index)]))
                                     schema-fields)))
        spec          (cond-> spec
                        (:key-spec spec)   (update :key-spec compose berth-key module-index)
                        (:value-spec spec) (update :value-spec compose berth-key module-index))
        spec          (assoc spec :schema composed-fields)
        merged-fields (if dynamic-path
                        (:fields (merge-dynamic-fields composed-fields berth-key dynamic-path
                                                       (contribution-entries module-index berth-key)))
                        composed-fields)]
    (-> spec
        (assoc :schema merged-fields)
        (dissoc :dynamic-schema))))

(defn compose
  [spec berth-key module-index]
  (let [spec (schema/normalize-spec spec)]
    (case (:type spec)
      :map    (compose-map spec berth-key module-index)
      :one-of (update spec :specs #(mapv (fn [child] (compose child berth-key module-index)) %))
      :seq    (update spec :spec compose berth-key module-index)
      spec)))
