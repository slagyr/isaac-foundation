(ns isaac.config.validation-lexicon
  "Registers the config-specific validation refs (:one-of?, :present-when?,
   :percentage?, :less-than?, foundation's own :berth-exists?/:gauge-exists?,
   …) into apron's schema validations lexicon at load time.

   A LEAF namespace (requires only apron schema + schema-base) so it can be
   required by BOTH isaac.config.validation (the validation engine, which uses
   these refs at run time) AND isaac.config.schema-compose (which validates each
   :isaac.config/schema contribution against the lexicon and therefore must have
   it populated before it composes). config.validation depends on schema-compose,
   so this registration cannot live there without a require cycle — and a compose
   that ran before config.validation had loaded used to freeze a lexicon-less
   schema in schema-compose's cache.

   A module that owns its own entity concept (an agent's own reference kinds,
   …) contributes its OWN existence-ref validations through the
   :isaac.config/validation-ref berth (declared in foundation's own manifest)
   rather than foundation naming that concept here — see
   `contributed-existence-refs` below. Foundation supplies the generic
   exists-ref shape; the module supplies the ref keyword, a :known symbol
   (fn [config] -> known id strings), and the error :message (isaac-h2oo)."
  (:require
    [c3kit.apron.schema :as cs]
    [clojure.string :as str]
    [isaac.config.schema-base :as schema-base]))

(def ^:dynamic *config*
  "Ambient config bound during semantic validation; the existence refs read
   their known-id sets from it."
  nil)

(defn- ->id [value]
  (schema-base/->id value))

(defn known-berth-ids [config]
  (->> (keys (:berths config)) (map ->id) distinct sort vec))

(defn known-gauge-ids [config]
  (->> (keys (:gauges config)) (map ->id) distinct sort vec))

(defn- exists-ref [ref-key known-fn message]
  {:validate (fn [value]
               (contains? (or (get-in *config* [:known-sets ref-key])
                              (set (known-fn (or (:raw *config*) *config*))))
                          (->id value)))
   :message  message
   :reference? true
   :known    (fn []
               (or (get-in *config* [:known-values ref-key])
                   (known-fn (or (:raw *config*) *config*))))})

