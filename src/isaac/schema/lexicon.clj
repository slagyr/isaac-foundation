(ns isaac.schema.lexicon
  (:require
    [c3kit.apron.schema :as schema]
    [clojure.string :as str]))

(defn ->id
  "Canonical id coercion: keywords become their name, strings pass
   through, nil stays nil, anything else stringifies."
  [value]
  (cond
    (keyword? value) (name value)
    (string? value) value
    (nil? value) nil
    :else (str value)))

;; Set by isaac.schema.meta at load to the meta-schema validator for a
;; fields-map (field keyword → spec). nil = pass-through until meta is
;; loaded; meta is loaded wherever schema validation actually matters.
;; Lives here (not meta) because meta requires lexicon, not vice versa.
(defonce schema-map-validator (atom nil))

(def ^:private builtin-types
  {:symbol     {:validations [{:validate (schema/nil?-or symbol?)
                               :message  "must be a symbol"}]}
   :id         {:coercions   [->id]
                :validations [{:validate (schema/nil?-or string?)
                               :message  "must be an id (string or keyword)"}]}
   ;; an apron schema literal — a map of field keyword → spec, validated
   ;; against the meta-schema (e.g. comm/tool berth :extra-schema fields).
   ;; meta populates schema-map-validator at load; if it hasn't loaded
   ;; yet, requiring it here is a no-cycle lazy trigger (meta → lexicon).
   :schema-map {:validations [{:validate (schema/nil?-or
                                           (fn [v]
                                             (when (nil? @schema-map-validator)
                                               (requiring-resolve 'isaac.schema.meta/valid-schema?))
                                             (boolean ((or @schema-map-validator (constantly true)) v))))
                               :message  "must be a schema map of field → spec"}]}})

(defonce registry* (atom {}))

(defn clear!
  []
  (reset! registry* {}))

(defn register-type!
  [{:keys [coerce message name validate]}]
  (let [entry (cond-> {:validations [{:validate (schema/nil?-or validate)
                                      :message  (or message (str "must be a " (str/replace (clojure.core/name name) "-" " ")))}]}
                coerce (assoc :coercions [coerce]))]
    (swap! registry* assoc name entry)
    entry))

(defn- active-types []
  (merge (:types schema/default-lexicon) builtin-types @registry*))

(defn known-type?
  [type]
  (contains? (active-types) type))

(defn- base-lexicon []
  (or (some-> #'schema/*lexicon* var-get)
      schema/default-lexicon))

(defn- active-lexicon []
  (assoc (base-lexicon) :types (active-types)))

(defn- unknown-type-error [type]
  (ex-info (str "unknown schema type: " (pr-str type)) {:type type}))

(defn- assert-known-type! [spec]
  (when-not (known-type? (:type spec))
    (throw (unknown-type-error (:type spec)))))

(defn- assert-known-types! [schema-or-spec]
  (let [emit (fn [spec _]
               (assert-known-type! spec)
               nil)]
    (if (:type schema-or-spec)
      (schema/walk-schema emit schema-or-spec)
      (doseq [[_ spec] schema-or-spec]
        (schema/walk-schema emit spec)))))

(defn- value-spec? [schema-or-spec]
  (and (:type schema-or-spec)
       (not= :map (:type schema-or-spec))))

(defn- coerce-value [spec value]
  (let [result (schema/coerce {:value spec} {:value value})]
    (if (schema/error? result) (:value result) (:value result))))

(defn- conform-value [spec value]
  (let [result (schema/conform {:value spec} {:value value})]
    (if (schema/error? result) (:value result) (:value result))))

(defn coerce
  [schema-or-spec value]
  (binding [schema/*lexicon* (active-lexicon)]
    (assert-known-types! schema-or-spec)
    (if (value-spec? schema-or-spec)
      (coerce-value schema-or-spec value)
      (schema/coerce schema-or-spec value))))

(defn coerce!
  [schema-or-spec value]
  (binding [schema/*lexicon* (active-lexicon)]
    (assert-known-types! schema-or-spec)
    (if (value-spec? schema-or-spec)
      (schema/coerce-value! schema-or-spec value)
      (schema/coerce! schema-or-spec value))))

(defn conform
  [schema-or-spec value]
  (binding [schema/*lexicon* (active-lexicon)]
    (assert-known-types! schema-or-spec)
    (if (value-spec? schema-or-spec)
      (conform-value schema-or-spec value)
      (schema/conform schema-or-spec value))))

(defn conform!
  [schema-or-spec value]
  (binding [schema/*lexicon* (active-lexicon)]
    (assert-known-types! schema-or-spec)
    (if (value-spec? schema-or-spec)
      (schema/conform-value! schema-or-spec value)
      (schema/conform! schema-or-spec value))))
