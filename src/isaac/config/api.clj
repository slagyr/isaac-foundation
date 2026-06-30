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
    [isaac.config.loader :as loader]))

(defn load-resolved
  "Live read from disk through the loader. Returns the full `load-config-result`
   map (:config, :errors, :warnings, ...). Use for hot-reload paths that need
   fresh resolved state."
  ([] (loader/load-config-result))
  ([opts] (loader/load-config-result opts)))

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
  (loader/set-env-override! name value))

(defn clear-env-overrides!
  "Clears all env-var overrides and the .env snapshot (test support)."
  []
  (loader/clear-env-overrides!))