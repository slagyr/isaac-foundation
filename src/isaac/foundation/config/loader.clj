;; mutation-tested: 2026-05-06
(ns isaac.foundation.config.loader
  "Config load orchestration: root read/validate, entity merge, berth slices, snapshot.

   Production logic for env/parse/companions/entities/normalize/warnings lives in
   those namespaces. This ns owns load orchestration + ambient snapshot + workspace.
   Vars below forward former public surface so published agent/server/hail modules
   that still require isaac.foundation.config.loader keep loading until they cut over
   (isaac-a7c0). Foundation-internal callers require the owning namespace directly."
  (:require
    [c3kit.apron.schema :as cs]
    [clojure.string :as str]
    [isaac.foundation.cli.host :as host]
    [isaac.foundation.config.berths :as berths]
    [isaac.foundation.config.check-compose :as check-compose]
    [isaac.foundation.config.companions :as companions]
    [isaac.foundation.config.entities :as entities]
    [isaac.foundation.config.env :as env]
    [isaac.foundation.config.normalize :as normalize]
    [isaac.foundation.config.parse :as parse]
    [isaac.foundation.config.paths :as paths]
    [isaac.foundation.config.schema-base :as schema-base]
    [isaac.foundation.config.schema-compose :as schema-compose]
    [isaac.foundation.config.templating :as templating]
    [isaac.foundation.config.tree :as tree]
    [isaac.foundation.config.validation :as validation]
    [isaac.foundation.config.warnings :as warnings]
    [isaac.foundation.logger :as log]
    [isaac.foundation.module.discovery :as discovery]
    [isaac.foundation.module.lifecycle :as lifecycle]
    [isaac.foundation.nexus :as nexus]
    [isaac.foundation.schema.lexicon :as lexicon]))

;; Temporary public re-exports of the former loader surface (isaac-flgy / a7c0).
;; External modules (agent/server/hail/…) still call these via isaac.foundation.config.loader.
(def env-overrides* env/env-overrides*)
(def clear-env-overrides! env/clear-env-overrides!)
(def set-env-override! env/set-env-override!)
(def env env/env)
(def normalize-config normalize/normalize-config)

(defn- runtime-schema [spec]
  (schema-base/strip-validation-annotations spec))

(defn- cached-root-schema []
  (schema-compose/cached-root-schema))

(defn- schema-for
  ([kind] (schema-for (cached-root-schema) kind))
  ([root-schema kind]
   (schema-compose/schema-for-kind root-schema kind)))

(defn- read-root-config [root {:keys [raw-parse-errors? substitute-env?] :as opts}]
  (let [overlay (entities/overlay-for opts paths/root-filename)
        path    (str root "/" paths/root-filename)]
    (cond
      overlay
      (let [{:keys [content relative]} overlay]
        (try
          (let [raw-data             (parse/read-edn-string content substitute-env?)
                {:keys [cron errors]} (companions/resolve-cron-prompts root raw-data)
                data                 (cond-> raw-data
                                             (:cron raw-data) (assoc :cron cron))]
            {:data     data
             :errors   (vec errors)
             :warnings []
             :sources  [(parse/source-path relative)]})
          (catch Exception _
            {:data nil :errors [{:key paths/root-filename :value "EDN syntax error"}] :warnings [] :sources []})))

      (parse/exists?* path)
      (let [{raw-data :data error :error} (parse/read-edn-file path substitute-env? raw-parse-errors?)]
        (if error
          {:data nil :errors [{:key paths/root-filename :value error}] :warnings [] :sources []}
          (let [{:keys [cron errors]} (companions/resolve-cron-prompts root raw-data)
                data                  (cond-> raw-data
                                              (:cron raw-data) (assoc :cron cron))]
            {:data     data
             :errors   (vec errors)
             :warnings []
             :sources  [(parse/source-path paths/root-filename)]})))

      :else
      {:data nil :errors [] :warnings [] :sources []})))

