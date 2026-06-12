(ns isaac.config.api
  "The public READ interface to Isaac configuration: load, snapshot, resolve,
   env. Everything outside the isaac.config.* namespaces requires *only* this
   namespace (aliased `config`) for reading config — never loader / mutate
   directly — so those internals stay free to reorganize behind this surface.

   The write / lifecycle side (installing config into the nexus, the
   Reconfigurable reconciler, and the file watcher that drives hot reload)
   lives in the sibling facade isaac.config.runtime, so read-only callers don't
   transitively pull in isaac.comm.registry / isaac.session.store.

   Exceptions: isaac.config.paths and isaac.config.nav are dependency-free
   utilities (pure path construction / schema-path walking) that callers may
   require directly; they carry no config state or logic to hide.

   Each fn delegates to its source at call time, so `with-redefs` on the
   underlying fn still takes effect for callers through this API."
  (:require
    [isaac.config.loader :as loader]
    [isaac.config.paths :as paths]
    [isaac.root :as root]))

;; ----- loading & snapshot -----

(defn load-config-result
  "Loads config and returns the full result map (config plus errors/warnings),
   WITHOUT committing it as the snapshot. For config tooling that inspects or
   validates config (mutate/validate CLIs, reload, missing-config checks) — to
   load config for the running process use load-config!.
   {:config :errors :warnings :sources :missing-config?}.
   opts keys: :root (required), :fs, :substitute-env?, :raw-parse-errors?,
   :skip-entity-files?, :data-path-overlay."
  ([]     (loader/load-config-result))
  ([opts] (loader/load-config-result opts)))

(defn normalize-config
  "Normalizes a raw config map into canonical shape (crew/models/providers,
   legacy forms migrated)."
  [cfg]
  (loader/normalize-config cfg))

(defn load-config!
  "THE loader: load config from `root` (read via `fs`), validate it, commit
   it as the process-wide snapshot, and return the value. Call once per process
   at an entry point, then thread the returned value onward or read the snapshot
   — never re-load. Throws ex-info {:errors [...]} carrying ALL validation errors
   when the config is invalid. `reason` documents the call site."
  [root fs reason]
  (loader/load-config! root fs reason))

(defn snapshot
  "Returns the current process-wide config snapshot, or nil if not yet set.
   An ambient read: call ONLY at entry points / wake boundaries; in-flight code
   must receive config as a value. `reason` documents why this site reads
   ambient config (kept greppable / reviewable)."
  [reason]
  (loader/snapshot reason))

(defn dangerously-install-config!
  "Commit an already-built config value as the process-wide snapshot, bypassing
   the loader. Reserved for boot (committing a runtime-built config), reload
   (after validation), and tests committing a synthetic config. Prefer
   load-config!. `reason` documents the call site."
  [cfg reason]
  (loader/set-snapshot! cfg reason))

(defn root
  "Returns the resolved state directory: a nexus-installed test override if
   present, otherwise the loaded config snapshot's :root."
  []
  (loader/root))

(defn default-root
  "Computes the default state directory for CLI-style opts: :root wins,
   otherwise :home (or the user's home directory) + the standard .isaac suffix.
   Use at entry points before load; the running process should read [[root]]
   (the resolved snapshot) instead."
  [opts]
  (or (:root opts)
      (paths/default-root (or (:home opts) (root/user-home)))))

;; ----- env -----

(defn env
  "Resolves an environment variable name for ${VAR} substitution: an override
   first, then the process environment, then the loaded .env snapshot."
  [name]
  (loader/env name))

(defn set-env-override!
  "Sets an env-var override (test support). Clears the load cache."
  [name value]
  (loader/set-env-override! name value))

(defn clear-env-overrides!
  "Clears all env-var overrides and the .env snapshot (test support)."
  []
  (loader/clear-env-overrides!))

;; ----- resolution -----
;;
;; Provider/crew/server-config resolution moved off the foundation read facade
;; into isaac.config.resolve (server-side; depends on isaac.llm / config.schema).
;; Server callers require isaac.config.resolve directly. Keeping it here would
;; pull isaac.llm + isaac.session into the foundation (see the foundation
;; boundary gate, isaac-youm).
