(ns isaac.config.schema-base
  (:require
    [isaac.schema.lexicon :as lexicon]))

(def ->id lexicon/->id)

(defn schema-fields [spec]
  (:schema spec))

(defn strip-validation-annotations [node]
  (cond
    (map? node)
    (let [node (dissoc node :validations)]
      (into {} (map (fn [[k v]] [k (strip-validation-annotations v)])) node))

    (vector? node)
    (mapv strip-validation-annotations node)

    :else
    node))

(def base-root
  {:name        :isaac
   :type        :map
   :description "Isaac's root level schema"
   :schema      {:hot-reload      {:type        :boolean
                                   :description "Enable config hot-reload watcher"}
                 :module-registry {:type        :string
                                   :description "Override for the module catalog registry (path relative to the Isaac root, or URL)."}
                 :modules         {:type        :map
                                   :key-spec    {:type :keyword}
                                   :value-spec  {:type :map}
                                   :message     "must be a map of id to coordinate (legacy vector shape)"
                                   :description "Declared modules as a map of module id to tools.deps coordinate"}
                 :server          {:type        :map
                                   :description "Retired HTTP/process settings"
                                   :schema      {:auth               {:type   :map
                                                                      :schema {:token {:type        :string
                                                                                       :validations [[:retired? "use :http :auth :token"]]}}}
                                                 :burst              {:type        :map
                                                                      :validations [[:retired? "use :http :burst"]]}
                                                 :host               {:type        :string
                                                                      :validations [[:retired? "use :http :host"]]}
                                                 :hot-reload         {:type        :boolean
                                                                      :validations [[:retired? "use :hot-reload"]]}
                                                 :port               {:type        :int
                                                                      :validations [[:retired? "use :http :port"]]}
                                                 :suspend-timeout-ms {:type        :int
                                                                      :validations [[:retired? "use :bridge :suspend-timeout-ms"]]}}}}})