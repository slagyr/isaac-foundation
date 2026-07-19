# 🍏 Isaac Foundation 🪨

<img align="left" width="200" src="https://raw.githubusercontent.com/slagyr/isaac-foundation/main/isaac-foundation.png" alt="isaac-foundation" style="margin-right: 20px; margin-bottom: 10px;">

The seed of the Isaac platform: the CLI dispatcher, config management, module loader, and the
berth extension machinery — and not much else. Everything else (the server,
sessions, LLM dispatch, tools, comms) is a module that plugs in.

<br>

[![Foundation](https://github.com/slagyr/isaac-foundation/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-foundation/actions/workflows/ci-tests.yml) 
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

<br clear="left">

Install the foundation, then grow it by adding modules to your config:

```sh
isaac init
# then add modules to <root>/config/isaac.edn:
#   {:modules {:isaac.server {:git/url "..." :git/sha "..."}}}
```

The foundation ships exactly one command (`init`) and built-in berths
(`:isaac/cli`, `:isaac.config/schema`, `:isaac.config/check`). Modules declare
their own berths and contribute to others' via `isaac-manifest.edn` — the
platform uses the same extension API that third-party modules do.

## What's here

- `isaac.main` / `isaac.cli.registry` — CLI dispatch; commands arrive as `:isaac/cli`
  berth contributions from module manifests.
- `isaac.foundation` — Tier-1 public API facade for module authors (see
  [FOUNDATION.md](FOUNDATION.md)).
- `isaac.module.*` — module discovery (tools.deps coordinates in user
  config), manifest reading/validation, berth processing.
- `isaac.config.*` — config loading, schema composition
  (`:isaac.config/schema` berth), validation checks (`:isaac.config/check`
  berth), entity files, companions.
- `isaac.schema.*` — schema runtime: lexicon extensions, dynamic schema,
  `[:registered-in? ...]` validation.
- Shared utilities: `isaac.fs`, `isaac.logger`, `isaac.nexus`,
  `isaac.scheduler.runtime`, `isaac.shell`, `isaac.config.root`.

## Development

Babashka-first. Run things with:

```sh
bb isaac --help    # the CLI
bb spec            # speclj specs (60s timeout)
bb features        # gherclj acceptance tests (60s timeout; skips @slow, @wip)
bb features-slow   # launcher/subprocess @slow features only (60s timeout)
bb ci              # spec + fast features (60s timeout)
```

From the JVM, compose `:test` with a runner alias (shared test deps live in
`:test` only). Prefer the `bb jvm-*` tasks — they enforce the same 60s
timeout as `bb spec` / `bb features`:

```sh
bb jvm-spec            # clj -M:test:spec
bb jvm-features        # clj -M:test:features
clj -M:test:mutate     # mutation testing (no timeout; not part of ci)
```

The spec tree is exported for consumers as
`io.github.slagyr/isaac-foundation-spec` (`:deps/root "spec"`): step
definitions, the marigold fixture world, and the marigold fixture modules.

Consumers should depend on a tagged release (not `:local/root`). Published tags
are immutable — see [RELEASE.md](RELEASE.md).

```clojure
io.github.slagyr/isaac-foundation
{:git/url "https://github.com/slagyr/isaac-foundation.git"
 :git/tag "v0.1.1"
 :git/sha "36e4a6f10a02b86008eb81aaa20b057387bb4c7a"}
```

Use the `:dev-local` alias in sibling repos to override back to
`../isaac-foundation` while developing in the monorepo.

The boundary gates (`spec/isaac/foundation_boundary_spec.clj`,
`spec/isaac/foundation_module_boundary_spec.clj`) are permanent guards that
foundation and module code stay on the documented public API.
