(ns isaac.config.mutate
  "Domain mutations for Isaac configuration. Pure functions over
   the config filesystem; no CLI concerns, no stdout, no user-facing
   strings.

   Both set-config and unset-config return a result map of shape:

     {:status   :ok | :invalid | :invalid-path | :missing-path | :missing-entity-id
                | :not-found | :invalid-config
      :file     \"<relative-path>\"   ; file that changed (nil on failure)
      :errors   [{:key :value} ...]   ; structured validation errors
      :warnings [{:key :value} ...]}  ; structured warnings

   set-many! applies several {:op :set|:unset :path :value} operations as one
   atomic, all-or-nothing write (isaac-cvri); see its docstring for its
   :files-plural result shape."
  (:require
     [clj-yaml.core :as yaml]
     [clojure.edn :as edn]
     [clojure.set :as set]
     [clojure.string :as str]
     [isaac.cli.host :as host]
     [isaac.config.env :as env]
     [isaac.config.loader :as loader]
     [isaac.config.nav :as nav]
     [isaac.config.paths :as paths]
     [isaac.config.parse :as parse]
     [isaac.config.schema-compose :as schema-compose]
     [isaac.config.tree :as tree]
     [isaac.config.schema.resolve :as schema-resolve]
     [isaac.schema.lexicon :as lexicon]
     [isaac.fs :as fs]
     [isaac.nexus :as nexus]
     [isaac.util.edn :as edn-pretty]))

(defn- entity-sections
  "Top-level keys whose entries live one-per-file in `config/<key>/`. A key is a
   directory because a directory exists, not because a module declared
   `:entity-dir` — foundation names no kind (isaac-49zp)."
  [root]
  (into (set (map keyword (schema-compose/entity-dir-names)))
        (keys (:dirs (tree/scan (paths/config-root root))))))

(def ^:private companion-inline-limit 64)

(defn- companion-spec
  "The one entity field a `.md` companion carries for `root-key`, from the
   owning module's descriptor — foundation does not know that crews have souls."
  [root-key]
  (when-let [{:keys [companion entity-dir]} (schema-compose/descriptor-for root-key)]
    (when (and (:field companion) entity-dir)
      {:field    (:field companion)
       :relative (fn [id] (str entity-dir "/" id ".md"))})))

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

