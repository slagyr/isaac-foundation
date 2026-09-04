;; mutation-tested: 2026-05-06
(ns isaac.config.loader
  "Config load orchestration: root read/validate, entity merge, berth slices, snapshot.

   Production logic for env/parse/companions/entities/normalize/warnings lives in
   those namespaces. This ns owns load orchestration + ambient snapshot + workspace.
   Vars below forward former public surface so published agent/server/hail modules
   that still require isaac.config.loader keep loading until they cut over
   (isaac-a7c0). Foundation-internal callers require the owning namespace directly."
  (:require
    [c3kit.apron.schema :as cs]
    [clojure.string :as str]
    [isaac.config.berths :as berths]
    [isaac.config.check-compose :as check-compose]
    [isaac.config.companions :as companions]
    [isaac.config.entities :as entities]
    [isaac.config.env :as env]
    [isaac.config.normalize :as normalize]
    [isaac.config.parse :as parse]
    [isaac.config.paths :as paths]
    [isaac.config.schema-base :as schema-base]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.config.validation :as validation]
    [isaac.config.warnings :as warnings]
    [isaac.logger :as log]
    [isaac.module.discovery :as discovery]
    [isaac.module.lifecycle :as lifecycle]
    [isaac.nexus :as nexus]
    [isaac.schema.lexicon :as lexicon]
    [isaac.startup.config-cache :as config-cache]))

;; Temporary public re-exports of the former loader surface (isaac-flgy / a7c0).
;; External modules (agent/server/hail/…) still call these via isaac.config.loader.
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

(defn -validate-root-config
  "Private intent, but feature spies (isaac-v1la) wrap this var."
  ([result] (-validate-root-config (cached-root-schema) result))
  ([root-schema {:keys [data] :as result}]
   (if-not data
     result
     (let [root-result     (lexicon/conform (runtime-schema root-schema) data)
           defaults-result (when-let [defaults (:defaults data)]
                             (lexicon/conform (runtime-schema (schema-for root-schema :defaults)) defaults))]
       (-> result
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
   berths/normalize-errors rewrites their keys downstream."
  [module-index root-schema config]
  (reduce
    (fn [acc path]
      (let [slice (get-in (:config acc) path)]
        (if (nil? slice)
          acc
          (let [spec      (get-in root-schema (vec (mapcat (fn [segment] [:schema segment]) path)))
                warns     (warnings/slice-unknown-key-warnings path spec slice)
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

(defn- overlays? [opts]
  (or (:skip-entity-files? opts)
      (:data-path-overlay opts)
      (:overlay-content opts)
      (:overlay-path opts)
      (:raw-parse-errors? opts)))

(defn- try-cached-result [fs* root opts]
  (when-not (overlays? opts)
    (try
      (when-let [cached (config-cache/read-pre-sub fs* root)]
        (-> cached
            (config-cache/hydrate opts)
            (update :config assoc :root root)
            (assoc :missing-config? false)))
      (catch Exception _ nil))))

(defn load-config-result
  [& [{:keys [root raw-parse-errors? substitute-env? skip-entity-files? data-path-overlay]
       :or   {substitute-env? true}
       :as   opts}]]
  (let [fs*  (parse/runtime-fs opts)
        opts (assoc opts :fs fs* :substitute-env? substitute-env?)]
    (or (try-cached-result fs* root opts)
        (nexus/-with-nested-nexus {:fs fs*}
                              (env/lock-dotenv! root)
                              (let [config-root (paths/config-root root)]
                                (if-not (entities/config-files-present? config-root opts)
                                  {:config          {:root root}
                                   :errors          [{:key "config" :value (parse/missing-config-message root)}]
                                   :missing-config? true
                                   :warnings        []
                                   :sources         []}
                                  (let [root-read       (read-root-config config-root opts)
                                        root-data       (:data root-read)
                                        discovery-input (cond-> {}
                                                          (contains? root-data :modules) (assoc :modules (:modules root-data)))
                                        discovery       (discovery/discover! discovery-input {:root root
                                                                                                  :cwd  (System/getProperty "user.dir")})
                                        [effective-schema compose-error] (compose-or-fallback (:index discovery))
                                        {root-errors :errors root-warnings :warnings root-sources :sources}
                                        (-validate-root-config effective-schema root-read)
                                        entity-kinds     (->> (schema-compose/descriptors)
                                                              (keep (fn [[kind {:keys [entity-dir]}]]
                                                                      (when entity-dir [kind entity-dir])))
                                                              vec)
                                        entity-files-by-kind
                                        (into {} (map (fn [[kind dir]]
                                                        [kind (entities/entity-files config-root dir opts)])
                                                      entity-kinds))
                                        md-warnings      (entities/dangling-md-warnings config-root root-data opts)
                                        base-config      (normalize/normalize-config effective-schema (or root-data {}))
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
                                        config           (update (:config result) :defaults #(normalize/normalize-defaults effective-schema %))
                                        config           (if data-path-overlay
                                                           (assoc-in config (:path data-path-overlay) (:value data-path-overlay))
                                                           config)
                                        slices           (conform-berth-slices (:index discovery) effective-schema config)
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
                                                                      (:errors discovery)
                                                                      (:errors contributed)
                                                                      (:errors slices)
                                                                      (when compose-error [compose-error]))
                                                            (into (:errors result))
                                                            (berths/normalize-errors (:index discovery)))]
                                    {:config   config
                                     :errors   (vec (distinct (sort-by :key errors)))
                                     :warnings (->> (concat (:warnings result) (:warnings contributed) (:warnings slices))
                                                    (berths/normalize-errors (:index discovery))
                                                    (sort-by :key)
                                                    vec)
                                     :sources  (vec (sort (:sources result)))})))))))

;; region ----- Ambient Config Snapshot -----

(defn- config-atom []
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
   greppable and reviewable. See set-snapshot!."
  [reason]
  @(config-atom))

(defn set-snapshot!
  "Low-level primitive: reset the process-wide config snapshot to `cfg`. Internal
   to config — callers use load-config! (load + commit) or, for an already-built
   value, dangerously-install-config!. `reason` documents the call site."
  [cfg reason]
  (log/debug :config/set-snapshot :reason reason)
  (reset! (config-atom) cfg)
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
