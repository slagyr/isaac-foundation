;; mutation-tested: 2026-05-06
(ns isaac.config.normalize
  "Normalize loaded config maps (defaults/crew/models/providers/cron) into canonical form."
  (:require
    [c3kit.apron.schema :as cs]
    [clojure.set :as set]
    [isaac.config.schema-base :as schema-base]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.schema.lexicon :as lexicon]))

(def ^:private ->id schema-base/->id)

(defn- runtime-schema [spec]
  (schema-base/strip-validation-annotations spec))

(defn- cached-root-schema []
  (schema-compose/cached-root-schema))

(defn- schema-for
  ([kind] (schema-for (cached-root-schema) kind))
  ([root-schema kind]
   (schema-compose/schema-for-kind root-schema kind)))

(def ^:private default-compaction-policy
  {:async? false :strategy :rubberband :head 0.3 :threshold 0.8})

(defn- ensure-default-compaction [defaults]
  (update defaults :compaction
          #(merge default-compaction-policy %)))

(defn normalize-defaults
  ([defaults] (normalize-defaults (cached-root-schema) defaults))
  ([root-schema defaults]
   (let [spec (schema-for root-schema :defaults)]
     (if-not spec
       (ensure-default-compaction (or defaults {}))
       (let [result (lexicon/conform (runtime-schema spec) defaults)]
         (if (cs/error? result) {}
             (ensure-default-compaction result)))))))

(defn- normalize-crew
  ([crew] (normalize-crew (cached-root-schema) crew))
  ([root-schema crew]
   (let [result (lexicon/conform (runtime-schema (schema-for root-schema :crew)) crew)]
     (if (cs/error? result) {} result))))

(defn- normalize-model
  ([model] (normalize-model (cached-root-schema) model))
  ([root-schema model]
   (let [result (lexicon/conform (runtime-schema (schema-for root-schema :models)) model)]
     (if (cs/error? result) {} result))))

(defn- normalize-cron-config [cfg]
  (if (map? (:cron cfg))
    (into {} (map (fn [[id entity]]
                    [(->id id) (cond-> entity
                                       (:crew entity) (update :crew ->id))]))
          (:cron cfg))
    {}))

(defn- modern-crew-map? [crew-block]
  (and (map? crew-block)
       (empty? (set/intersection #{:defaults :list :models} (set (keys crew-block))))))

(defn- normalize-crew-config
  ([crew-block] (normalize-crew-config (cached-root-schema) crew-block))
  ([root-schema crew-block]
   (let [old-crew-list (or (:list crew-block) [])]
     (cond
       (modern-crew-map? crew-block)
       (into {} (map (fn [[id entity]] [(->id id) (normalize-crew root-schema entity)])) crew-block)

       (seq old-crew-list)
       (into {} (map (fn [entity] [(->id (:id entity)) (normalize-crew root-schema entity)])) old-crew-list)

       :else
       {}))))

(defn- normalize-model-config
  ([cfg crew-block] (normalize-model-config (cached-root-schema) cfg crew-block))
  ([root-schema cfg crew-block]
   (let [old-models (or (:models crew-block) {})]
     (cond
       (and (map? (:models cfg))
            (not (vector? (:models cfg)))
            (not (:providers (:models cfg))))
       (into {} (map (fn [[id entity]] [(->id id) (normalize-model root-schema entity)])) (:models cfg))

       (seq old-models)
       (into {} (map (fn [[id entity]] [(->id id) (normalize-model root-schema entity)])) old-models)

       :else
       {}))))

(defn- normalize-provider-config
  ([cfg] (normalize-provider-config (cached-root-schema) cfg))
  ([_root-schema cfg]
   (let [old-providers (or (get-in cfg [:models :providers]) [])]
     (cond
       (map? (:providers cfg))
       (into {} (map (fn [[id entity]] [(->id id) entity])) (:providers cfg))

       (seq old-providers)
       (into {} (map (fn [entity] [(->id (or (:id entity) (:name entity))) (dissoc entity :name)])) old-providers)

       :else
       {}))))

(defn- assoc-present-keys [result source keys]
  (reduce (fn [acc k]
            (if (contains? source k)
              (assoc acc k (get source k))
              acc))
          result
          keys))

(def ^:private extra-present-config-keys [:dev :module-index :root])

(defn- present-config-keys [root-schema]
  (concat extra-present-config-keys
          (remove (schema-compose/normalized-config-keys)
                  (keys (schema-base/schema-fields root-schema)))))

(defn normalize-config
  ([cfg] (normalize-config (cached-root-schema) cfg))
  ([root-schema cfg]
   (let [crew-block    (or (:crew cfg) {})
         defaults      (or (:defaults cfg) (:defaults crew-block) {})
         new-cron      (normalize-cron-config cfg)
         new-crew      (normalize-crew-config root-schema crew-block)
         new-models    (normalize-model-config root-schema cfg crew-block)
         new-providers (normalize-provider-config root-schema cfg)]
     (assoc-present-keys {:defaults  (normalize-defaults root-schema defaults)
                          :crew      new-crew
                          :models    new-models
                          :providers new-providers
                          :cron      new-cron}
                         (cond-> cfg
                                 (contains? cfg :cron) (assoc :cron new-cron))
                         (present-config-keys root-schema)))))
