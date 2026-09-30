;; mutation-tested: 2026-05-06
(ns isaac.config.normalize
  "Normalize loaded config maps (defaults/crew/models/providers/cron) into canonical form."
  (:require
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

(defn normalize-defaults
  "Canonicalize :defaults. An invalid :defaults is kept as written — the
   validation layer reports it by name; blanking it here would lose the
   default crew and model silently. Code defaults (compaction and friends) are
   not written in here: a :defaults section is what the operator configured,
   and the resolution chain owns the fallbacks.

   The conformed result overlays onto the raw :defaults field-by-field
   (isaac-dnib): a default fills, a field that fails to conform keeps its raw
   value, and an undeclared field survives — never the old whole-map {}
   discard. `raw-config?` (config get --raw) skips conform entirely."
  ([defaults] (normalize-defaults (cached-root-schema) defaults false))
  ([root-schema defaults] (normalize-defaults root-schema defaults false))
  ([root-schema defaults raw-config?]
   (cond
     raw-config?        (or defaults {})
     (not (map? defaults)) (or defaults {})
     :else
     (let [spec (schema-for root-schema :defaults)]
       (if-not spec
         defaults
         (let [result (lexicon/conform (runtime-schema spec) defaults)]
           (if (map? result)
             (schema-base/overlay-conformed defaults result)
             defaults)))))))

(defn- normalize-crew
  ([crew] (normalize-crew (cached-root-schema) crew false))
  ([root-schema crew] (normalize-crew root-schema crew false))
  ([root-schema crew raw-config?]
   (cond
     raw-config?       (if (map? crew) crew {})
     (not (map? crew)) {}
     :else
     (let [result (lexicon/conform (runtime-schema (schema-for root-schema :crew)) crew)]
       (if (map? result) (schema-base/overlay-conformed crew result) {})))))

(defn- normalize-model
  ([model] (normalize-model (cached-root-schema) model false))
  ([root-schema model] (normalize-model root-schema model false))
  ([root-schema model raw-config?]
   (cond
     raw-config?        (if (map? model) model {})
     (not (map? model)) {}
     :else
     (let [result (lexicon/conform (runtime-schema (schema-for root-schema :models)) model)]
       (if (map? result) (schema-base/overlay-conformed model result) {})))))

(defn- normalize-cron-config [cfg]
  (if (map? (:cron cfg))
    (into {} (map (fn [[id entity]]
                    [(->id id) (cond-> entity
                                       (:crew entity) (update :crew ->id))]))
          (:cron cfg))
    {}))

(defn- normalize-crew-config
  ([crew-block] (normalize-crew-config (cached-root-schema) crew-block false))
  ([root-schema crew-block] (normalize-crew-config root-schema crew-block false))
  ([root-schema crew-block raw-config?]
   (if-not (map? crew-block)
     {}
     (into {} (map (fn [[id entity]] [(->id id) (normalize-crew root-schema entity raw-config?)])) crew-block))))

(defn- normalize-model-config
  ([cfg] (normalize-model-config (cached-root-schema) cfg false))
  ([root-schema cfg] (normalize-model-config root-schema cfg false))
  ([root-schema cfg raw-config?]
   (if-not (map? (:models cfg))
     {}
     (into {} (map (fn [[id entity]] [(->id id) (normalize-model root-schema entity raw-config?)])) (:models cfg)))))

(defn- normalize-provider-config
  ([cfg] (normalize-provider-config (cached-root-schema) cfg))
  ([_root-schema cfg]
   (if-not (map? (:providers cfg))
     {}
     (into {} (map (fn [[id entity]] [(->id id) entity])) (:providers cfg)))))

(defn- assoc-present-keys [result source keys]
  (reduce (fn [acc k]
            (if (contains? source k)
              (assoc acc k (get source k))
              acc))
          result
          keys))

(def ^:private extra-present-config-keys [:dev :module-index :root :unresolved-refs])

(defn- present-config-keys [root-schema]
  (concat extra-present-config-keys
          (remove (schema-compose/normalized-config-keys)
                  (keys (schema-base/schema-fields root-schema)))))

(defn normalize-config
  ([cfg] (normalize-config (cached-root-schema) cfg false))
  ([root-schema cfg] (normalize-config root-schema cfg false))
  ([root-schema cfg raw-config?]
   (let [crew-block    (or (:crew cfg) {})
         defaults      (or (:defaults cfg) (:defaults crew-block) {})
         new-cron      (normalize-cron-config cfg)
         new-crew      (normalize-crew-config root-schema crew-block raw-config?)
         new-models    (normalize-model-config root-schema cfg raw-config?)
         new-providers (normalize-provider-config root-schema cfg)]
     (assoc-present-keys {:defaults  (normalize-defaults root-schema defaults raw-config?)
                          :crew      new-crew
                          :models    new-models
                          :providers new-providers
                          :cron      new-cron}
                         (cond-> cfg
                                 (contains? cfg :cron) (assoc :cron new-cron))
                         (present-config-keys root-schema)))))
