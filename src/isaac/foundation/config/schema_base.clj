(ns isaac.foundation.config.schema-base
  (:require
    [c3kit.apron.schema :as cs]
    [isaac.foundation.schema.lexicon :as lexicon]))

(def ->id lexicon/->id)

(defn schema-fields [spec]
  (:schema spec))

(defn strip-validation-annotations [node]
  (cond
    (map? node)
    (let [node (dissoc node :validations)]
      (into {} (map (fn [[k v]] [k (strip-validation-annotations v)])) node))

    (vector? node)
    (mapv strip-validation-annotations node)

    :else
    node))

(declare overlay-conformed)

(defn- ->canonical-key
  "Match apron's own :id coercion (name-only, namespace dropped) so a raw
   keyword key finds its conformed counterpart even when a dynamic map's
   `:key-spec` canonicalizes keys to strings (`:id` type) — a plain
   `merge-with` compares keys for identity and would see `:parlour` and
   \"parlour\" as two different entries, silently duplicating every dynamic-
   keyed entity (isaac-dnib)."
  [k]
  (cond
    (keyword? k) (name k)
    (symbol? k) (name k)
    :else k))

(defn- overlay-map [raw conformed]
  (let [conformed-by-canon (into {} (map (fn [[k v]] [(->canonical-key k) v])) conformed)
        raw-canon-keys     (into #{} (map (comp ->canonical-key key)) raw)
        from-raw           (into {}
                             (map (fn [[k v]]
                                    (let [ck (->canonical-key k)]
                                      (if (contains? conformed-by-canon ck)
                                        [k (overlay-conformed v (get conformed-by-canon ck))]
                                        [k v]))))
                             raw)
        ;; A key only conform produced (e.g. a schema default filling an
        ;; absent map key) has no raw counterpart to key off of — add it as
        ;; conform spelled it. A required-but-absent field is also "only in
        ;; conformed" this way (apron represents that failure as the field's
        ;; own key mapped to a ValidateError, even though the key was never
        ;; there) — that error object must never leak into the runtime
        ;; config as a value; the field stays genuinely absent, exactly as
        ;; raw had it, and the failure is reported through the ordinary
        ;; errors channel instead (isaac-dnib)."
        conform-only       (into {}
                             (comp
                               (remove (fn [[k _]] (contains? raw-canon-keys (->canonical-key k))))
                               (remove (fn [[_ v]] (cs/field-error? v))))
                             conformed)]
    (merge from-raw conform-only)))

(defn overlay-conformed
  "Deep-merge `raw` (pre-conform, merged-files data) with `conformed` (the
   same data run through schema conform): conformed wins per field — a
   schema default fills an absent key, a coercible-but-wrong-typed value
   comes back coerced — but any key or subtree the schema doesn't declare
   survives from raw, recursively, and a field whose conform failed keeps
   its raw value rather than an error object (isaac-dnib, Decision #2: the
   fallback is field-by-field, never a whole-subtree replace)."
  [raw conformed]
  (cond
    (cs/field-error? conformed) raw

    (and (map? raw) (map? conformed))
    (overlay-map raw conformed)

    (nil? conformed) raw

    :else conformed))

(defn- drop-required-errors
  "Recursively drops any map entry whose conformed value is a FieldError —
   the shape apron's conform leaves at a required-but-absent field — instead
   of letting that error object leak into runtime config as a value. Used
   only on the synthetic {} conform in `conform-absent-section`: a wholly
   absent section hasn't been configured yet, so its required fields are not
   a validation failure, just absent (isaac-zmub)."
  [v]
  (cond
    (cs/field-error? v) ::drop
    (map? v) (into {} (keep (fn [[k x]]
                              (let [x' (drop-required-errors x)]
                                (when-not (= x' ::drop) [k x']))))
                   v)
    :else v))

(defn conform-absent-section
  "A schema-declared section — a root-level :isaac.config/schema fragment or
   a config-berth-claimed slice — that is wholly absent from raw config still
   conforms as {} so its nested :default values fill; a module can rely on
   them before the section is ever written (isaac-zmub). A :required field
   inside the synthetic {} conforms to a ValidateError — that is not a
   validation failure (nothing has been configured yet), so it is dropped
   rather than reported or leaked into the runtime value as an error object.
   Returns the filled map, or nil when there is nothing to contribute (not a
   :map section, or conforming {} yields no defaults)."
  [spec]
  (when (= :map (:type (cs/normalize-spec spec)))
    (let [filled (drop-required-errors (lexicon/conform (strip-validation-annotations spec) {}))]
      (when (seq filled) filled))))

(def base-root
  {:name        :isaac
   :type        :map
   :description "Isaac's root level schema"
   :schema      {:hot-reload      {:type        :boolean
                                   :description "Enable config hot-reload watcher"}
                 :module-registry {:type        :string
                                   :description "Override for the module catalog registry (path relative to the Isaac root, or URL)."}
                 :modules         {:type        :map
                                   :key-spec    {:type :keyword}
                                   :value-spec  {:type        :map
                                                :description "A tools.deps coordinate for one module"
                                                :schema      {:local/root  {:type        :string
                                                                            :description "Path to a local module checkout"
                                                                            :validations [[:requires-any? :local/root :mvn/version :git/url]]}
                                                              :mvn/version {:type        :string
                                                                            :description "Maven coordinate version"}
                                                              :git/url     {:type        :string
                                                                            :description "Git repository URL"}
                                                              :git/sha     {:type        :string
                                                                            :description "Git commit sha to check out"}
                                                              :git/tag     {:type        :string
                                                                            :description "Git tag to check out"}
                                                              :deps/root   {:type        :string
                                                                            :description "Subdirectory within the git checkout that holds the module"}
                                                              :exclusions  {:type        :seq
                                                                            :spec        {:type :any}
                                                                            :description "Libraries to exclude from this coordinate's transitive deps"}}}
                                   :message     "must be a map of id to coordinate (legacy vector shape)"
                                   :description "Declared modules as a map of module id to tools.deps coordinate"}}})