(defn- fill-absent-root-sections
  "Each top-level schema field whose section is wholly absent from `data`
   still conforms as {}, so a module's nested :default values fill before
   the section is ever written (isaac-zmub). A present section is untouched
   here — the ordinary conform above already enforces its required fields."
  [root-schema data root-result]
  (reduce (fn [acc [field-key field-spec]]
            (if (contains? data field-key)
              acc
              (if-let [filled (schema-base/conform-absent-section field-spec)]
                (assoc acc field-key filled)
                acc)))
          root-result
          (dissoc (schema-base/schema-fields root-schema) :*)))

(defn -validate-root-config
  "Private intent, but feature spies (isaac-v1la) wrap this var.

   Loaded config is a conformed-over-raw overlay (isaac-dnib): the returned
   `:data` is `data` with the conform result overlaid on top field-by-field —
   defaults fill, coercible values coerce, but any key/subtree the schema
   doesn't declare (and any field whose conform failed) survives from raw.
   `raw-config?` (used by `config get --raw`) skips the overlay entirely, so
   `:data` comes back exactly as given — pre-conform, no defaults."
  ([result] (-validate-root-config (cached-root-schema) result false))
  ([root-schema result] (-validate-root-config root-schema result false))
  ([root-schema {:keys [data] :as result} raw-config?]
   (if-not data
     result
     (let [root-result     (lexicon/conform (runtime-schema root-schema) data)
           root-result     (if raw-config? root-result (fill-absent-root-sections root-schema data root-result))
           defaults-result (when-let [defaults (:defaults data)]
                             (lexicon/conform (runtime-schema (schema-for root-schema :defaults)) defaults))
           overlaid-data   (if raw-config? data (schema-base/overlay-conformed data root-result))]
       (-> result
           (assoc :data overlaid-data)
           (update :errors into (concat
                                  (when (cs/error? root-result) (validation/schema-error-entries nil root-result))
                                  (when (and defaults-result (cs/error? defaults-result))
                                    (validation/schema-error-entries "defaults" defaults-result))))
           (assoc :warnings (warnings/root-config-warnings root-schema data)))))))

(defn- load-root-config [root {:keys [raw-parse-errors? substitute-env?] :as opts}]
  (let [overlay (entities/overlay-for opts paths/root-filename)
        path    (str root "/" paths/root-filename)]
    (cond
      overlay
      (let [{:keys [content relative]} overlay]
        (try
          (let [raw-data        (parse/read-edn-string content substitute-env?)
                {:keys [cron errors]} (companions/resolve-cron-prompts root raw-data)
                data            (cond-> raw-data
                                        (:cron raw-data) (assoc :cron cron))
                root-schema     (cached-root-schema)
                root-result     (lexicon/conform (runtime-schema root-schema) data)
                defaults-result (when-let [defaults (:defaults data)]
                                  (lexicon/conform (runtime-schema (schema-for root-schema :defaults)) defaults))]
            {:data     data
             :errors   (vec (concat errors
                                    (when (cs/error? root-result) (validation/schema-error-entries nil root-result))
                                    (when (and defaults-result (cs/error? defaults-result))
                                      (validation/schema-error-entries "defaults" defaults-result))))
             :warnings (concat (warnings/top-level-warnings raw-data)
                               (warnings/root-entity-warnings raw-data))
             :sources  [(parse/source-path relative)]})
          (catch Exception _
            {:data nil :errors [{:key paths/root-filename :value "EDN syntax error"}] :warnings [] :sources []})))

      (parse/exists?* path)
      (let [{raw-data :data error :error} (parse/read-edn-file path substitute-env? raw-parse-errors?)]
        (if error
          {:data nil :errors [{:key paths/root-filename :value error}] :warnings [] :sources []}
          (let [{:keys [cron errors]} (companions/resolve-cron-prompts root raw-data)
                data            (cond-> raw-data
                                        (:cron raw-data) (assoc :cron cron))
                root-schema     (cached-root-schema)
                root-result     (lexicon/conform (runtime-schema root-schema) data)
                defaults-result (when-let [defaults (:defaults data)]
                                  (lexicon/conform (runtime-schema (schema-for root-schema :defaults)) defaults))]
            {:data     data
             :errors   (vec (concat errors
                                    (when (cs/error? root-result) (validation/schema-error-entries nil root-result))
                                    (when (and defaults-result (cs/error? defaults-result))
                                      (validation/schema-error-entries "defaults" defaults-result))))
             :warnings (concat (warnings/top-level-warnings raw-data)
                               (warnings/root-entity-warnings raw-data))
             :sources  [(parse/source-path paths/root-filename)]})))

      :else
      {:data nil :errors [] :warnings [] :sources []})))