(def ^:private existence-refs
  ;; Indirected through #(...) rather than passing known-gauge-ids /
  ;; known-berth-ids as bare values: this def runs once at load time, so a
  ;; bare symbol would capture that fn value into the closure forever —
  ;; with-redefs on the var (tests) would never be seen. The wrapper looks
  ;; the var up fresh on every call.
  {:gauge-exists? (exists-ref :gauge-exists? #(known-gauge-ids %) "references undefined gauge")
   :berth-exists? (exists-ref :berth-exists? #(known-berth-ids %) "references undefined berth")})

;; ----- Module-contributed existence refs (isaac-h2oo) -----

(def ^:private contributed-berth-key :isaac.config/validation-ref)

(defn- contributed-entries
  "[ref-key descriptor] pairs contributed by any module's manifest through
   the :isaac.config/validation-ref berth."
  [module-index]
  (mapcat (fn [[_module-id entry]] (get-in entry [:manifest contributed-berth-key]))
          module-index))

(defn- resolve-known-fn [sym]
  (or (requiring-resolve sym)
      (throw (ex-info (str "validation-ref :known symbol did not resolve: " sym) {:sym sym}))))

(defn contributed-existence-refs
  "Existence-ref validations contributed by modules via the
   :isaac.config/validation-ref berth, keyed by ref keyword and built with
   the same exists-ref shape as foundation's own refs above. Recomputed
   fresh from `module-index` on every call — no boot-order dependency."
  [module-index]
  (into {}
        (map (fn [[ref-key {:keys [known message]}]]
               [ref-key (exists-ref ref-key (resolve-known-fn known) message)]))
        (contributed-entries module-index)))

(defn register-contributed-existence-refs!
  "Registers `module-index`'s :isaac.config/validation-ref contributions into
   apron's GLOBAL shared lexicon (cs/update-lexicon!, the same mechanism
   foundation's own static refs above use). A scoped `with-lexicon` binding
   isn't enough here: config load is a long, deeply-nested pipeline (compose,
   root conform, per-entity-file validation, checks) that passes through
   several independent dynamic-var scopes and can hop threads (isaac-agent's
   :dynamic-schema gather); a scoped override closes before later steps run
   and silently drops the ref. Idempotent and safe to call repeatedly — a
   later call for the same ref-key just re-assocs the same shape. Call this
   once module-index is known, before compose or validate reads the lexicon."
  [module-index]
  (doseq [[ref-key ref-def] (contributed-existence-refs module-index)]
    (cs/update-lexicon! :validations assoc ref-key ref-def)))

(def ^:private value-refs
  ;; nil-tolerant: apron's conform also resolves these refs and (unlike the
  ;; annotation layer) runs them on absent values.
  {:positive?          {:validate #(or (nil? %) (pos-int? %))
                        :message  "must be a positive integer"}
   :non-negative?      {:validate #(or (nil? %) (and (int? %) (<= 0 %)))
                        :message  "must be a non-negative integer"}
   :absolute-path?     {:validate #(or (nil? %) (and (string? %) (str/starts-with? % "/")))
                        :message  "must be an absolute path"}
   :keyword-set?       {:validate #(or (nil? %) (and (set? %) (every? keyword? %)))
                        :message  "must be a set of keywords"}
   :keyword-or-string? {:validate #(or (nil? %) (keyword? %) (string? %))
                        :message  "must be a keyword or string"}
   :cwd-or-path?       {:validate #(or (nil? %) (#{:cwd :quarters} %) (string? %))
                        :message  "must be :cwd, :quarters, or an absolute path string"}})

(defn- one-of-ref [& allowed]
  {:validate #(or (nil? %) (contains? (set allowed) %))
   :message  (str "must be one of " (str/join ", " allowed))})

(defn- retired-ref [hint]
  {:validate nil?
   :message  (str "retired; " hint)})

(defn- requires-any-ref [& field-keys]
  ;; entity-scope; benign when apron's conform hands it a bare (nil)
  ;; pseudo-field value instead of the entity.
  {:scope    :entity
   :validate (fn [entity & _]
               (or (nil? entity)
                   (boolean (some #(seq (get entity %)) field-keys))))
   :message  (str "must include at least one of "
                  (str/join ", " (map str field-keys)))})

(defn- percentage-ref [hint]
  {:validate #(or (nil? %) (and (number? %) (<= 0.0 %) (< % 1.0)))
   :message  (str "must be a percentage in [0.0, 1.0); " hint)})

(defn- less-than-ref [smaller-key larger-key]
  {:scope    :entity
   :validate (fn [entity & _]
               (let [a (get entity smaller-key)
                     b (get entity larger-key)]
                 (or (nil? a) (nil? b) (< a b))))
   :message  (str (name smaller-key) " must be smaller than " (name larger-key))})

(defn- present-when-ref [other-key expected]
  ;; the discriminator compares by id — conformed configs carry
  ;; string-coerced values where manifests write keywords.
  {:scope    :entity
   :validate (fn [entity field-key]
               (or (not= (->id expected) (->id (get entity other-key)))
                   (cs/present? (get entity field-key))))
   :message  (str "is required when " (name other-key) " is " (->id expected))})

(defonce ^:private _refs-registered
         (do
           (cs/update-lexicon! :validations assoc :one-of? one-of-ref)
           (doseq [[k v] (merge existence-refs value-refs)]
             (cs/update-lexicon! :validations assoc k v))
           (cs/update-lexicon! :validations assoc :present-when? present-when-ref)
           (cs/update-lexicon! :validations assoc :retired? retired-ref)
           (cs/update-lexicon! :validations assoc :requires-any? requires-any-ref)
           (cs/update-lexicon! :validations assoc :percentage? percentage-ref)
           (cs/update-lexicon! :validations assoc :less-than? less-than-ref)
           true))
