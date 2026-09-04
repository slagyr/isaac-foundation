(ns isaac.startup.config-cache
  "CLI startup-cache payload for the resolved (pre-substitution) config (isaac-v1la).

   cache/cli.edn is world-readable, so the cached blob must never contain
   substituted secrets. We store the normalized config with ${VAR} placeholders
   intact and re-apply env substitution on a warm read."
  (:require
    [isaac.config.env :as env]
    [isaac.config.parse :as parse]
    [isaac.config.paths :as paths]
    [isaac.startup.cache :as cache]))

(defn- cache-config [cached]
  (get-in cached [:data :config]))

(defn- usable-cached-config? [cached]
  (let [config (cache-config cached)]
    (and (map? config) (seq config))))

(defn read-pre-sub
  "Return the cached pre-substitution load result when the startup cache is
   fresh and carries a usable :config blob; otherwise nil (fail-open)."
  [fs* root]
  (try
    (when-let [cached (cache/read-cache fs* root)]
      (when (and (= cache/cache-version (:version cached))
                 (usable-cached-config? cached))
        (let [config  (cache-config cached)
              watched (cache/watched-files (paths/root-config-file root)
                                           config
                                           (System/getProperty "user.dir"))]
          (when (cache/fresh? fs* root watched)
            {:config   config
             :errors   (or (get-in cached [:data :errors]) [])
             :warnings (or (get-in cached [:data :warnings]) [])
             :sources  (or (get-in cached [:data :sources]) [])}))))
    (catch Exception _ nil)))

(defn hydrate
  "Re-apply env substitution to a cached pre-substitution load result so a
   warm hit still reflects the current .env. No-op when substitute-env? is
   false (raw CLI path)."
  [cached-result {:keys [root substitute-env?] :or {substitute-env? true}}]
  (if-not substitute-env?
    cached-result
    (do
      (env/lock-dotenv! root)
      (update cached-result :config parse/substitute-env-recursive))))

(defn cacheable-config
  "Strip process-local keys that should not land in the world-readable cache."
  [config]
  (dissoc config :module-index :root))
