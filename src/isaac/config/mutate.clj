(ns isaac.config.mutate
  "Domain mutations for Isaac configuration. Pure functions over
   the config filesystem; no CLI concerns, no stdout, no user-facing
   strings.

   Both set-config and unset-config return a result map of shape:

     {:status   :ok | :invalid | :invalid-path | :missing-path | :missing-entity-id
                | :not-found | :invalid-config
      :file     \"<relative-path>\"   ; file that changed (nil on failure)
      :errors   [{:key :value} ...]   ; structured validation errors
      :warnings [{:key :value} ...]}  ; structured warnings"
  (:require
     [c3kit.apron.schema.path :as path]
     [clojure.edn :as edn]
     [isaac.config.loader :as loader]
     [isaac.config.paths :as paths]
     [isaac.config.schema-compose :as schema-compose]
     [isaac.schema.lexicon :as lexicon]
     [isaac.fs :as fs]
     [isaac.nexus :as nexus]))

(defn- entity-sections []
  (set (map keyword (schema-compose/entity-dir-names))))
(def ^:private companion-inline-limit 64)

(def ^:private companion-md-specs
  {:crew   {:field :soul   :relative paths/soul-relative}
   :berths {:field :ledger :relative paths/ledger-relative}})

(defn- companion-spec [root-key]
  (companion-md-specs root-key))

(defn- companion-field? [root-key field-path]
  (when-let [{:keys [field]} (companion-spec root-key)]
    (and (= 1 (count field-path)) (= field (first field-path)))))

(defn- companion-md-relative [root-key entity-id]
  (when-let [{:keys [relative]} (companion-spec root-key)]
    (relative entity-id)))

(defn- runtime-fs []
  (or (fs/instance) (throw (ex-info "config.mutate requires :fs in system" {}))))

(defn- read-edn-path [path]
  (let [fs* (runtime-fs)]
    (when (fs/exists? fs* path)
      (edn/read-string (fs/slurp fs* path)))))

;; region ----- Data navigation -----

(defn- candidate-keys [segment]
  (cond
    (keyword? segment) [segment (name segment)]
    (string? segment)  [(keyword segment) segment]
    :else              [segment]))

