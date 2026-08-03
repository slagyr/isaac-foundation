;; mutation-tested: 2026-05-06
(ns isaac.config.warnings
  "Top-level / root-entity / berth-slice / config-table unknown-key warnings."
  (:require
    [clojure.string :as str]
    [isaac.config.parse :as parse]
    [isaac.config.schema-base :as schema-base]
    [isaac.config.schema-compose :as schema-compose]))

(def ^:private ->id schema-base/->id)

(defn collect-unknown-key-warnings [warnings kind id entity entity-schema]
  ;; A non-map entity (e.g. a vector where the schema expects a map) can't have
  ;; unknown keys — leave it for schema conform to report as a type error rather
  ;; than crashing on (keys ...).
  (if-not (map? entity)
    warnings
    (let [entity-fields (schema-base/schema-fields entity-schema)]
      (reduce (fn [acc key]
                (if (contains? entity-fields key)
                  acc
                  (conj acc (parse/warning (str kind "." id "." (name key)) "unknown key"))))
              warnings
              (keys entity)))))

(defn top-level-warnings
  ([data] (top-level-warnings (schema-compose/cached-root-schema) data))
  ([root-schema data]
   (reduce (fn [acc key]
             (if (contains? (schema-base/schema-fields root-schema) key)
               acc
               (conj acc (parse/warning (name key) "unknown key"))))
           []
           (keys data))))

(defn- root-entity-warning-kinds []
  (remove #{:cron} (schema-compose/merge-root-entity-kinds)))

(defn- schema-for
  ([kind] (schema-for (schema-compose/cached-root-schema) kind))
  ([root-schema kind]
   (schema-compose/schema-for-kind root-schema kind)))

(defn root-entity-warnings
  ([raw-data] (root-entity-warnings (schema-compose/cached-root-schema) raw-data))
  ([root-schema raw-data]
   (reduce (fn [warnings kind]
             (reduce-kv (fn [acc id entity]
                          (if (map? entity)
                            (collect-unknown-key-warnings acc (name kind) (->id id) entity (schema-for root-schema kind))
                            acc))
                        warnings
                        (get raw-data kind {})))
           []
           (root-entity-warning-kinds))))

(defn root-config-warnings
  ([raw-data] (root-config-warnings (schema-compose/cached-root-schema) raw-data))
  ([root-schema raw-data]
   (concat (top-level-warnings root-schema raw-data)
           (root-entity-warnings root-schema raw-data))))

(defn slice-unknown-key-warnings
  "Unknown-key warnings for an open-map berth slice — conform strips
   unknown keys silently, so they are collected first. Shallow: one
   warning per unknown field in each slot map."
  [path spec slice]
  (let [known (set (keys (get-in spec [:value-spec :schema])))]
    (when (and (= :map (:type spec)) (seq known) (map? slice))
      (for [[slot-id slot] slice
            :when (map? slot)
            [field _] slot
            :when (not (contains? known field))]
        {:key   (str/join "." (concat (map name path) [(->id slot-id) (name field)]))
         :value "unknown key"}))))

(defn nested-unknown-key-warnings
  "Recursively collect unknown-key warnings for a config value against
   its schema. A closed map (a :schema, no :value-spec) rejects keys
   absent from the schema and descends into the known ones; an open map
   (:value-spec) accepts any key and descends into each value against the
   shared value-spec. The root conform pass strips these silently, so a
   statically-declared config table (e.g. :tools) needs them gathered."
  [path spec data]
  (when (and (= :map (:type spec)) (map? data))
    (if-let [value-spec (:value-spec spec)]
      (mapcat (fn [[k v]] (nested-unknown-key-warnings (conj path k) value-spec v)) data)
      (when-let [known (:schema spec)]
        (concat
          (for [[k _] data :when (not (contains? known k))]
            {:key (str/join "." (map ->id (conj path k))) :value "unknown key"})
          (mapcat (fn [[k v]]
                    (when-let [child (get known k)]
                      (nested-unknown-key-warnings (conj path k) child v)))
                  data))))))

(defn config-table-warnings
  "Unknown-key warnings for the statically-declared top-level config
   tables — every table except those whose warnings are produced by the
   berth-slice pass (berth-claimed paths) or the entity-collection pass
   (entity-dir kinds), which would otherwise double-report."
  [root-schema raw-data handled]
  (mapcat (fn [[key spec]]
            (when-not (contains? handled key)
              (nested-unknown-key-warnings [key] spec (get raw-data key))))
          (:schema root-schema)))
