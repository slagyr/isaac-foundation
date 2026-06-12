(ns isaac.module.manifest
  (:require
    [c3kit.apron.schema :as schema]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.schema.lexicon :as lexicon]))

(def manifest-schema
  {:name   :module/manifest
   :type   :map
   :schema {:id            {:type     :keyword
                            :validate schema/present?
                            :message  "is required"}
            :bootstrap     {:type :ignore}
            :version       {:type     :string
                            :validate schema/present?
                            :message  "must be present"}
            :builtin?      {:type :boolean}
            :description   {:type :string}
            ;; :berths and :deps stay :ignore here because their nested
            ;; error keys are easier to emit directly (see
            ;; validate-berths-and-deps!) than to coax out of c3kit's
            ;; message-map. :factory uses the lexicon's :symbol type.
            :factory       {:type :symbol}
            :berths        {:type :ignore}
            :deps          {:type :ignore}
            ;; :cli is no longer a hardcoded extension kind — it's a berth
            ;; (declared by isaac.core's manifest under :berths) and
            ;; contributions flow through process-manifest-berths!. Stays
            ;; ignored at the schema layer so existing manifests that put
            ;; :cli at the top level still parse.
            :cli           {:type :ignore}}})

(def ^:private known-meta-keys #{:berths :bootstrap :builtin? :cli :deps :description :factory :id :version})
(def ^:private known-extend-kinds #{})
(def ^:private known-keys (into known-meta-keys known-extend-kinds))

(defn- validate-bootstrap! [path manifest]
  (when (and (contains? manifest :bootstrap)
             (some? (:bootstrap manifest))
             (not (symbol? (:bootstrap manifest))))
    (throw (ex-info "bootstrap must be a symbol"
                    {:field :bootstrap :path path}))))

(defn- validate-v2-entries! [path manifest]
  (doseq [kind known-extend-kinds
          [extension-id extension] (get manifest kind)]
    (when (contains? extension :isaac/factory)
      (throw (ex-info ":isaac/factory is no longer supported; use :factory"
                      {:field :isaac/factory :extension-id extension-id
                       :kind kind :path path})))
    (when (= :cli kind)
      (when-not (symbol? (:factory extension))
        (throw (ex-info ":factory is required and must be a symbol"
                        {:field :factory :extension-id extension-id
                          :kind kind :path path}))))
    (when (contains? extension :sort-index)
      (throw (ex-info ":sort-index is no longer supported"
                      {:field :sort-index :extension-id extension-id
                       :kind kind :path path})))))

(defn- id-str [id]
  (cond
    (qualified-keyword? id) (str (namespace id) "/" (name id))
    (keyword? id)           (name id)
    :else                   (str id)))

(defn- berths-key-prefix [id k]
  (str "module-index[\"" (id-str id) "\"].berths[" k "]"))

(defn- deps-key-prefix [id k]
  (str "module-index[\"" (id-str id) "\"].deps[" k "]"))

(defn- collect-berth-errors [id berths]
  (cond
    (not (map? berths))
    [{:key   (str "module-index[\"" (id-str id) "\"].berths")
      :value "must be a map"}]

    :else
    ;; Foundational berths declared by isaac.core (e.g., :cli — the
    ;; well-known un-namespaced names that ship with the platform) are
    ;; permitted as un-namespaced keywords. Third-party modules must
    ;; namespace their berth ids so two modules can't accidentally
    ;; collide on a generic name.
    (let [core?     (= :isaac.core id)
          allowed?  (fn [k] (or (qualified-keyword? k)
                                 (and core? (keyword? k))))]
      (vec
        (mapcat
          (fn [[k v]]
            (cond-> []
              (not (allowed? k))
              (conj {:key   (berths-key-prefix id k)
                     :value "berth key must be a namespaced keyword"})

              (and (allowed? k)
                   (or (not (map? v))
                       (str/blank? (str (:description v)))))
              (conj {:key   (str (berths-key-prefix id k) ".description")
                     :value "must be present"})))
          berths)))))

(defn- collect-deps-errors [id deps]
  (cond
    (not (map? deps))
    [{:key   (str "module-index[\"" (id-str id) "\"].deps")
      :value "must be a map"}]

    :else
    (vec
      (keep
        (fn [[k v]]
          (when-not (map? v)
            {:key   (deps-key-prefix id k)
             :value "must be a coordinate map"}))
        deps))))

(defn- validate-berths-and-deps! [path manifest]
  (let [id     (:id manifest)
        errs   (concat (when (contains? manifest :berths)
                         (collect-berth-errors id (:berths manifest)))
                       (when (contains? manifest :deps)
                         (collect-deps-errors id (:deps manifest))))]
    (when (seq errs)
      (throw (ex-info "manifest shape errors"
                      {:isaac/manifest-errors (vec errs)
                       :path                  path})))))

(defn read-manifest
  [path fs*]
  (let [raw (edn/read-string (if (string? path) (fs/slurp fs* path) (slurp path)))]
     (when (contains? raw :entry)
       (throw (ex-info "entry is not supported; use :bootstrap"
                       {:field :entry :path path})))
     (when (contains? raw :extends)
       (throw (ex-info "use top-level kind keys instead of :extends"
                       {:field :extends :path path})))
     (when (contains? raw :requires)
       (throw (ex-info ":requires is no longer supported in v2 manifests"
                       {:field :requires :path path})))
     (doseq [[k v] raw]
       (cond
         (contains? known-keys k) nil
         ;; Namespaced top-level keys are berth contributions; the loader
         ;; matches them against berth declarations in the module-index
         ;; after all manifests are read. Don't flag them here as unknown.
         (qualified-keyword? k) nil
         (map? v) (throw (ex-info (str "unknown extension kind: " k)
                                  {:kind k :path path}))
         :else    (log/warn :manifest/unknown-key :key k :path path)))
     (let [conformed     (lexicon/conform! manifest-schema raw)
           ;; lexicon/conform! drops keys the schema doesn't name. Namespaced
           ;; top-level keys not in known-keys are berth contributions —
           ;; preserve them so the post-discovery contribution-validation
           ;; pass (isaac.module.loader/validate-contributions!) can find
           ;; them.
           contributions (into {} (filter (fn [[k _]]
                                            (and (qualified-keyword? k)
                                                 (not (contains? known-keys k))))
                                          raw))
           manifest      (merge conformed contributions)]
       (validate-bootstrap! path manifest)
       (validate-berths-and-deps! path manifest)
       (validate-v2-entries! path manifest)
       manifest)))
