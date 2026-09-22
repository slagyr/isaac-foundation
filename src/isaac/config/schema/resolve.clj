(ns isaac.config.schema.resolve
  "Schema-path resolution against a composed root schema."
  (:require
    [c3kit.apron.schema.path :as path]
    [clojure.string :as str]
    [isaac.config.paths :as paths]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.module.discovery :as discovery]
))

(def ^:private entity-collections
  #{:berths :gauges :foundries :crew :hail :models :providers})

(defn module-index-for-config
  [config result]
  (let [builtin-index       (discovery/builtin-index)
        declared-module-ids (into (set (keys builtin-index)) (keys (or (:modules config) {})))
        discovered-index    (or (get-in result [:config :module-index]) builtin-index)]
    (select-keys discovered-index declared-module-ids)))

(defn root-schema-for
  [config result]
  (schema-compose/effective-root-schema (module-index-for-config config result)))

(defn- unparse-segment [segment]
  ;; c3kit's unparse renders a qualified keyword as a bracketed form its own
  ;; parse cannot re-read as that keyword. This isaac-side unparse keeps
  ;; `ns/name` as an identifier so a path with namespace-qualified segments
  ;; round-trips through schema-at (isaac-cgxa).
  (if (and (= :key (first segment)) (qualified-keyword? (second segment)))
    (str (namespace (second segment)) "/" (name (second segment)))
    (path/unparse [segment])))

(defn- segments->path [segments]
  (str/join "." (map unparse-segment segments)))

(defn- normalize-template-path [path-str]
  (let [segments (paths/parse-path-segments path-str)]
    (when (seq segments)
      (segments->path
        (map (fn [segment]
               (if (and (= :key (first segment)) (= :value (second segment)))
                 [:key :value]
                 segment))
             segments)))))

(defn- normalize-data-path [path-str]
  (let [segments (paths/parse-path-segments path-str)]
    (when (seq segments)
      (segments->path
        (map-indexed (fn [idx segment]
                       (if (and (= 1 idx)
                                (contains? entity-collections (second (first segments)))
                                (#{:key :str} (first segment)))
                         [:key :value]
                         segment))
                     segments)))))

(defn- parent-path-and-key-suffix [path-str]
  (let [suffix ".key"]
    (when (and path-str (str/ends-with? path-str suffix) (> (count path-str) (count suffix)))
      (subs path-str 0 (- (count path-str) (count suffix))))))

(defn- key-segment-for-schema [spec k]
  (cond
    (nil? spec)             nil
    (and (= k :value) (or (:value-spec spec) (= :seq (:type spec))))
    (or (:value-spec spec) (:spec spec))

    (and (= k :key) (:key-spec spec))
    (:key-spec spec)

    (map? (:schema spec)) (get (:schema spec) k)
    :else                 (get spec k)))

(defn- schema-at-segments
  "Descend root-schema along pre-parsed segments. A :key segment on a map
   without a matching field falls to :value-spec (entity-collection
   semantics), mirroring c3kit's descend-schema except that a
   namespace-qualified keyword stays one segment (isaac-cgxa)."
  [root-schema segments]
  (reduce
    (fn [spec segment]
      (when spec
        (case (first segment)
          :key   (or (when (map? (:schema spec)) (get (:schema spec) (second segment)))
                     (key-segment-for-schema spec (second segment)))
          :str   (when (= :map (:type spec)) (:value-spec spec))
          :index (case (:type spec)
                   :map (:value-spec spec)
                   :seq (:spec spec)
                   nil))))
    root-schema
    segments))

(declare schema-for-path)

(defn schema-for-path
  [root-schema path-str]
  (cond
    (or (nil? path-str) (str/blank? path-str))
    root-schema

    :else
    (try
      (or (schema-at-segments root-schema (paths/parse-path-segments path-str))
          (when-let [normalized (normalize-template-path path-str)]
            (schema-at-segments root-schema (paths/parse-path-segments normalized)))
          (when-let [parent-path (parent-path-and-key-suffix path-str)]
            (:key-spec (schema-for-path root-schema parent-path))))
      (catch Exception _ nil))))

(defn schema-for-data-path
  [root-schema path-str]
  (try
    (or (schema-for-path root-schema path-str)
        (when-let [normalized (normalize-data-path path-str)]
          (schema-at-segments root-schema (paths/parse-path-segments normalized))))
    (catch Exception _ nil)))