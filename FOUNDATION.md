# Foundation API

Isaac's **foundation** is the CLI, module loader, config machinery, and shared
runtime primitives that every module builds on. Module authors depend on a small,
**enforced** public surface — not a flat bag of internal namespaces.

This document describes the three API tiers, the `isaac.foundation` facade, and
the documented direct-import carve-outs.

## Purpose

Foundation owns:

- Discovering and activating modules from `isaac-manifest.edn`
- Loading, validating, and hot-reloading config
- The process-wide **nexus** registry (`:fs`, `:config`, `:module-index`, `:scheduler`, …)
- CLI command registration via berths

Modules contribute behavior through **manifest data** (berth entries, schema
fragments, CLI maps) and optional Clojure factories. Production module code should
require **`isaac.foundation`** plus the small carve-out list below — not
`isaac.config.loader`, `isaac.module.loader`, or other internals.

## Quick start

A minimal module needs a manifest and a factory:

```edn
{:id          :my.module
 :version     "0.1.0"
 :factory     my.module/create-module
 :description "Example module"}
```

```clojure
(ns my.module
  (:require [isaac.foundation :as foundation]))

(defn create-module []
  (foundation/create-module))
```

To contribute a CLI command, add a berth entry in the manifest and implement
`isaac.cli.api` multimethods in a separate namespace (direct import — multimethods
cannot be re-exported):

```edn
{:isaac/cli {:hello {:usage     "hello"
                     :summary   "Say hello"
                     :namespace my.module.cli}}}
```

```clojure
(ns my.module.cli
  (:require [isaac.cli.api :as cli-api]))

(defmethod cli-api/run :hello [_id _opts]
  (println "Hello!")
  0)
```

See `modules/isaac.cli.greeter` for a working example. Builtin berth declarations
live in `src/isaac-manifest.edn`.

## Tier 1 — module authors

