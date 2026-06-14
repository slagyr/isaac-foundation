(ns isaac.foundation
  "Tier-1 public API for Isaac modules. Documented fn/protocol/var surfaces only.
   CLI multimethods, fs, logger, and config.paths are direct imports — see FOUNDATION.md."
  (:refer-clojure :exclude [get get-in])
  (:require
    [isaac.module.protocol :as module-protocol]
    [isaac.config.loader :as loader]
    [isaac.reconfigurable :as reconfigurable]
    [isaac.nexus :as nexus]))

;; module.protocol

(def Module
  "Lifecycle protocol for module instances: optional `on-startup` / `on-shutdown` hooks."
  module-protocol/Module)

(defn module
  "Returns a module instance. With no args, an empty module; with `opts`, optional
   `:on-startup` and `:on-shutdown` fns are wired as protocol methods."
  ([] (module-protocol/module))
  ([opts] (module-protocol/module opts)))

(defn module?
  "Returns true when `value` satisfies `Module`."
  [value]
  (module-protocol/module? value))

(defn create-module
  "Manifest `:factory` entry point: returns a default module instance."
  []
  (module-protocol/module))

;; config.loader (read path — no default-root; use isaac.config.root for bootstrap)

(defn load-config-result
  "Loads config and returns the full result map (config plus errors/warnings),
   WITHOUT committing it as the snapshot. For config tooling that inspects or
   validates config — to load config for the running process use `load-config!`.
   `{:config :errors :warnings :sources :missing-config?}`.
   opts keys: `:root` (required), `:fs`, `:substitute-env?`, `:raw-parse-errors?`,
   `:skip-entity-files?`, `:data-path-overlay`."
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
   — never re-load. Throws ex-info `{:errors [...]}` carrying ALL validation errors
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

(defn root
  "Returns the resolved state directory: a nexus-installed test override if
   present, otherwise the loaded config snapshot's `:root`."
  []
  (loader/root))

(defn env
  "Resolves an environment variable name for `${VAR}` substitution: an override
   first, then the process environment, then the loaded .env snapshot."
  [name]
  (loader/env name))

;; reconfigurable (protocol home — not config.runtime)

(def Reconfigurable
  "Protocol for config-driven components: `on-startup!` with a config slice and
   `on-config-change!` when that slice changes on reload."
  reconfigurable/Reconfigurable)

(defn on-startup!
  "Invokes `on-startup!` on a `Reconfigurable` instance with its config slice."
  [instance slice]
  (reconfigurable/on-startup! instance slice))

(defn on-config-change!
  "Invokes `on-config-change!` on a `Reconfigurable` instance with old and new slices."
  [instance old-slice new-slice]
  (reconfigurable/on-config-change! instance old-slice new-slice))

;; nexus (factory publish/read)

(defn get
  "Returns the value registered under `k` in the process-wide nexus, or nil."
  [k]
  (nexus/get k))

(defn get-in
  "Returns the value at `path` in the process-wide nexus, or nil."
  [path]
  (nexus/get-in path))

(defn register!
  "Registers `v` at `path` in the process-wide nexus."
  [path v]
  (nexus/register! path v))