(defn- parse-config-path [root path-str]
  (let [segments (try (paths/parse-path-segments path-str)
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
            entity?    (contains? (entity-sections root) root-key)
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
        entity-exists?         (boolean (and entity-path (fs/exists? (runtime-fs) entity-path)))
        entity-root-exists?    (and (:entity? parsed) (path-present? root-data (:root-path parsed)))
        md-relative            (when entity-relative (str/replace entity-relative #"\.edn$" ".md"))
        md-path                (when md-relative (paths/config-path root md-relative))
        md-content             (when (and md-path (fs/exists? (runtime-fs) md-path))
                                 (fs/slurp (runtime-fs) md-path))
        md-frontmatter         (when md-content (parse/split-frontmatter md-content))
        companion-field        (when (:companion? parsed) (:field (companion-spec (:root-key parsed))))
        companion-relative     (when (:companion? parsed) (companion-md-relative (:root-key parsed) (:entity-id parsed)))
        companion-path         (when companion-relative (paths/config-path root companion-relative))
        slice-relative         (paths/slice-relative (:root-key parsed))
        slice-path             (paths/config-path root slice-relative)
        slice-data             (or (read-edn-path slice-path) {})]
    {:frontmatter-relative  (when md-frontmatter md-relative)
     :frontmatter-content   md-content
     :frontmatter-data      (when md-frontmatter (yaml/parse-string (:frontmatter md-frontmatter) :keywords true))
     :companion-field       companion-field
     :companion-path        companion-path
     :companion-relative    companion-relative
     :entity-data           entity-data
     :entity-exists?        entity-exists?
     :entity-path           entity-path
     :entity-relative       entity-relative
     :entity-root-exists?   entity-root-exists?
     :inline-entity-companion? (and (:companion? parsed)
                                    (path-present? entity-data [companion-field]))
     :inline-root-companion?   (and (:companion? parsed)
                                    (path-present? root-data (:segments parsed)))
     :md-exists?            (boolean (and companion-path (fs/exists? (runtime-fs) companion-path)))
     :prefer-entity-files?  (true? (value-at-path root-data [:prefer-entity-files]))
     :root-data             root-data
     ;; the top-level key itself, not the full path: a key that already lives
     ;; inline keeps its home even when the field being written is new
     :root-key-inline?      (path-present? root-data [(:root-key parsed)])
     :root-path-exists?     (path-present? root-data (:segments parsed))
     :root-path             root-path
     ;; config/<key>.edn — the whole value of one top-level key in its own file
     :slice-data            slice-data
     :slice-exists?         (boolean (fs/exists? (runtime-fs) slice-path))
     :slice-path            slice-path
     :slice-path-exists?    (path-present? slice-data (rest (:segments parsed)))
     :slice-relative        slice-relative}))

;; endregion ^^^^^ Parse & state ^^^^^

;; region ----- Plan & apply -----

(defn- update-edn-file [plan relative data]
  (if (nil? data)
    (-> plan
        (update :writes dissoc relative)
        (update :deletes conj relative))
    (-> plan
        (update :deletes disj relative)
        (assoc-in [:writes relative] (str (edn-pretty/pretty data) "\n")))))

(defn- update-text-file [plan relative content]
  (-> plan
      (update :deletes disj relative)
      (assoc-in [:writes relative] content)))

(defn- choose-set-location
  "Where a written value lands, in precedence order: (1) whichever form
   already holds the key wins — an existing entry is never moved; (2) a
   brand-new entity becomes its own entity file when `:prefer-entity-files`
   is true; (3) else `isaac.edn`. Extended to the `config/<key>.edn` slice
   (isaac-49zp) — the slice is checked before the entity forms: a key stored
   as one file cannot also be a directory, so routing a write into
   `<key>/<id>.edn` would manufacture the very conflict the loader refuses."
  [parsed state]
  (cond
    (and (:companion? parsed) (:md-exists? state)) :md
    (and (:companion? parsed) (:inline-root-companion? state)) :root
    (and (:companion? parsed) (:inline-entity-companion? state)) :entity
    (:slice-exists? state) :slice
    (and (:entity? parsed) (:frontmatter-relative state)) :frontmatter
    (and (:entity? parsed) (:entity-root-exists? state)) :root
    (and (:entity? parsed) (:entity-exists? state)) :entity       ; rule 1 (existing entry stays where it lives)
    (and (:entity? parsed) (:prefer-entity-files? state)) :entity ; rule 2 (new entry, preference)
    (and (:prefer-entity-files? state) (not (:root-key-inline? state))) :slice
    :else :root))                                                 ; rule 3 (new entry, no preference)

(defn- choose-unset-location [parsed state]
  (cond
    (and (:companion? parsed) (:md-exists? state)) :md
    (and (:entity? parsed) (:frontmatter-relative state)
         (or (:whole-entity? parsed)
             (path-present? (:frontmatter-data state) (:field-path parsed)))) :frontmatter
    (:root-path-exists? state) :root
    (and (:entity? parsed)
         (or (and (:whole-entity? parsed) (:entity-exists? state))
             (path-present? (:entity-data state) (:field-path parsed)))) :entity
    (and (:slice-exists? state) (:slice-path-exists? state)) :slice
    :else nil))

(defn- use-companion-markdown? [parsed state location value]
  (and (:companion? parsed)
       (= :entity location)
       (not (:md-exists? state))
       (not (:inline-entity-companion? state))
       (string? value)
       (> (count value) companion-inline-limit)))

(defn- whole-entity-companion-field
  "The companion field name when `parsed` is a whole-entity write and
   `value` is a map carrying that field as a string — the shape a new
   entity file placed by rule 1/2 (isaac-cvri) needs split out to its
   companion .md, same as a per-field write already does. nil otherwise
   (no companion kind, no whole-entity write, or the field isn't in
   `value` as a string), meaning the whole map is written as-is."
  [parsed value]
  (when (:whole-entity? parsed)
    (when-let [field (:field (companion-spec (:root-key parsed)))]
      (when (and (map? value) (string? (get value field)))
        field))))

(defn- update-frontmatter [plan state data]
  ;; Keep the delimiters and *all* bytes following the closing delimiter.
  ;; SnakeYAML returns an ordered map, so replacing a field does not reorder
  ;; unrelated YAML keys. A blank line is valid empty frontmatter.
  (let [[_ open _ close body] (re-matches #"(?s)\A(---\r?\n)(.*?)(\r?\n---\r?\n?)(.*)\z"
                                           (:frontmatter-content state))
        yaml-text (if (seq data)
                    (str/trimr (yaml/generate-string data {:dumper-options {:flow-style :block}}))
                    "")
        content (str open yaml-text close body)]
    (update-text-file plan (:frontmatter-relative state) content)))

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

      (= :frontmatter location)
      (let [data (if (:whole-entity? parsed)
                   value
                   (assoc-path (:frontmatter-data state) (:field-path parsed) value))]
        (update-frontmatter {:deletes #{} :file (:frontmatter-relative state) :writes {}} state data))

      (= :entity location)
      (if-let [field (whole-entity-companion-field parsed value)]
        (let [md-relative (companion-md-relative (:root-key parsed) (:entity-id parsed))]
          (-> {:deletes #{} :file md-relative :writes {}}
              (update-text-file md-relative (get value field))
              (update-edn-file (:entity-relative state) (dissoc value field))))
        (let [entity-data' (if (:whole-entity? parsed)
                             value
                             (assoc-path (:entity-data state) (:field-path parsed) value))]
          (-> {:deletes #{} :file (:entity-relative state) :writes {}}
              (update-edn-file (:entity-relative state) entity-data'))))

      (= :slice location)
      (let [slice-data' (assoc-path (:slice-data state) (rest (:segments parsed)) value)]
        (-> {:deletes #{} :file (:slice-relative state) :writes {}}
            (update-edn-file (:slice-relative state) slice-data')))

      :else
      (let [root-data' (assoc-path (:root-data state) (:segments parsed) value)]
        (-> {:deletes #{} :file paths/root-filename :writes {}}
            (update-edn-file paths/root-filename root-data'))))))

(defn- unset-plan [parsed state]
  (when-let [location (choose-unset-location parsed state)]
    (case location
      :md
      {:deletes #{(:companion-relative state)} :file (:companion-relative state) :writes {}}

      :frontmatter
      (let [data (if (:whole-entity? parsed)
                   nil
                   (dissoc-path (:frontmatter-data state) (:field-path parsed)))]
        (update-frontmatter {:deletes #{} :file (:frontmatter-relative state) :writes {}} state data))

      :entity
      (let [entity-data' (if (:whole-entity? parsed)
                           nil
                           (dissoc-path (:entity-data state) (:field-path parsed)))]
        (-> {:deletes #{} :file (:entity-relative state) :writes {}}
            (update-edn-file (:entity-relative state) entity-data')))

      :slice
      (let [field-path  (vec (rest (:segments parsed)))
            slice-data' (when (seq field-path)
                          (dissoc-path (:slice-data state) field-path))]
        (-> {:deletes #{} :file (:slice-relative state) :writes {}}
            (update-edn-file (:slice-relative state) slice-data')))

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

(defn- absolute-local-root [local-root]
  (if (or (str/starts-with? local-root "/")
          (re-matches #"[A-Za-z]:.*" local-root))
    local-root
    (str (host/cwd) "/" local-root)))

(defn- copy-declared-local-modules! [source-fs stage-fs root]
  (when-let [config (read-edn-on-fs stage-fs (paths/root-config-file root))]
    (doseq [[_ coord] (:modules config)
            :let [declared   (:local/root coord)
                  local-root (when (string? declared) (absolute-local-root declared))]
            :when (and local-root (fs/dir? source-fs local-root))]
      (fs/copy-tree! source-fs stage-fs local-root))))

(defn- validate-plan [root plan]
  (let [source-fs   (or (:fs (nexus/necho))
                        (fs/mem-fs))
        stage-fs    (fs/mem-fs)
        config-root (paths/config-root root)
        ;; Reuse the process's already-locked <root>/.env snapshot rather than
        ;; have the staged load re-lock against stage-fs, which never receives
        ;; a copy of .env below — every ${VAR} reference would otherwise read
        ;; as unset and trip conditional-required fields that are genuinely
        ;; satisfied on the live root (isaac-p4oj).
        dotenv      (env/dotenv-snapshot)]
    (fs/copy-tree! source-fs stage-fs config-root)
    (nexus/-with-nested-nexus {:fs stage-fs}
      (apply-plan! root plan)
      (copy-declared-local-modules! source-fs stage-fs root)
      (loader/load-config-result {:root root :fs stage-fs :dotenv dotenv}))))

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
  "True for errors produced by existence-ref validators — foundation's own
   (:berth-exists?, :gauge-exists?) plus any module-contributed ref (e.g.
   isaac-agent's entity-reference checks). Value-validator errors carry
   :bad-value too; only
   entries tagged :reference? — or check contributions that reuse the
   existence-ref message — are skipped under skip-ref-validation?."
  [e]
  (or (true? (:reference? e))
      (and (string? (:value e))
           (str/starts-with? (:value e) "references undefined "))))

(defn- coercion-error?
  "True for errors raised while coercing the written value to its declared
   type. `force?` never bypasses these: a value that cannot become the
   declared type is a typo, not a policy decision."
  [e]
  (and (string? (:value e))
       (str/starts-with? (:value e) "can't coerce")))

(defn- blocking-errors
  "Errors that stop the mutation. Without `force?` every new error blocks;
   with it only coercion errors do."
  [force? errors]
  (if force? (filter coercion-error? errors) errors))

(defn- module-discovery-error? [e]
  (and (string? (:key e))
       (str/starts-with? (:key e) "modules[")))

(defn- unresolved-reference-paths
  "The field paths whose `${...}` reference this shell cannot resolve. Such a
   field reads as unset, so validation raises the ordinary required-field error
   on it — but writing config must never refuse over that: the writer's
   environment is not the server's, so a variable missing here proves nothing,
   and a file reference may be written before the file exists (isaac-rxun).
   The warning still fires; only the refusal is dropped."
  [load-result]
  (set (keys (get-in load-result [:config :unresolved-refs]))))

(defn- unresolved-reference-error? [unresolved-paths e]
  (contains? unresolved-paths (:key e)))


(defn- pre-existing->warnings
  "Format pre-existing errors as warnings so the user sees them without
   the mutation being blocked."
  [errors]
  (mapv (fn [e] (-> e (assoc :value (str "pre-existing: " (:value e))))) errors))

(defn- root-schema-from [current]
  (schema-resolve/root-schema-for (:config current) current))

(defn- undeclared-key-message [{:keys [parent-path known-keys segment]}]
  (str "unknown key " (pr-str segment) " — "
       (if (str/blank? parent-path) "the config root" parent-path)
       " knows: " (str/join ", " known-keys)))

(defn- undeclared-key-refusal
  "When `path` names a segment a STATIC schema'd map (not an open
   :key-spec/:value-spec entity table) does not declare, refuse the
   mutation in the same shape as a fun8 validator error — nothing is
   written, and the message names the parent path plus the keys that
   level knows. Open entity tables (crews, models, berths, relays, …)
   accept any key at the id level, unchanged (nav/path->spec never fails
   there). `force?` skips this check entirely — the caller writes and the
   nq4c load-time warning still fires from the post-write reload."
  [force? root-schema path]
  (when-not force?
    (let [result (nav/path->spec root-schema path)]
      (when (and (not (:ok? result)) (:parent-path result))
        {:key path :value (undeclared-key-message result)}))))

(defn set-config
  "Writes `value` at dotted `path` under `root`. See ns docstring for
   return shape.

   Pre-existing config errors do not block the mutation — they're
   surfaced as warnings and the change still applies, as long as the
   change itself doesn't introduce *new* validation errors.

   When `skip-ref-validation?` is true, reference errors (existence-ref
   validators, foundation's own or module-contributed) are never treated as
   new errors — only type errors can block the mutation. Use this from the
   CLI so operators can wire up values that reference entities not yet
   defined."
  [root path value & {:keys [skip-ref-validation? skip-module-validation? force?]
                      :or   {skip-ref-validation? false
                             skip-module-validation? false
                             force? false}}]
  (let [parsed (parse-config-path root path)]
    (cond
      (:status parsed)
      {:status   (:status parsed)
       :file     nil
       :errors   []
       :warnings []}

       :else
       (let [current        (loader/load-config-result {:root root :skip-cache? true})
             refusal        (undeclared-key-refusal force? (root-schema-from current) path)]
         (if refusal
           {:status :invalid :file nil :errors [refusal] :warnings []}
           (let [pre-errors     (or (:errors current) [])
                 state          (config-state root parsed)
                 plan           (set-plan parsed state value)
                 result         (validate-plan root plan)
                 [new-errors carried-errors] (partition-errors pre-errors (:errors result))
                 unresolved     (unresolved-reference-paths result)
                 new-errors     (as-> new-errors $
                                  (if skip-ref-validation?
                                    (vec (remove reference-error? $))
                                    $)
                                  (if skip-module-validation?
                                    (vec (remove module-discovery-error? $))
                                    $)
                                  (vec (remove #(contains? unresolved (:key %)) $)))
                 warnings       (concat (:warnings result)
                                        (pre-existing->warnings carried-errors))]
             (if (seq (blocking-errors force? new-errors))
               {:status :invalid :file nil :errors new-errors :warnings warnings}
               (do
                 (apply-plan! root plan)
                 {:status :ok :file (:file plan) :errors []
                  :warnings (if force?
                              (concat warnings new-errors)
                              warnings)}))))))))

(defn unset-config
  "Removes dotted `path` under `root`. See ns docstring for return shape.

   Pre-existing config errors do not block the unset; they're surfaced
   as warnings."
  [root path & {:keys [skip-module-validation? force?] :or {skip-module-validation? false force? false}}]
  (let [parsed (parse-config-path root path)]
    (cond
      (:status parsed)
      {:status (:status parsed) :file nil :errors [] :warnings []}

      :else
      (let [current (loader/load-config-result {:root root :skip-cache? true})
            refusal (undeclared-key-refusal force? (root-schema-from current) path)]
        (if refusal
          {:status :invalid :file nil :errors [refusal] :warnings []}
          (let [pre-errors (or (:errors current) [])
                state      (config-state root parsed)
                plan       (unset-plan parsed state)]
            (cond
              (nil? plan)
              {:status :ok :file nil :errors [] :warnings []}

              :else
              (let [result   (validate-plan root plan)
                    [new-errors carried-errors] (partition-errors pre-errors (:errors result))
                    unresolved (unresolved-reference-paths result)
                    new-errors (cond->> new-errors
                                 skip-module-validation? (remove module-discovery-error?)
                                 :always                 (remove (partial unresolved-reference-error? unresolved))
                                 :always                 vec)
                    warnings (concat (:warnings result)
                                     (pre-existing->warnings carried-errors))]
                (if (seq (blocking-errors force? new-errors))
                  {:status :invalid :file nil :errors new-errors :warnings warnings}
                  (do
                    (apply-plan! root plan)
                    {:status :ok :file (:file plan) :errors []
                     :warnings (if force?
                                 (concat warnings new-errors)
                                 warnings)}))))))))))

(defn- merge-op-plan
  "Folds one op's `{:writes :deletes}` plan into the running `combined` plan:
   writes merge left-to-right (a later op's write to the same relative file
   wins), deletes union — minus anything a later op re-writes."
  [combined op-plan]
  (let [writes'  (-> (:writes combined)
                     (as-> w (apply dissoc w (:deletes op-plan)))
                     (merge (:writes op-plan)))
        deletes' (-> (:deletes combined)
                    (into (:deletes op-plan))
                    (set/difference (set (keys (:writes op-plan)))))]
    (assoc combined :writes writes' :deletes deletes')))

(defn- combined-op-plan
  "Builds the merged plan for `parsed-ops` by applying each op's plan, in
   order, onto a throwaway scratch copy of the config tree — so a later op's
   `set-plan`/`unset-plan` sees the earlier ops' effects (two ops touching the
   same file, an unset that empties a directory before a later placement
   decision, ...) rather than the original on-disk state. The scratch copy is
   discarded; only the combined `{:writes :deletes}` plan survives."
  [root parsed-ops]
  (let [source-fs   (or (:fs (nexus/necho)) (fs/mem-fs))
        scratch-fs  (fs/mem-fs)
        config-root (paths/config-root root)]
    (fs/copy-tree! source-fs scratch-fs config-root)
    (nexus/-with-nested-nexus {:fs scratch-fs}
      (reduce (fn [combined {:keys [op parsed value]}]
                (let [state   (config-state root parsed)
                      op-plan (case op
                                :set   (set-plan parsed state value)
                                :unset (unset-plan parsed state))]
                  (if (nil? op-plan)
                    combined
                    (do
                      (apply-plan! root op-plan)
                      (merge-op-plan combined op-plan)))))
              {:deletes #{} :writes {}}
              parsed-ops))))

(defn- plan-touched-files [plan]
  (vec (sort (into (set (keys (:writes plan))) (:deletes plan)))))

(defn set-many!
  "Applies `ops` — `[{:op :set :path \"...\" :value ...} {:op :unset :path
   \"...\"} ...]` — as ONE atomic write: every path is parsed first (a parse
   failure on any op refuses the whole batch untouched), each op's plan is
   built with the same `set-plan`/`unset-plan`/`choose-set-location` rules
   `set-config`/`unset-config` use, the plans are merged into one, staged, and validated ONCE
   against the resulting config (`validate-plan`, same pre-existing-error /
   new-error semantics as `set-config`). A blocking new error refuses the
   whole batch — nothing is written, not even the individually-valid ops.
   No `--force`: the caller never bypasses validation. Reference errors
   (model-exists?, gauge-exists?, etc.) never block, same as the CLI's own
   `set-config`/`unset-config` calls (`:skip-ref-validation? true`) — a batch
   exists to wire up several mutually-referencing entities together, so a
   reference to an id defined elsewhere (or not yet at all) is not this
   caller's error to refuse over.

   Returns {:status :ok | :invalid | :invalid-path | :missing-path
                    | :missing-entity-id
            :files   [\"<relative-path>\" ...]   ; plural — a batch can touch several
            :errors   [{:key :value} ...]
            :warnings [{:key :value} ...]}"
  [root ops]
  (let [parsed-ops (mapv (fn [op] (assoc op :parsed (parse-config-path root (:path op)))) ops)]
    (if-let [parse-failure (some #(:status (:parsed %)) parsed-ops)]
      {:status parse-failure :files [] :errors [] :warnings []}
      (let [current     (loader/load-config-result {:root root :skip-cache? true})
            root-schema (root-schema-from current)
            refusals    (vec (keep #(undeclared-key-refusal false root-schema (:path (:parsed %))) parsed-ops))]
        (if (seq refusals)
          {:status :invalid :files [] :errors refusals :warnings []}
          (let [pre-errors  (or (:errors current) [])
                plan        (combined-op-plan root parsed-ops)
                result      (validate-plan root plan)
                [new-errors carried-errors] (partition-errors pre-errors (:errors result))
                unresolved  (unresolved-reference-paths result)
                new-errors  (as-> new-errors $
                              (vec (remove reference-error? $))
                              (vec (remove #(contains? unresolved (:key %)) $)))
                warnings    (concat (:warnings result) (pre-existing->warnings carried-errors))]
            (if (seq new-errors)
              {:status :invalid :files [] :errors new-errors :warnings warnings}
              (do
                (apply-plan! root plan)
                {:status :ok :files (plan-touched-files plan) :errors [] :warnings warnings}))))))))

;; endregion ^^^^^ Public API ^^^^^