**Goal:** At most **`isaac.foundation`** plus the [direct-import carve-outs](#tier-1-direct-imports).

### `isaac.foundation` facade

Re-exported fn / protocol / var surfaces only. Defined in `src/isaac/foundation.clj`.

| Source | Export | Kind | Notes |
|--------|--------|------|-------|
| `isaac.module.protocol` | `Module` | protocol | Lifecycle hooks `on-startup` / `on-shutdown` |
| | `module` | fn | Build a module instance |
| | `module?` | fn | `satisfies?` check |
| | `create-module` | fn | Manifest `:factory` entry point (same ns) |
| `isaac.config.loader` | `load-config!` | fn | Load, validate, commit snapshot (entry points) |
| | `load-config-result` | fn | Inspect without committing |
| | `snapshot` | fn | Ambient read — entry points only |
| | `root` | fn | Runtime root from snapshot |
| | `normalize-config` | fn | Canonical config shape |
| | `env` | fn | `${VAR}` substitution |
| `isaac.reconfigurable` | `Reconfigurable` | protocol | Config-driven component lifecycle |
| | `on-startup!` | fn | Protocol dispatch |
| | `on-config-change!` | fn | Protocol dispatch |
| `isaac.nexus` | `get` | fn | Read registered runtime state |
| | `get-in` | fn | Nested read |
| | `register!` | fn | Publish factory output |

**Not in the facade:**

| Symbol | Where | Why |
|--------|-------|-----|
| `default-root` | `isaac.config.root` | Bootstrap / CLI only — before config load |
| `dangerously-install-config!`, env overrides | `isaac.config.api` | Test / host internals |
| `necho`, `init!`, `reset!`, test macros | `isaac.nexus` | Host / test lifecycle |
| `isaac.foundation.version` | `isaac.foundation.version` | Distribution / `--version` only |

`isaac.nexus/schema` documents foundation-reserved slots only: `:fs`, `:config`,
`:module-index`, `:scheduler`. Platform hosts install additional keys (`:sessions`,
`:tool-registry`, …) at runtime; those are omitted from foundation documentation.

### Tier-1 direct imports

Multimethods, macros, and bootstrap helpers must be required from their real
namespace — a `defmethod` attaches to the original var.

| Namespace | Why direct |
|-----------|------------|
| `isaac.cli.api` | `run`, `option-spec`, `subcommands`, `help` multimethods |
| `isaac.fs` | `Fs` protocol, `real-fs`, `mem-fs` — factory I/O |
| `isaac.logger` | `info`, `warn`, `error`, `debug` |
| `isaac.config.paths` | Pure path helpers (`config-path`, `root-config-file`, …) |
| `isaac.config.root` | Bootstrap root resolution (`default-root`) |
| `isaac.schema.lexicon` | Register custom apron types in code (advanced) |
| `isaac.schema.meta` | `conform-spec!` for manifest schema authors (advanced) |

Modules may also require `isaac.reconfigurable` directly when they only need the
protocol (the facade re-exports the same surface).

### Forbidden for modules

Do not require these from module production code (enforced in step 9):

```
isaac.module.loader
isaac.module.manifest
isaac.config.loader
isaac.config.install
isaac.config.configurator
isaac.config.berths
isaac.config.runtime
isaac.config.schema-compose
isaac.config.check-compose
isaac.config.validation
isaac.cli.registry
isaac.main
isaac.api
```

Integrate via **berth declarations + manifest contributions + `:factory`**, not
by requiring Tier-3 internals.

## Tier 2 — runtime hosts

Server, agent daemon, and test harnesses that boot the world.

| Namespace | Surface |
|-----------|---------|
| `isaac.module.loader` | `discover!`, `process-manifest-berths!`, `start-modules!`, `shutdown-modules!`, `builtin-index` |
| `isaac.config.runtime` | `install!`, `install-config-berths!`, `reconcile!`, `reload!`, `validate-config!`, change-source |
| `isaac.nexus` | Full surface including `init!`, `reset!`, `-with-nexus`, `-with-nested-nexus` |

`Reconfigurable` is **Tier 1** (`isaac.reconfigurable`), not Tier 2.

## Tier 3 — foundation internals

Everything else under `isaac.config.*`, `isaac.module.*`, the schema/check compose
pipeline, `isaac.cli.registry`, `isaac.main`, and scheduler internals except the
documented primitives. Modules should not require these namespaces.

## Berths

Modules extend Isaac by declaring **berths** in `isaac-manifest.edn`. A berth is a
typed contribution slot declared in the foundation manifest (`:berths` in
`src/isaac-manifest.edn`):

| Berth key | Contribution shape |
|-----------|-------------------|
| `:isaac/cli` | Map of command id → `{:usage :summary :namespace}` |
| `:isaac.config/schema` | Map of table key → apron schema fragment |
| `:isaac.config/check` | Map of check id → post-load validation fn |

The loader resolves per-entry `:factory` symbols at activation time. Factories
typically use Tier-1 APIs (`foundation/register!`, `isaac.logger`, `isaac.fs`) to
publish live instances into the nexus.

Manifest-only modules (EDN contributions, no Clojure requires) are the common case.

## Config

Two different “roots”:

| Concept | Namespace | When |
|---------|-----------|------|
| **Bootstrap root** | `isaac.config.root` | CLI / `main` resolves before config is loaded (`default-root`, `--root` flag) |
| **Runtime root** | `foundation/root` or snapshot `:root` | After `load-config!` commits the process-wide snapshot |

Path construction helpers (`config-path`, entity dirs, pointer files) live in
`isaac.config.paths` (direct import). Module authors read config through the
facade; hosts load it via `foundation/load-config!` at entry points.

## Reconfigurable

Config-driven components that need startup and reload lifecycle implement
`isaac.reconfigurable/Reconfigurable` (re-exported as `foundation/Reconfigurable`):

```clojure
(ns my.module.node
  (:require [isaac.foundation :as foundation]))

(defrecord RelayStation [state*]
  foundation/Reconfigurable
  (on-startup! [_ slice]
    (reset! state* {:slice slice :event :started}))
  (on-config-change! [_ _old new-slice]
    (reset! state* {:slice new-slice :event :changed})))
```

The reconciler (Tier 2, `isaac.config.runtime`) invokes `on-startup!` and
`on-config-change!` on live nexus instances. Do not implement the protocol via
`isaac.config.berths` or `isaac.config.runtime` — those are internal / host-only.

## Enforcement

Two specs guard the foundation boundary:

| Spec | What it checks |
|------|----------------|
| `spec/isaac/foundation_boundary_spec.clj` | Foundation file set never requires server/agent namespaces |
| `spec/isaac/foundation_spec.clj` | Facade re-exports; smoke module requires only facade + carve-outs |
| `spec/isaac/foundation_module_boundary_spec.clj` | Module `modules/` sources require Tier 1 or agent surfaces only |

The module boundary spec parses `:require` forms statically. Factories that must
call Tier-3 internals at runtime should use `requiring-resolve`, not a namespace
require.

## Not foundation

`isaac.api` is the **agent** public surface (sessions, comm registration, LLM
providers, bridge dispatch). It moves with the agent/platform split — not part of
foundation Tier 1. Agent modules (`isaac.comm.*`, `isaac.llm.*`, …) use
`isaac.api`, not `isaac.foundation`.