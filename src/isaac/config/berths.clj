(ns isaac.config.berths
  (:require
    [clojure.string :as str]
    [isaac.nexus :as nexus]
    [isaac.schema.dynamic :as dynamic]
    [isaac.schema.lexicon :as lexicon]
    [isaac.schema.registered-in :as registered-in]))

(defn- ordered-berth-decls [module-index]
  (reduce
    (fn [acc [_module-id entry]]
      (reduce-kv
        (fn [acc berth-id decl]
          (if (contains? acc berth-id)
            acc
            (assoc acc berth-id decl)))
        acc
        (or (get-in entry [:manifest :berths]) {})))
    (sorted-map-by #(compare (str %1) (str %2)))
    (sort-by (comp str key) module-index)))

(defn config-berths [module-index]
  (->> (ordered-berth-decls module-index)
       (keep (fn [[berth-id decl]]
               (when-let [config-decl (:config decl)]
                 [berth-id config-decl])))
       vec))

(defn config-paths [module-index]
  (->> (config-berths module-index)
       (mapv (comp :path second))))

(defn claims-path? [module-index path]
  (some #(= path %) (config-paths module-index)))

(declare composed-schema)

(defn- open-map-paths [module-index]
  (->> (config-berths module-index)
       (keep (fn [[_ config-decl]]
               (when (and (= :map (get-in config-decl [:schema :type]))
                          (get-in config-decl [:schema :value-spec]))
                 (:path config-decl))))
       vec))

(defn- rewrite-open-map-key [path key]
  (let [segments        (str/split key #"\.")
        prefix-segments (mapv name path)
        prefix-count    (count prefix-segments)]
    (when (and (> (count segments) prefix-count)
               (= prefix-segments (subvec (vec segments) 0 prefix-count)))
      (let [slot      (nth segments prefix-count)
            remainder (drop (inc prefix-count) segments)
            prefix    (str/join "." prefix-segments)]
        (str prefix
             "[:"
             slot
             "]"
             (apply str (map (fn [segment]
                               (if (str/includes? segment "/")
                                 (str "[:"
                                      segment
                                      "]")
                                 (str "." segment)))
                             remainder)))))))

(defn normalize-error-keys [module-index errors]
  (let [paths (open-map-paths module-index)]
    (mapv (fn [entry]
            (if-let [rewritten (some #(rewrite-open-map-key % (:key entry)) paths)]
              (assoc entry :key rewritten)
              entry))
          errors)))

(defn- open-map-field-message [module-index path key]
  (let [prefix (str/join "." (map name path))
        field  (some (fn [[berth-id config-decl]]
                       (when (= path (:path config-decl))
                         (let [schema    (composed-schema berth-id config-decl module-index)
                               pattern   (re-pattern (str "^"
                                                         (java.util.regex.Pattern/quote prefix)
                                                         "\\[:[^\\]]+\\](?:\\.([^\\.\\[]+)|\\[:([^\\]]+)\\])$"))
                               [_ plain bracketed] (re-matches pattern key)
                               field-key (or plain bracketed)]
                           (when field-key
                             (get-in schema [:value-spec :schema (keyword field-key) :message])))))
                     (config-berths module-index))]
    field))

(defn normalize-errors [module-index errors]
  (let [rewritten (normalize-error-keys module-index errors)
        paths     (open-map-paths module-index)]
    (mapv (fn [entry]
            (if-let [message (some #(open-map-field-message module-index % (:key entry)) paths)]
              (assoc entry :value message)
              entry))
          rewritten)))

(defn- schema-location [path]
  (vec (mapcat (fn [segment] [:schema segment]) path)))

(defn- composed-schema [berth-id config-decl module-index]
  (dynamic/compose (:schema config-decl) berth-id module-index))

(defn effective-root-schema [root-schema module-index]
  (reduce
    (fn [schema [berth-id config-decl]]
      (assoc-in schema
                (schema-location (:path config-decl))
                (composed-schema berth-id config-decl module-index)))
    root-schema
    (config-berths module-index)))

(defn- resolve-factory! [factory]
  (cond
    (fn? factory) factory
    (symbol? factory) (requiring-resolve factory)
    :else (throw (ex-info (str "invalid config berth factory: " (pr-str factory))
                          {:factory factory
                           :type    :config-berth/invalid-factory}))))

(defn- walk-factory-nodes [path spec value]
  (cond
    (:factory spec)
    [[path spec value]]

    (and (= :map (:type spec)) (:value-spec spec) (map? value))
    (mapcat (fn [[entry-id entry-value]]
              (walk-factory-nodes (conj path entry-id) (:value-spec spec) entry-value))
            value)

    (and (= :map (:type spec)) (:schema spec) (map? value))
    (mapcat (fn [[field child-spec]]
              (when (contains? value field)
                (walk-factory-nodes (conj path field) child-spec (get value field))))
            (:schema spec))

    (and (= :seq (:type spec)) (:spec spec) (sequential? value))
    (mapcat (fn [[idx entry-value]]
              (walk-factory-nodes (conj path idx) (:spec spec) entry-value))
            (map-indexed vector value))

    :else
    []))

(defn- validate-node! [module-index spec value]
  (binding [registered-in/*module-index* module-index]
    (lexicon/conform! (dissoc spec :factory) value)))

(defn install!
  "Fire-once node construction for config-berth-claimed paths. Berths in
   :exclude-berths are skipped — reconciler-owned slot trees install
   through the diff engine, not here."
  [{:keys [config module-index exclude-berths]}]
  (doseq [[berth-id config-decl] (config-berths module-index)
          :let [path  (:path config-decl)
                slice (get-in config path)]
          :when (and (some? slice)
                     (not (contains? (or exclude-berths #{}) berth-id)))
          :let [schema (composed-schema berth-id config-decl module-index)]
          [node-path node-spec node-slice] (walk-factory-nodes path schema slice)]
    (let [factory   (resolve-factory! (:factory node-spec))
          conformed (validate-node! module-index node-spec node-slice)
          node      (factory node-path conformed)]
      (nexus/register! node-path node)))
  :installed)
