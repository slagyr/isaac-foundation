(ns isaac.config.schema.resolve
  "Schema-path resolution against a composed root schema."
  (:require
    [c3kit.apron.schema.path :as path]
    [clojure.string :as str]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.module.loader :as module-loader]))

(def ^:private entity-collections
  #{:berths :gauges :foundries :crew :hail :models :providers})

(defn module-index-for-config
  [config result]
  (let [builtin-index       (module-loader/builtin-index)
        declared-module-ids (into (set (keys builtin-index)) (keys (or (:modules config) {})))
        discovered-index    (or (get-in result [:config :module-index]) builtin-index)]
    (select-keys discovered-index declared-module-ids)))

(defn root-schema-for
  [config result]
  (schema-compose/effective-root-schema (module-index-for-config config result)))

(defn- normalize-template-path [path-str]
  (let [segments (path/parse path-str)]
    (when (seq segments)
      (path/unparse
        (map (fn [segment]
               (if (and (= :key (first segment)) (= :value (second segment)))
                 [:key :value]
                 segment))
             segments)))))

(defn- normalize-data-path [path-str]
  (let [segments (path/parse path-str)]
    (when (seq segments)
      (path/unparse
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

(defn schema-for-path
  [root-schema path-str]
  (cond
    (or (nil? path-str) (str/blank? path-str))
    root-schema

    :else
    (try
      (or (path/schema-at root-schema path-str)
          (when-let [normalized (normalize-template-path path-str)]
            (path/schema-at root-schema normalized))
          (when-let [parent-path (parent-path-and-key-suffix path-str)]
            (:key-spec (schema-for-path root-schema parent-path))))
      (catch Exception _ nil))))

(defn schema-for-data-path
  [root-schema path-str]
  (try
    (or (schema-for-path root-schema path-str)
        (when-let [normalized (normalize-data-path path-str)]
          (path/schema-at root-schema normalized)))
    (catch Exception _ nil)))