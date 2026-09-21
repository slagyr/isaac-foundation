;; mutation-tested: 2026-05-06
(ns isaac.config.warnings
  "Top-level / root-entity / berth-slice / config-table unknown-key warnings."
  (:require
    [clojure.string :as str]
    [isaac.config.parse :as parse]
    [isaac.config.schema-base :as schema-base]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.logger :as log]))

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

(defn- seg->path-part [seg]
  (if (keyword? seg) (name seg) (str seg)))

(defn- join-path [parts]
  ;; A "[idx]" segment glues to the previous part instead of taking a dot.
  (loop [acc [] [p & rest] parts]
    (cond
      (nil? p)            (str/join "." acc)
      (and (str/starts-with? p "[") (seq acc))
      (recur (assoc acc (dec (count acc)) (str (last acc) p)) rest)
      :else (recur (conj acc p) rest))))

(defn- slot-walk
  "Recursively collect unknown-key warnings for one value against its spec.
   Mirrors what conform prunes: a closed map keeps declared fields and drops
   the rest; an open map (:value-spec) accepts any key and descends into each
   value; a seq descends per entry. Nested seq indices render as [idx]."
  [prefix spec data]
  (cond
    (and (= :map (:type spec)) (map? data))
    (if-let [value-spec (:value-spec spec)]
      ;; keyed open map: any key survives (coerced per :key-spec); values
      ;; conform to value-spec. Descend; nothing is silently pruned here.
      (mapcat (fn [[k v]] (slot-walk (conj prefix k) value-spec v)) data)
      (if-let [known (:schema spec)]
        ;; closed map: conform keeps declared fields and silently drops the rest
        (concat
          (for [[k _] data :when (not (contains? known k))]
            {:key   (join-path (mapv seg->path-part (conj prefix k)))
             :value "unknown key"})
          (mapcat (fn [[k v]]
                    (when-let [child (get known k)]
                      (slot-walk (conj prefix k) child v)))
                  data))
        ;; bare {:type :map} — no :schema, no :value-spec, no :key-spec:
        ;; conform prunes EVERY key inside (isaac-12fo's :env). Each content
        ;; key is reported so the operator sees exactly what was dropped.
        (for [[k _] data]
          {:key   (join-path (mapv seg->path-part (conj prefix k)))
           :value "unknown key"})))

    (and (= :seq (:type spec)) (sequential? data) (:spec spec))
    (mapcat (fn [[idx entry]]
              (slot-walk (conj prefix (str "[" idx "]")) (:spec spec) entry))
            (map-indexed vector data))

    :else nil))

(defn slice-unknown-key-warnings
  "Unknown-key warnings for an open-map berth slice — conform strips
   unknown keys silently, so they are collected first. Recursive: a
   closed :map field with no :key-spec has its contents pruned too
   (isaac-12fo's :env), so the walk descends into closed maps, open
   maps, and seq entries."
  [path spec slice]
  (let [value-spec (:value-spec spec)]
    (when (and (= :map (:type spec)) (map? value-spec) (map? slice))
      (mapcat (fn [[slot-id slot]]
                (when (map? slot)
                  (slot-walk (conj (vec path) (->id slot-id)) value-spec slot)))
              slice))))

(def ^:private unknown-key-message "unknown key")

(defn- slice-and-key
  "Split a warning path into the slice that owns it and the offending key:
   `signals[:mychan].account-id` -> [\"signals[:mychan]\" \"account-id\"].
   A bare top-level key has no slice."
  [path]
  (if-let [dot (str/last-index-of path ".")]
    [(subs path 0 dot) (subs path (inc dot))]
    [nil path]))

(defn log-unknown-keys!
  "Warn-log every unknown-key row so a key the schema does not recognise is
   visible at boot, not only to an operator who separately runs
   `isaac config validate` (isaac-nq4c). Warning, never error: forward-
   compatible config — a key a newer module will understand — must not break
   a boot. Returns `warnings` so it can sit inside the load pipeline."
  [warnings]
  (doseq [{:keys [key value]} warnings
          :when (= unknown-key-message value)]
    (let [[slice unknown] (slice-and-key key)]
      (log/warn :config/unknown-key :slice slice :key unknown :path key)))
  warnings)

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