(defn- conform-berth-slices
  "Conform each config-berth-claimed slice of `config` against its
   composed schema from the effective root (validations stripped — the
   annotation layer owns those), storing the coerced values back.
   Uncoercible values become error rows, unknown fields warning rows;
   berths/normalize-errors rewrites their keys downstream. A slice that is
   wholly absent still conforms as {} so its nested :default values fill,
   dropping (never reporting) any required-field error that produces —
   nothing has been configured there yet (isaac-zmub)."
  [module-index root-schema config]
  (reduce
    (fn [acc path]
      (let [slice (get-in (:config acc) path)
            spec  (get-in root-schema (vec (mapcat (fn [segment] [:schema segment]) path)))]
        (if (nil? slice)
          (if-let [filled (schema-base/conform-absent-section spec)]
            (assoc-in acc (into [:config] path) filled)
            acc)
          (let [warns     (warnings/slice-unknown-key-warnings path spec slice)
                conformed (lexicon/conform (runtime-schema spec) slice)
                acc       (update acc :warnings into warns)]
            (if (cs/error? conformed)
              (update acc :errors into (validation/schema-error-entries
                                        (str/join "." (map name path)) conformed))
              (assoc-in acc (into [:config] path) conformed))))))
    {:config config :errors [] :warnings []}
    (berths/config-paths module-index)))

