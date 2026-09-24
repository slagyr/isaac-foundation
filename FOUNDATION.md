# Foundation

Isaac's **foundation** is the CLI, module loader, config machinery, and shared
runtime primitives that every module builds on. The foundation repo is splitting
out from the monolith; module authors require **foundation components directly**
(`isaac.nexus`, `isaac.fs`, `isaac.reconfigurable`, …) rather than through a
single facade namespace. The right module-facing API will emerge from that split.

## Purpose

Foundation owns:

- Discovering and activating modules from `isaac-manifest.edn`
- Loading, validating, and hot-reloading config
- The process-wide **nexus** registry (`:fs`, `:config`, `:module-index`, `:scheduler`, …)
- CLI command registration via berths

Modules contribute behavior through **manifest data** (berth entries, schema
fragments, CLI maps) and optional Clojure factories.

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
  (:require [isaac.module.protocol :as module]))

(defn create-module []
  (module/module))
```

To contribute a CLI command, add a berth entry in the manifest and implement
`isaac.cli.api` multimethods in a separate namespace:

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

See `modules/marigold.cli.greeter` for a Marigold fixture shaped like a real
module (production modules use your own prefix, not `marigold.*`). Builtin berth
declarations live in `src/isaac-manifest.edn`.

## Module-facing components

Require these directly when building module code:

| Namespace | Surface | Notes |
|-----------|---------|-------|
| `isaac.module.protocol` | `Module`, `module`, `module?` | Module lifecycle hooks |
| `isaac.nexus` | `get`, `get-in`, `register!` | Publish factory output to runtime |
| `isaac.cli.api` | `run`, `option-spec`, … | CLI multimethods |
| `isaac.fs` | `Fs`, `real-fs`, `mem-fs` | Factory I/O |
| `isaac.logger` | `info`, `warn`, `error`, `debug` | |
| `isaac.config.paths` | `config-path`, `root-config-file`, … | Pure path helpers |
| `isaac.config.root` | `default-root`, … | Bootstrap root before config load |
| `isaac.reconfigurable` | `Reconfigurable` | Config-driven component lifecycle |
| `isaac.schema.lexicon` | apron type registration | Advanced |
| `isaac.schema.meta` | `conform-spec!` | Manifest schema authors |

`isaac.nexus/schema` documents foundation-reserved slots: `:fs`, `:config`,
`:module-index`, `:scheduler`. Platform hosts install additional keys at runtime.

### Forbidden for modules

Do not require these from module production code (enforced in
`spec/isaac/foundation_module_boundary_spec.clj`):

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
by requiring loader/berth internals.

## Tier 2 — runtime hosts

Server, agent daemon, and test harnesses that boot the world.

| Namespace | Surface |
|-----------|---------|
| `isaac.module.loader` | `discover!`, `process-manifest-berths!`, `load-modules!`, `reconcile-modules!`, `shutdown-modules!`, `builtin-index` |
| `isaac.config.loader` | `load-config!`, `snapshot`, `root`, `env`, … |
| `isaac.config.runtime` | `install!`, `install-config-berths!`, `reconcile!`, `reload!`, `validate-config!`, change-source |
| `isaac.nexus` | Full surface including `init!`, `reset!`, `-with-nexus`, `-with-nested-nexus` |

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
typically use `nexus/register!`, `isaac.logger`, and `isaac.fs` to publish live
instances.

Manifest-only modules (EDN contributions, no Clojure requires) are the common case.

## Config

Two different “roots”:

| Concept | Namespace | When |
|---------|-----------|------|
| **Bootstrap root** | `isaac.config.root` | CLI / `main` resolves before config is loaded (`default-root`, `--root` flag) |
| **Runtime root** | `loader/root` or snapshot `:root` | After `load-config!` commits the process-wide snapshot |

Path construction helpers live in `isaac.config.paths`. Hosts load config via
`isaac.config.loader/load-config!` at entry points and thread the value onward.

### Templating — `:_base` (`isaac.config.templating`)

Any config map entity may inherit from a **template** by naming it explicitly:

```clojure
;; config/crew/_worker.edn   — a template, not a crew
{:model "grover" :tools {:allow [:fs/read :exec/run]} :tags #{:role/worker}}

;; config/crew/marvin.edn
{:_base "_worker" :soul "You are Marvin."}
```

- Inheritance is **explicit**. Nothing is inherited by proximity or by
  filename alone — an entry names its own template.
- The template must be a **sibling in the same map**: `crew/_worker` is a
  template for crews, not for models.
- **Single base, not a list.** A template may itself declare `:_base`, so
  chains work; a cycle is a load error naming the cycle, and a `:_base`
  naming no template is a load error.
- The merge is **one level**: map-valued keys merge key-wise, scalars and
  vectors replace. The entry's own keys win.
- A `_`-prefixed entry is **never addressable as a real entity**. It is
  dropped before validation and before any factory runs, so it is not a crew,
  not a signal, not a hailable band, not a selectable model. A template is
  therefore never rejected for fields a real entry would have to supply.
- `:_base` is reserved rather than `:base` precisely so it cannot collide with
  a module's legitimate `base` field.

Templating is structural, not kind-specific: it applies to every top-level
table, including keys no module declares.

#### The `_` distinction

`_` carries two meanings, told apart by **exact match**:

| Form | Meaning | Settled in |
|------|---------|-----------|
| `_` exactly | this map's own values (e.g. `config/crew/marvin/_.edn`) | isaac-49zp |
| `_<name>` | a template, inherited from and never addressable | isaac-h2ck |

Same prefix, different meaning. This is workable but not self-evident, which
is why it is written down here rather than left to be inferred.

#### Dot-entries are not config

A child of `config/` whose name starts with `.` is ignored in all three forms —
`.<key>.edn`, `.<name>.md` and `.<name>/` — at the config root and at every
depth below it. No key, no entity, no warning, no error, and no hot-reload
trigger.

Since isaac-49zp a directory *is* a key, which silently promoted an ordinary
backup (`config/crew/.removed-20260915/`) to configuration. Git, build tools and
editors all skip hidden names by default; the config tree now does too
(isaac-63ei). A name that must start with a dot has no way to be config — that
is the point.

## Reconfigurable

Config-driven components implement `isaac.reconfigurable/Reconfigurable`:

```clojure
(ns my.module.node
  (:require [isaac.reconfigurable :as reconfigurable]))

(defrecord RelayStation [state*]
  reconfigurable/Reconfigurable
  (on-startup! [_ slice]
    (reset! state* {:slice slice :event :started}))
  (on-config-change! [_ _old new-slice]
    (reset! state* {:slice new-slice :event :changed})))
```

The reconciler (`isaac.config.runtime`) invokes lifecycle methods on live nexus
instances.

## Enforcement

| Spec | What it checks |
|------|----------------|
| `spec/isaac/foundation_boundary_spec.clj` | Foundation file set never requires server/agent namespaces |
| `spec/isaac/foundation_module_boundary_spec.clj` | Module `modules/` sources require allowed components or agent surfaces only |

Factories that must call Tier-2/3 internals at runtime should use
`requiring-resolve`, not a namespace require.

## Not foundation

`isaac.api` is the **agent** public surface (sessions, comm registration, LLM
providers, bridge dispatch). Agent modules (`isaac.comm.*`, `isaac.llm.*`, …) use
`isaac.api` for platform integration.