(defn- existing-key [m segment]
  (some #(when (contains? m %) %) (candidate-keys segment)))

(defn- new-key [segment]
  (cond
    (keyword? segment) segment
    (string? segment)  (keyword segment)
    :else              segment))

(defn- path-present? [data segments]
  (if (empty? segments)
    true
    (and (map? data)
         (when-let [k (existing-key data (first segments))]
           (path-present? (get data k) (rest segments))))))

(defn- value-at-path [data segments]
  (if (empty? segments)
    data
    (when (map? data)
      (when-let [k (existing-key data (first segments))]
        (value-at-path (get data k) (rest segments))))))

(defn- assoc-path [data segments value]
  (if (empty? segments)
    value
    (let [data  (or data {})
          seg   (first segments)
          k     (or (existing-key data seg) (new-key seg))
          child (get data k)]
      (assoc data k (assoc-path child (rest segments) value)))))

(defn- dissoc-path [data segments]
  (if (and (map? data) (seq segments))
    (if-let [k (existing-key data (first segments))]
      (if-let [more (next segments)]
        (let [child   (dissoc-path (get data k) more)
              updated (if (nil? child) (dissoc data k) (assoc data k child))]
          (when (seq updated) updated))
        (let [updated (dissoc data k)]
          (when (seq updated) updated)))
      data)
    data))

;; endregion ^^^^^ Data navigation ^^^^^

;; region ----- Parse & state -----

(defn- parse-config-path [path-str]
  (let [segments (try (path/parse path-str)
                      (catch Exception _ ::invalid))]
    (cond
      (= ::invalid segments)
      {:status :invalid-path}

      (empty? segments)
      {:status :missing-path}

      (some #(= :index (first %)) segments)
      {:status :invalid-path}

      :else
      (let [segments   (mapv second segments)
            root-key   (first segments)
            entity?    (contains? (entity-sections) root-key)
            field-path (if entity? (subvec segments 2) (subvec segments 1))]
        (cond
          (and entity? (< (count segments) 2))
          {:status :missing-entity-id}

          :else
          {:entity-id     (when entity? (lexicon/->id (second segments)))
           :entity?       entity?
           :field-path    field-path
           :path          path-str
           :root-key      root-key
           :root-path     (if entity? [(first segments) (second segments)] [(first segments)])
           :segments      segments
           :companion?    (and entity? (companion-field? root-key field-path))
           :whole-entity? (and entity? (= 2 (count segments)))})))))

(defn- config-state [root parsed]
  (let [root-path              (paths/root-config-file root)
        root-data              (or (read-edn-path root-path) {})
        entity-relative        (when (:entity? parsed) (paths/entity-relative (:root-key parsed) (:entity-id parsed)))
        entity-path            (when entity-relative (paths/config-path root entity-relative))
        entity-data            (or (some-> entity-path read-edn-path) {})
        companion-field        (when (:companion? parsed) (:field (companion-spec (:root-key parsed))))
        companion-relative     (when (:companion? parsed) (companion-md-relative (:root-key parsed) (:entity-id parsed)))
        companion-path         (when companion-relative (paths/config-path root companion-relative))]
    {:companion-field       companion-field
     :companion-path        companion-path
     :companion-relative    companion-relative
     :entity-data           entity-data
     :entity-exists?        (boolean (and entity-path (fs/exists? (runtime-fs) entity-path)))
     :entity-path           entity-path
     :entity-relative       entity-relative
     :entity-root-exists?   (and (:entity? parsed) (path-present? root-data (:root-path parsed)))
     :inline-entity-companion? (and (:companion? parsed)
                                    (path-present? entity-data [companion-field]))
     :inline-root-companion?   (and (:companion? parsed)
                                    (path-present? root-data (:segments parsed)))
     :md-exists?            (boolean (and companion-path (fs/exists? (runtime-fs) companion-path)))
     :prefer-entity-files?  (true? (value-at-path root-data [:prefer-entity-files]))
     :root-data             root-data
     :root-path-exists?     (path-present? root-data (:segments parsed))
     :root-path             root-path}))

;; endregion ^^^^^ Parse & state ^^^^^

;; region ----- Plan & apply -----

(defn- update-edn-file [plan relative data]
  (if (nil? data)
    (-> plan
        (update :writes dissoc relative)
        (update :deletes conj relative))
    (-> plan
        (update :deletes disj relative)
        (assoc-in [:writes relative] (pr-str data)))))

(defn- update-text-file [plan relative content]
  (-> plan
      (update :deletes disj relative)
      (assoc-in [:writes relative] content)))

(defn- choose-set-location [parsed state]
  (cond
    (and (:companion? parsed) (:md-exists? state)) :md
    (and (:companion? parsed) (:inline-root-companion? state)) :root
    (and (:companion? parsed) (:inline-entity-companion? state)) :entity
    (and (:entity? parsed) (:entity-root-exists? state)) :root
    (and (:entity? parsed) (:entity-exists? state)) :entity
    (and (:entity? parsed) (:prefer-entity-files? state)) :entity
    :else :root))

(defn- choose-unset-location [parsed state]
  (cond
    (and (:companion? parsed) (:md-exists? state)) :md
    (:root-path-exists? state) :root
    (and (:entity? parsed)
         (or (and (:whole-entity? parsed) (:entity-exists? state))
             (path-present? (:entity-data state) (:field-path parsed)))) :entity
    :else nil))

(defn- use-companion-markdown? [parsed state location value]
  (and (:companion? parsed)
       (= :entity location)
       (not (:md-exists? state))
       (not (:inline-entity-companion? state))
       (string? value)
       (> (count value) companion-inline-limit)))

(defn- set-plan [parsed state value]
  (let [location (choose-set-location parsed state)]
    (cond
      (or (= :md location) (use-companion-markdown? parsed state location value))
      (let [field-key    (:companion-field state)
            entity-data' (when (:entity-exists? state)
                           (dissoc-path (:entity-data state) [field-key]))]
        (cond-> {:deletes #{} :file (:companion-relative state) :writes {}}
          true (update-text-file (:companion-relative state) value)
          (:entity-exists? state) (update-edn-file (:entity-relative state) entity-data')))

      (= :entity location)
      (let [entity-data' (if (:whole-entity? parsed)
                           value
                           (assoc-path (:entity-data state) (:field-path parsed) value))]
        (-> {:deletes #{} :file (:entity-relative state) :writes {}}
            (update-edn-file (:entity-relative state) entity-data')))

      :else
      (let [root-data' (assoc-path (:root-data state) (:segments parsed) value)]
        (-> {:deletes #{} :file paths/root-filename :writes {}}
            (update-edn-file paths/root-filename root-data'))))))

(defn- unset-plan [parsed state]
  (when-let [location (choose-unset-location parsed state)]
    (case location
      :md
      {:deletes #{(:companion-relative state)} :file (:companion-relative state) :writes {}}

      :entity
      (let [entity-data' (if (:whole-entity? parsed)
                           nil
                           (dissoc-path (:entity-data state) (:field-path parsed)))]
        (-> {:deletes #{} :file (:entity-relative state) :writes {}}
            (update-edn-file (:entity-relative state) entity-data')))

      :root
      (let [root-data' (dissoc-path (:root-data state) (:segments parsed))]
        (-> {:deletes #{} :file paths/root-filename :writes {}}
            (update-edn-file paths/root-filename root-data'))))))

(defn- apply-plan! [root plan]
  (let [fs* (runtime-fs)]
    (doseq [relative (:deletes plan)]
      (let [path (paths/config-path root relative)]
        (when (fs/exists? fs* path)
          (fs/delete fs* path))))
    (doseq [[relative content] (:writes plan)]
      (let [path   (paths/config-path root relative)
            parent (fs/parent path)]
        (when parent
          (fs/mkdirs fs* parent))
        (fs/spit fs* path content)))))

(defn- read-edn-on-fs [fs* path]
  (when (fs/exists? fs* path)
    (edn/read-string (fs/slurp fs* path))))

(defn- copy-declared-local-modules! [source-fs stage-fs root]
  (when-let [config (read-edn-on-fs stage-fs (paths/root-config-file root))]
    (doseq [[_ coord] (:modules config)
            :let [local-root (:local/root coord)]
            :when (and (string? local-root) (fs/dir? source-fs local-root))]
      (fs/copy-tree! source-fs stage-fs local-root))))

(defn- validate-plan [root plan]
  (let [source-fs (or (:fs (nexus/necho))
                      (fs/mem-fs))
        stage-fs  (fs/mem-fs)
        config-root (paths/config-root root)]
    (fs/copy-tree! source-fs stage-fs config-root)
    (nexus/-with-nested-nexus {:fs stage-fs}
      (apply-plan! root plan)
      (copy-declared-local-modules! source-fs stage-fs root)
      (loader/load-config-result {:root root :fs stage-fs}))))

;; endregion ^^^^^ Plan & apply ^^^^^

;; region ----- Public API -----

(defn- error-signature [e]
  [(:key e) (:value e)])

(defn- partition-errors
  "Split `post-errors` into [new-errors pre-existing-errors] given the
   `pre-errors` set. An error is pre-existing if a matching :key+:value
   pair already appears in pre-errors."
  [pre-errors post-errors]
  (let [pre-set (set (map error-signature pre-errors))]
    [(remove (fn [e] (contains? pre-set (error-signature e))) post-errors)
     (filter (fn [e] (contains? pre-set (error-signature e))) post-errors)]))

(defn- reference-error?
  "True for errors produced by existence-ref validators (model-exists?,
   crew-exists?, etc.). These carry :bad-value; type errors do not."
  [e]
  (contains? e :bad-value))


(defn- pre-existing->warnings
  "Format pre-existing errors as warnings so the user sees them without
   the mutation being blocked."
  [errors]
  (mapv (fn [e] (-> e (assoc :value (str "pre-existing: " (:value e))))) errors))

(defn set-config
  "Writes `value` at dotted `path` under `root`. See ns docstring for
   return shape.

   Pre-existing config errors do not block the mutation — they're
   surfaced as warnings and the change still applies, as long as the
   change itself doesn't introduce *new* validation errors.

   When `skip-ref-validation?` is true, reference errors (model-exists?,
   crew-exists?, etc.) are never treated as new errors — only type errors
   can block the mutation. Use this from the CLI so operators can wire up
   values that reference entities not yet defined."
  [root path value & {:keys [skip-ref-validation?] :or {skip-ref-validation? false}}]
  (let [parsed (parse-config-path path)]
    (cond
      (:status parsed)
      {:status   (:status parsed)
       :file     nil
       :errors   []
       :warnings []}

       :else
       (let [current        (loader/load-config-result {:root root})
             pre-errors     (or (:errors current) [])
             state          (config-state root parsed)
             plan           (set-plan parsed state value)
             result         (validate-plan root plan)
             [new-errors carried-errors] (partition-errors pre-errors (:errors result))
             new-errors     (if skip-ref-validation?
                              (remove reference-error? new-errors)
                              new-errors)
             warnings       (concat (:warnings result)
                                    (pre-existing->warnings carried-errors))]
         (if (seq new-errors)
           {:status :invalid :file nil :errors new-errors :warnings warnings}
          (do
            (apply-plan! root plan)
            {:status :ok :file (:file plan) :errors [] :warnings warnings}))))))

(defn unset-config
  "Removes dotted `path` under `root`. See ns docstring for return shape.

   Pre-existing config errors do not block the unset; they're surfaced
   as warnings."
  [root path]
  (let [parsed (parse-config-path path)]
    (cond
      (:status parsed)
      {:status (:status parsed) :file nil :errors [] :warnings []}

      :else
      (let [current    (loader/load-config-result {:root root})
            pre-errors (or (:errors current) [])
            state      (config-state root parsed)
            plan       (unset-plan parsed state)]
        (cond
          (nil? plan)
          {:status :ok :file nil :errors [] :warnings []}

          :else
          (let [result   (validate-plan root plan)
                [new-errors carried-errors] (partition-errors pre-errors (:errors result))
                warnings (concat (:warnings result)
                                 (pre-existing->warnings carried-errors))]
            (if (seq new-errors)
              {:status :invalid :file nil :errors new-errors :warnings warnings}
              (do
                (apply-plan! root plan)
                {:status :ok :file (:file plan) :errors [] :warnings warnings}))))))))

;; endregion ^^^^^ Public API ^^^^^
