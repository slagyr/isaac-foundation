(ns isaac.config.api
  "Blessed configuration access for modules and boot paths.

   **Read config** — `load-resolved`, `resolved-config`, `resolved-slice`: live
   disk reads through `isaac.config.loader` (${VAR} resolution, schema
   validation, entity merge). Never slurp/read-string `config/isaac.edn` or entity
   files directly.

   **Write / test** — `dangerously-install-config!`, env overrides: see below.

   Server lifecycle (install, reconcile, reload) lives in `isaac.config.runtime`.

   Each fn delegates to `isaac.config.loader` at call time, so `with-redefs` on the
   underlying fn still takes effect for callers through this API."
  (:require
    [isaac.config.env :as env]
    [isaac.config.loader :as loader]))

;; Process-local memo for CLI: one resolution per (root, fs, load opts) until
;; cleared. Server hot-reload calls `clear-process-memo!` (or uses a new
;; process); `load-config-result` itself is not memoized so a direct loader
;; call still re-resolves.
(defonce ^:private process-memo* (atom {}))

(defn clear-process-memo!
  "Drop the process-local load-resolved memo. Called at the start of each CLI
   run and whenever env overrides change."
  []
  (reset! process-memo* {}))

(defn- process-memo-key [opts]
  [(:root opts)
   (System/identityHashCode (:fs opts))
   (if (contains? opts :substitute-env?) (:substitute-env? opts) true)
   (:skip-entity-files? opts)
   (:data-path-overlay opts)
   (:overlay-content opts)
   (:overlay-path opts)
   (:raw-parse-errors? opts)])

(defn load-resolved
  "Live read from disk through the loader. Returns the full `load-config-result`
   map (:config, :errors, :warnings, ...). Use for hot-reload paths that need
   fresh resolved state. Memoized per process on (root, fs, load opts); call
   `clear-process-memo!` to force a re-read."
  ([] (load-resolved {}))
  ([opts]
   (let [opts (or opts {})
         k    (process-memo-key opts)]
     (if-let [cached (get @process-memo* k)]
       cached
       (let [result (loader/load-config-result opts)]
         (swap! process-memo* assoc k result)
         result)))))

(defn resolved-config
  "Live read of the normalized `:config` map. Prefer this over raw slurp of
   `config/isaac.edn` or entity files."
  ([] (:config (load-resolved)))
  ([opts] (:config (load-resolved opts))))

(defn resolved-slice
  "Live read of a config subtree at `path` (vector of keys)."
  ([path] (get-in (resolved-config) path))
  ([path opts] (get-in (resolved-config opts) path)))

(defn dangerously-install-config!
  "Commit an already-built config value as the process-wide snapshot, bypassing
   the loader. Reserved for boot (committing a runtime-built config), reload
   (after validation), and tests committing a synthetic config. Prefer
   `load-config!`. `reason` documents the call site."
  [cfg reason]
  (loader/set-snapshot! cfg reason))

(defn set-env-override!
  "Sets an env-var override (test support). Clears the load cache."
  [name value]
  (clear-process-memo!)
  (env/set-env-override! name value))

(defn clear-env-overrides!
  "Clears all env-var overrides and the .env snapshot (test support)."
  []
  (clear-process-memo!)
  (env/clear-env-overrides!))