(ns isaac.schema.meta
  (:require
    [c3kit.apron.schema :as schema]
    [isaac.schema.lexicon :as lexicon]))

(declare spec-schema)

(defn- ref-form?
  [value]
  (or (keyword? value)
      (string? value)
      (symbol? value)
      (and (vector? value)
           (let [head (first value)]
             (or (keyword? head)
                 (string? head)
                 (symbol? head))))))

(defn- callable-form?
  [value]
  (or (fn? value)
      (ref-form? value)))

(defn- callable-or-callables?
  [value]
  (or (callable-form? value)
      (and (sequential? value)
           (every? callable-form? value))))

(defn- validations-form?
  [value]
  (and (sequential? value)
       (every? (fn [entry]
                 (or (callable-form? entry)
                     (and (map? entry)
                          (contains? entry :validate)
                          (callable-or-callables? (:validate entry))
                          (or (nil? (:message entry))
                              (string? (:message entry))))))
               value)))

(defn- invalid-spec!
  ([path message]
   (throw (ex-info (if (seq path)
                     (str "invalid schema spec at " (pr-str (vec path)) ": " message)
                     message)
                   {:path path})))
  ([path message data]
   (throw (ex-info (if (seq path)
                     (str "invalid schema spec at " (pr-str (vec path)) ": " message)
                     message)
                   (assoc data :path path)))))

(defn- assert-string-slot!
  [spec path slot]
  (when (and (contains? spec slot)
             (some? (slot spec))
             (not (string? (slot spec))))
    (invalid-spec! path (str slot " must be a string"))))

(defn- assert-callable-slot!
  [spec path slot]
  (when (and (contains? spec slot)
             (some? (slot spec))
             (not (callable-or-callables? (slot spec))))
    (invalid-spec! path (str slot " must be a function, ref, or seq of functions/refs"))))

(defn- assert-validations-slot!
  [spec path]
  (when (and (contains? spec :validations)
             (some? (:validations spec))
             (not (validations-form? (:validations spec))))
    (invalid-spec! path ":validations must be a seq of validation refs/fns or {:validate ... :message ...} maps")))

(defn- assert-known-type!
  [spec path]
  (cond
    (not (contains? spec :type))
    (invalid-spec! path "schema spec is missing required :type")

    (not (lexicon/known-type? (:type spec)))
    (invalid-spec! path (str "unknown schema type: " (pr-str (:type spec)))
                   {:type (:type spec)})))

(defn- assert-resolved-lexes!
  [spec path]
  (try
    (schema/verify-schema-lexes {:value spec})
    (catch Throwable t
      (invalid-spec! path (.getMessage t) (or (ex-data t) {})))))

(declare validate-spec!)

(defn- validate-schema-children!
  [field-schema path]
  (when-not (map? field-schema)
    (invalid-spec! path ":schema must be a map"))
  (doseq [[field child] field-schema]
    (when-not (keyword? field)
      (invalid-spec! path (str ":schema key must be a keyword: " (pr-str field))))
    (validate-spec! child (conj path field))))

(defn- validate-map-spec!
  [spec path]
  (when-not (or (contains? spec :schema)
                (contains? spec :key-spec)
                (contains? spec :value-spec))
    (invalid-spec! path ":map specs require :schema or :key-spec/:value-spec"))
  (when-let [field-schema (:schema spec)]
    (validate-schema-children! field-schema (conj path :schema)))
  (when-let [key-spec (:key-spec spec)]
    (validate-spec! key-spec (conj path :key-spec)))
  (when-let [value-spec (:value-spec spec)]
    (validate-spec! value-spec (conj path :value-spec))))

(defn- validate-seq-spec!
  [spec path]
  (when-not (contains? spec :spec)
    (invalid-spec! path ":seq specs require :spec"))
  (validate-spec! (:spec spec) (conj path :spec)))

(defn- validate-one-of-spec!
  [spec path]
  (let [specs (:specs spec)]
    (when-not (and (sequential? specs) (seq specs))
      (invalid-spec! path ":one-of specs require a non-empty :specs collection"))
    (doseq [[index child] (map-indexed vector specs)]
      (validate-spec! child (conj path :specs index)))))

(defn- validate-spec!
  [raw-spec path]
  (when (and (map? raw-spec)
             (not (contains? raw-spec :type)))
    (invalid-spec! path "schema spec is missing required :type"))
  (let [spec (schema/normalize-spec raw-spec)]
    (assert-known-type! spec path)
    (assert-string-slot! spec path :description)
    (assert-string-slot! spec path :message)
    (assert-callable-slot! spec path :coerce)
    (assert-callable-slot! spec path :present)
    (assert-callable-slot! spec path :validate)
    (assert-validations-slot! spec path)
    (assert-resolved-lexes! spec path)
    (case (:type spec)
      :map    (validate-map-spec! spec path)
      :one-of (validate-one-of-spec! spec path)
      :seq    (validate-seq-spec! spec path)
      nil)
    spec))

(defn- conform-spec!
  [value]
  (validate-spec! value []))

(def spec-schema
  {:type   :ignore
   :coerce conform-spec!})
