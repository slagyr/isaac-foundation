;; mutation-tested: 2026-05-06
(ns isaac.config.entities
  "Entity-dir scanning, overlay machinery, frontmatter entities, schema finalize/merge."
  (:require
    [c3kit.apron.schema :as cs]
    [clojure.string :as str]
    [isaac.config.companions :as companions]
    [isaac.config.companion :as companion]
    [isaac.config.parse :as parse]
    [isaac.config.paths :as paths]
    [isaac.config.schema-base :as schema-base]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.config.validation :as validation]
    [isaac.config.warnings :as warnings]
    [isaac.schema.lexicon :as lexicon]))

(def ^:private ->id schema-base/->id)
(def ^:private present? companion/present?)

(defn- runtime-schema [spec]
  (schema-base/strip-validation-annotations spec))

(defn- cached-root-schema []
  (schema-compose/cached-root-schema))

(defn schema-for
  ([kind] (schema-for (cached-root-schema) kind))
  ([root-schema kind]
   (schema-compose/schema-for-kind root-schema kind)))

(defn- read-dir-files [root dir-name ext]
  (let [dir (str root "/" dir-name)]
    (->> (or (parse/children* dir) [])
         (filter #(parse/has-ext? % ext))
         sort
         (mapv (fn [name]
                 {:id       (subs name 0 (- (count name) (count ext)))
                  :path     (str dir "/" name)
                  :relative (str dir-name "/" name)})))))

(defn- read-entity-files [root dir-name] (read-dir-files root dir-name ".edn"))
(defn- read-md-files [root dir-name] (read-dir-files root dir-name ".md"))

(defn overlay-relative [{:keys [overlay-path]}]
  (when (present? overlay-path)
    overlay-path))

(defn overlay-for [opts relative]
  (when (= relative (overlay-relative opts))
    {:path     (str "<overlay>/" relative)
     :relative relative
     :content  (:overlay-content opts)
     :overlay? true}))

(defn- overlay-entry [dir-name ext {:keys [overlay-content] :as opts}]
  (when-let [relative (overlay-relative opts)]
    (when (and (str/starts-with? relative (str dir-name "/"))
               (parse/has-ext? relative ext))
      (let [name (last (str/split relative #"/"))]
        {:id       (subs name 0 (- (count name) (count ext)))
         :relative relative
         :content  overlay-content
         :overlay? true}))))

(defn- with-overlay [files overlay]
  (if overlay
    (conj (vec (remove #(= (:relative overlay) (:relative %)) files)) overlay)
    files))

(defn- frontmatter-md-entry? [entry]
  (boolean (parse/split-frontmatter (parse/entry-content entry))))

(defn config-files-present? [root opts]
  (or (overlay-relative opts)
      (parse/exists?* (str root "/" paths/root-filename))
      (some (fn [dir-name]
              (or (seq (read-entity-files root dir-name))
                  (seq (read-md-files root dir-name))))
            (schema-compose/entity-dir-names))))

(defn- merge-root-entity-with-schema [entity-schema result kind]
  (reduce (fn [acc [id entity]]
            (let [id       (->id id)
                  warns    (warnings/collect-unknown-key-warnings [] (name kind) id entity entity-schema)
                  entity   (lexicon/conform (runtime-schema entity-schema) entity)
                  explicit (:id entity)]
              (-> acc
                  (update :warnings into warns)
                  (cond-> (cs/error? entity)
                          (update :errors into (validation/schema-error-entries (str (name kind) "." id) entity)))
                  (cond-> (and explicit (not= explicit id))
                          (#(parse/assoc-error % (str (name kind) "." id ".id")
                                               (str "must match filename (got \"" explicit "\")"))))
                  (cond-> (not (cs/error? entity))
                          (assoc-in [:config kind id] (dissoc entity :id))))))
          result
          (get-in result [:root kind])))

(defn merge-root-entity
  ([result kind]
   (merge-root-entity-with-schema (schema-for kind) result kind))
  ([root-schema result kind]
   (merge-root-entity-with-schema (schema-for root-schema kind) result kind)))

(defn entity-files [root dir-name opts]
  (let [edn-files (-> (read-entity-files root dir-name)
                      (with-overlay (overlay-entry dir-name ".edn" opts)))
        md-files  (-> (read-md-files root dir-name)
                      (with-overlay (overlay-entry dir-name ".md" opts)))]
    (if (contains? (schema-compose/frontmatter-entity-dirs) dir-name)
      (let [md-files  (->> md-files
                           (filter frontmatter-md-entry?)
                           (mapv #(assoc % :format :md-frontmatter)))
            edn-files (mapv #(assoc % :format :edn) edn-files)
            md-by-id  (set (map :id md-files))]
        {:files    (vec (sort-by :relative (concat md-files (remove #(contains? md-by-id (:id %)) edn-files))))
         :warnings (mapv (fn [{:keys [id relative]}]
                           (parse/warning relative (str "single-file config overrides legacy " dir-name "/" id ".edn")))
                         (filter #(contains? (set (map :id edn-files)) (:id %)) md-files))})
      {:files    (vec (sort-by :relative (map #(assoc % :format :edn) edn-files)))
       :warnings []})))

(defn- read-entity-entry [entry substitute-env? raw-parse-errors?]
  (let [{:keys [content format overlay? path]} entry]
    (case format
      :md-frontmatter
      (parse/read-frontmatter-file entry substitute-env? raw-parse-errors?)

      (if overlay?
        (try
          {:data (parse/read-edn-string content substitute-env?)}
          (catch Exception e
            {:error (if raw-parse-errors? (.getMessage e) "EDN syntax error")}))
        (parse/read-edn-file path substitute-env? raw-parse-errors?)))))

(defn- resolve-entity-data [root kind id format raw-data body]
  (if-not (map? raw-data)
    {:data raw-data :error nil :extra-errors []}
    (let [{:keys [companion entity-dir]} (schema-compose/descriptor-for kind)
          load-md? (= format :md-frontmatter)
          load-fn  (fn [] {:exists? true :text body})]
      (case (:field companion)
        (:soul :ledger)
        (let [{resolved-data :data companion-error :error}
              (companions/resolve-inline-or-md-companion kind (:field companion) id raw-data
                                              (if load-md?
                                                load-fn
                                                #(companions/load-companion-text (str root "/"
                                                                           (companions/companion-md-relative kind id)))))]
          {:data resolved-data :error companion-error :extra-errors []})

        :prompt
        (if (= kind :hail)
          (let [{resolved-band :band prompt-errors :errors}
                (companions/resolve-hail-prompt id raw-data (if load-md?
                                                   load-fn
                                                   #(companions/load-companion-text (str root "/" entity-dir "/" id ".md"))))]
            {:data resolved-band :error nil :extra-errors prompt-errors})
          (let [relative (paths/cron-relative id)
                {resolved-job :job prompt-errors :errors}
                (companions/resolve-cron-prompt id raw-data (if load-md?
                                                   load-fn
                                                   #(companions/load-companion-text (str root "/" relative)))
                                     relative)]
            {:data resolved-job :error nil :extra-errors prompt-errors}))

        :template
        (let [relative (paths/hook-relative id)
              {resolved-hook :hook template-errors :errors}
              (companions/resolve-hook-template id raw-data (if load-md?
                                                   load-fn
                                                   #(companions/load-companion-text (str root "/" relative)))
                                     relative)]
          {:data resolved-hook :error nil :extra-errors template-errors})

        {:data raw-data :error nil :extra-errors []}))))

(defn- finalize-entity-load-with-schema [entity-schema result kind id relative data extra-errors]
  (let [root-entry   (or (get-in (:root result) [kind id])
                         (get-in (:root result) [kind (keyword id)]))
        warns       (warnings/collect-unknown-key-warnings [] (name kind) id data entity-schema)
        entity      (lexicon/conform (runtime-schema entity-schema) data)
        explicit-id (:id entity)
        result      (-> result
                        (update :warnings into warns)
                        (update :errors into extra-errors))
        result      (if (cs/error? entity)
                      (update result :errors into (validation/schema-error-entries (str (name kind) "." id) entity))
                      result)
        result      (if (and explicit-id (not= explicit-id id))
                      (parse/assoc-error result (str (name kind) "." id ".id") (str "must match filename (got \"" explicit-id "\")"))
                      result)
        result      (if (and (get-in (:config result) [kind id])
                             root-entry)
                      (parse/assoc-error result (str (name kind) "." id) (str "defined in both isaac.edn and " relative))
                      result)]
    (if (or (some? root-entry)
            (cs/error? entity))
      (update result :sources conj (parse/source-path relative))
      (-> result
          (assoc-in [:config kind id] (dissoc entity :id))
          (assoc-in [:raw kind id] (dissoc data :id))
          (update :sources conj (parse/source-path relative))))))

(defn finalize-entity-load
  ([result kind id relative data extra-errors]
   (finalize-entity-load-with-schema (schema-for kind) result kind id relative data extra-errors))
  ([root-schema result kind id relative data extra-errors]
   (finalize-entity-load-with-schema (schema-for root-schema kind) result kind id relative data extra-errors)))

(defn load-entity-file
  ([result root kind entry substitute-env? raw-parse-errors?]
   (let [{:keys [format id relative]} entry
         {raw-data :data error :error body :body} (read-entity-entry entry substitute-env? raw-parse-errors?)
         {data :data error :error extra-errors :extra-errors}
         (if error
           {:data raw-data :error error :extra-errors []}
           (resolve-entity-data root kind id format raw-data body))]
     (cond
       error
       (if (map? error)
         (update result :errors conj error)
         (parse/assoc-error result relative error))

       (not (map? data))
       (parse/assoc-error result relative "must contain a map")

       :else
       (finalize-entity-load result kind id relative data extra-errors))))
  ([root-schema result root kind {:keys [format id relative] :as entry} substitute-env? raw-parse-errors?]
   (let [{raw-data :data error :error body :body} (read-entity-entry entry substitute-env? raw-parse-errors?)
         {data :data error :error extra-errors :extra-errors}
         (if error
           {:data raw-data :error error :extra-errors []}
           (resolve-entity-data root kind id format raw-data body))]
     (cond
       error
       (if (map? error)
         (update result :errors conj error)
         (parse/assoc-error result relative error))

       (not (map? data))
       (parse/assoc-error result relative "must contain a map")

       :else
       (finalize-entity-load root-schema result kind id relative data extra-errors)))))

(defn- dangling-entry-kind [kind]
  (case kind
    :hooks "hook"
    :models "model"
    :providers "provider"
    (name kind)))

(defn dangling-md-warnings [root root-data opts]
  (let [root-data (or root-data {})
        inline-ids (fn [kind]
                     (let [ids (->> (keys (get root-data kind {})) (map ->id) set)]
                       (if (= kind :hooks)
                         (into ids (->> (keys (get root-data :hooks {}))
                                        (filter string?)
                                        (map ->id)))
                         ids)))
        file-ids   (fn [dir-name] (->> (entity-files root dir-name opts) :files (map :id) set))
        warn-for   (fn [kind dir-name]
                     (->> (read-md-files root dir-name)
                          (remove #(contains? (into (inline-ids kind) (file-ids dir-name)) (:id %)))
                          (mapv #(parse/warning (:relative %)
                                          (str "dangling: no matching " (dangling-entry-kind kind) " entry")))))]
    (vec (mapcat (fn [[kind {:keys [entity-dir]}]]
                   (when entity-dir
                     (warn-for kind entity-dir)))
                 (schema-compose/descriptors)))))