(def ^:private compose-error-types #{:config-schema/collision :config-schema/invalid-schema})
(def ^:private check-error-types   #{:config-check/collision :config-check/missing-fn :config-check/invalid-fn})

(defn- collision-error-row [prefix id-key e]
  {:key   (if-let [id (id-key (ex-data e))] (str prefix "." (name id)) prefix)
   :value (ex-message e)})

(defn- compose-or-fallback
  "Compose the effective root schema; on a config-schema collision /
   invalid-schema, fall back to the builtin composition (which cannot
   collide — only user modules do) and return the error so the load
   reports it located and keeps going."
  [module-index]
  (try [(schema-compose/cache-composed! module-index) nil]
       (catch clojure.lang.ExceptionInfo e
         (if (compose-error-types (:type (ex-data e)))
           [(schema-compose/cache-composed! (discovery/builtin-index))
            (collision-error-row "config-schema" :config-key e)]
           (throw e)))))

(defn load-config-result
  "Load and validate configuration from the current filesystem.
   `:skip-cache?` remains accepted as a compatibility no-op."
  [& [{:keys [root raw-parse-errors? substitute-env? skip-entity-files? data-path-overlay dotenv raw-config?]
       :or   {substitute-env? true}
       :as   opts}]]
  (let [fs*         (parse/runtime-fs opts)
        opts        (assoc opts :fs fs* :substitute-env? substitute-env?)
        ;; One collector for the whole load: every read below substitutes ${VAR}
        ;; references, and an unresolvable one drops its field rather than
        ;; passing the literal through (isaac-rxun).
        unresolved* (atom [])]
   (binding [parse/*unresolved-refs* unresolved*]
    (nexus/-with-nested-nexus {:fs fs*}
                              ;; A caller building a staged/in-memory load (e.g.
                              ;; isaac.foundation.config.mutate/validate-plan) passes the
                              ;; process's already-locked <root>/.env snapshot as
                              ;; `:dotenv` so this load resolves ${VAR} refs the
                              ;; same way the live load did, rather than re-locking
                              ;; against a staging fs that never received a copy
                              ;; of .env (isaac-p4oj).
                              (if-let [dotenv dotenv]
                                (env/lock-dotenv! root dotenv)
                                (env/lock-dotenv! root))
                              (let [config-root (paths/config-root root)]
                                (if-not (entities/config-files-present? config-root opts)
                                  {:config          {:root root}
                                   :errors          [{:key "config" :value (parse/missing-config-message root)}]
                                   :missing-config? true
                                   :warnings        []
                                   :sources         []}
                                  (let [inline-read     (read-root-config config-root opts)
                                        layout          (tree/scan config-root)
                                        slices          (tree/read-slices config-root (:slices layout)
                                                                          (:data inline-read) substitute-env?)
                                        dir-own         (tree/read-dir-own config-root (:dirs layout) substitute-env?)
                                        layout-errors   (vec (concat (:errors layout)
                                                                     (:errors slices)
                                                                     (mapcat (fn [[_ dir-name]]
                                                                               (tree/dir-errors (str config-root "/" dir-name)
                                                                                                (str dir-name "/")))
                                                                             (:dirs layout))))
                                        root-read       (-> inline-read
                                                            (assoc :data (merge-with (fn [own inline]
                                                                                       (if (and (map? own) (map? inline))
                                                                                         (merge own inline)
                                                                                         inline))
                                                                                     dir-own (:data slices)))
                                                            (update :errors into layout-errors)
                                                            (update :sources into (:sources slices)))
                                        root-data       (:data root-read)
                                        discovery-input (cond-> {}
                                                          (contains? root-data :modules) (assoc :modules (:modules root-data)))
                                        discovery       (discovery/discover! discovery-input {:root root
                                                                                                  :cwd  (host/cwd)})
                                        [effective-schema compose-error] (compose-or-fallback (:index discovery))
                                        {root-errors :errors root-warnings :warnings root-sources :sources
                                         overlaid-root-data :data}
                                        (-validate-root-config effective-schema root-read raw-config?)
                                        ;; Every directory under config/ is a key; foundation knows no
                                        ;; kind by name and reads no :entity-dir declaration (isaac-49zp).
                                        entity-kinds     (vec (:dirs layout))
                                        entity-files-by-kind
                                        (into {} (map (fn [[kind dir]]
                                                        [kind (entities/entity-files config-root dir opts)])
                                                      entity-kinds))
                                        md-warnings      (entities/dangling-md-warnings config-root (:dirs layout) root-data opts)
                                        base-config      (normalize/normalize-config effective-schema (or overlaid-root-data {}) raw-config?)
                                        result           {:config          base-config
                                                          :errors          root-errors
                                                          :missing-config? false
                                                          :warnings        (vec (concat root-warnings
                                                                                        (warnings/config-table-warnings
                                                                                          effective-schema root-data
                                                                                          (into (set (map first entity-kinds))
                                                                                                (map first (berths/config-paths (:index discovery)))))
                                                                                        (mapcat :warnings (vals entity-files-by-kind))
                                                                                        md-warnings))
                                                          :sources         root-sources
                                                          :root            (or root-data {})}
                                        result           (reduce (fn [acc kind]
                                                                   (entities/merge-root-entity effective-schema acc kind))
                                                                 result
                                                                 (schema-compose/merge-root-entity-kinds))
                                        result           (if skip-entity-files?
                                                             result
                                                             (reduce (fn [acc [kind _dir]]
                                                                       (reduce (fn [a entity-file]
                                                                                 (entities/load-entity-file effective-schema a config-root kind
                                                                                                     entity-file substitute-env? raw-parse-errors?))
                                                                               acc
                                                                               (:files (get entity-files-by-kind kind))))
                                                                     result
                                                                     entity-kinds))
                                        hail-module?     (contains? (:index discovery) :isaac.hail)
                                        result           (if hail-module?
                                                           (try
                                                             (let [resolve (requiring-resolve 'isaac.hail.band-resolve/apply-to-load-result!)]
                                                               (resolve effective-schema result))
                                                             (catch Throwable _ result))
                                                           result)
                                        config           (update (:config result) :defaults #(normalize/normalize-defaults effective-schema % raw-config?))
                                        config           (if data-path-overlay
                                                           (assoc-in config (:path data-path-overlay) (:value data-path-overlay))
                                                           config)
                                        ;; :_base inheritance resolves before anything validates or
                                        ;; instantiates a slot, so a `_<name>` template is never seen
                                        ;; as a real entry (isaac-h2ck).
                                        templating       (templating/resolve-config config)
                                        config           (:config templating)
                                        slices           (if raw-config?
                                                           {:config config :errors [] :warnings []}
                                                           (conform-berth-slices (:index discovery) effective-schema config))
                                        config           (assoc (:config slices)
                                                           :module-index (:index discovery)
                                                           :root root)
                                        raw-providers    (merge (get-in result [:root :providers])
                                                                (get-in result [:raw :providers]))
                                        check-ctx        {:config           config
                                                            :raw-providers    raw-providers
                                                            :module-index     (:index discovery)
                                                            :root             config-root
                                                            :result           result
                                                            :effective-schema effective-schema}
                                        contributed      (try (check-compose/run-checks (:index discovery) check-ctx)
                                                              (catch clojure.lang.ExceptionInfo e
                                                                (if (check-error-types (:type (ex-data e)))
                                                                  {:errors [(collision-error-row "config-check" :check-id e)] :warnings []}
                                                                  (throw e))))
                                        errors           (->> (concat (validation/semantic-errors config config-root effective-schema)
                                                                      (:errors templating)
                                                                      (:errors discovery)
                                                                      (:errors contributed)
                                                                      (:errors slices)
                                                                      (when compose-error [compose-error]))
                                                            (into (:errors result))
                                                            (berths/normalize-errors (:index discovery)))
                                        all-warnings     (->> (concat (:warnings result) (:warnings contributed) (:warnings slices)
                                                                      (:warnings discovery)
                                                                      (warnings/reference-warnings @unresolved*))
                                                              (berths/normalize-errors (:index discovery))
                                                              (sort-by :key)
                                                              vec)
                                        ;; Read back off the normalized rows so these paths match the keys
                                        ;; validation errors use (isaac-rxun).
                                        unresolved-refs  (warnings/unresolved-ref-index all-warnings)]
                                    {:config   (cond-> config
                                                (seq unresolved-refs) (assoc :unresolved-refs unresolved-refs))
                                     :errors   (->> (sort-by :key errors)
                                                    (distinct)
                                                    (warnings/attach-reference-reasons unresolved-refs))
                                     ;; Log both as they leave the loader: a key the schema silently prunes
                                     ;; (isaac-nq4c) and a field an unresolvable reference silently emptied
                                     ;; (isaac-rxun) are otherwise invisible until someone separately runs
                                     ;; `isaac config validate`.
                                     :warnings (->> all-warnings
                                                    (warnings/log-unknown-keys!)
                                                    (warnings/log-unresolved-refs!))
                                     :sources  (vec (sort (:sources result)))
                                     ;; Raw (pre-conform-overlay) root-level data, already computed
                                     ;; above — a companion for callers that need to tell a schema
                                     ;; default apart from a file-set value (isaac-dnib's `(default)`
                                     ;; annotation) WITHOUT a second `load-config-result` call, which
                                     ;; would violate "the CLI resolves the config once per command"
                                     ;; (isaac-v1la). Root-level fields only — not entity-dir files.
                                     :raw-root (or root-data {})})))))))

;; region ----- Ambient Config Snapshot -----

(defn- install-config-atom!
  "The atom holding the process-wide config, registering one when the slot is
   free. Write path only: reading must never plant a slot the owning entry
   point expects to claim (isaac-600d). An already registered atom is reused so
   everything already holding it keeps seeing updates."
  []
  (or (nexus/get :config)
      (let [cfg* (atom nil)]
        (nexus/register! [:config] cfg*)
        cfg*)))

(defn snapshot
  "Returns the current process-wide config, or nil if not yet initialized.
   Reads ambient config; call ONLY at entry points and wake boundaries (process
   start, request/turn entry, a worker waking from sleep) — in-flight code must
   receive config as a value, not pull a fresh snapshot. `reason` is a short
   string documenting why this site reads ambient config; it keeps such reads
   greppable and reviewable. See set-snapshot!.

   Read-only: a snapshot of an uninitialized config is nil and registers
   nothing. Only set-snapshot! installs the slot."
  [reason]
  (some-> (nexus/get :config) deref))

(defn unresolved-ref
  "The `${VAR}` name a config field referenced but could not resolve, or nil.
   `path` is the dotted field path the warnings use (`providers.zane.api-key`).

   The field itself is absent — an unresolvable reference is an unset field
   (isaac-rxun) — so the code that needs it cannot see why. This is how the
   point of use names the variable instead of reporting a bare \"not set\"."
  ([path] (unresolved-ref (snapshot "unresolved-ref: which ${VAR} emptied this field") path))
  ([config path] (get-in config [:unresolved-refs path])))

(defn set-snapshot!
  "Low-level primitive: reset the process-wide config snapshot to `cfg`. Internal
   to config — callers use load-config! (load + commit) or, for an already-built
   value, dangerously-install-config!. `reason` documents the call site.

   This is the only path that registers the config slot; reads leave a fresh
   nexus untouched."
  [cfg reason]
  (log/debug :config/set-snapshot :reason reason)
  (reset! (install-config-atom!) cfg)
  cfg)

(defn load-config!
  "THE loader: load config from `root` (read via `fs`), validate it, commit
   it as the process-wide snapshot, and return the value. Call once at an entry
   point, then thread the returned value onward (or read the snapshot). Throws
   ex-info {:errors [...]} carrying ALL validation/coercion errors when the
   config is invalid (a missing config is not an error — it commits the empty
   default). `reason` documents the call site."
  [root fs reason]
  (let [{:keys [config errors missing-config?]}
        (load-config-result {:root root :fs fs})]
    (when (and (seq errors) (not missing-config?))
      (throw (ex-info (str "invalid configuration in " root)
                      {:errors errors :root root})))
    (when (:module-index config)
      (lifecycle/reconcile-modules! (:module-index config)))
    (set-snapshot! config reason)
    config))

(defn load-config
  "Compatibility wrapper for older module repos. Loads config and returns only
   the config value without committing it as the process snapshot."
  ([] (:config (load-config-result)))
  ([opts] (:config (load-config-result opts))))

(defn root
  "Returns the resolved root. Test fixtures install an explicit
   :root on the nexus via -with-nested-nexus and that wins; otherwise the
   loaded config carries :root (derived from home). Production never
   installs the nexus slot, so the config snapshot is authoritative there."
  []
  (or (nexus/get :root)
      (:root (snapshot "root resolution — ambient config fallback"))))

;; endregion ^^^^^ Ambient Config Snapshot ^^^^^

;; region ----- Workspace -----

(defn resolve-workspace
  [crew-id & [{:keys [root] :as opts}]]
  (let [fs*       (parse/runtime-fs opts)
        crew-dir  (str root "/crew/" crew-id)
        isaac-dir (str root "/workspace-" crew-id)
        ;; Legacy ~/.openclaw lives beside ~/.isaac, so it only applies when the
        ;; root is a .isaac directory under a user home.
        oc-dir    (when (str/ends-with? (str root) "/.isaac")
                    (str (subs root 0 (- (count root) (count "/.isaac")))
                         "/.openclaw/workspace-" crew-id))]
    (nexus/-with-nested-nexus {:fs fs*}
                              (cond
                                (some? (parse/children* crew-dir)) crew-dir
                                (and oc-dir (some? (parse/children* oc-dir))) oc-dir
                                (some? (parse/children* isaac-dir)) isaac-dir
                                :else nil))))

(defn read-workspace-file
  [crew-id filename & [{:as opts}]]
  (let [fs* (parse/runtime-fs opts)]
    (nexus/-with-nested-nexus {:fs fs*}
                              (when-let [ws-dir (resolve-workspace crew-id opts)]
                                (let [path (str ws-dir "/" filename)]
                                  (when (parse/exists?* path)
                                    (parse/slurp* path)))))))

;; endregion ^^^^^ Workspace ^^^^^

;; Module-loader registration: dispatched by module.loader when reading
;; user-supplied config for a module's :tools or :slash-commands entry.
(lifecycle/register-handler! :user-config
                                 (fn [root-key entry-id]
                                   (let [snap (snapshot "module :user-config handler — ambient config lookup")]
                                     (or (get-in snap [root-key entry-id])
                                         (get-in snap [root-key (keyword entry-id)])))))
