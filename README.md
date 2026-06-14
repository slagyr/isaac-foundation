# Isaac Foundation

The seed of the Isaac platform: a CLI dispatcher, a module loader, and the
berth extension machinery — and not much else. Everything else (the server,
sessions, LLM dispatch, tools, comms) is a module that plugs in.

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
bb spec            # speclj specs
bb features        # gherclj acceptance tests
bb ci              # both
```

From the JVM, compose `:test` with a runner alias (shared test deps live in
`:test` only):

```sh
clj -M:test:spec       # speclj specs
clj -M:test:features   # gherclj acceptance tests
clj -M:test:mutate     # mutation testing
```

The spec tree is exported for consumers as
`io.github.slagyr/isaac-foundation-spec` (`:deps/root "spec"`): step
definitions, the marigold fixture world, and the marigold fixture modules.

The boundary gates (`spec/isaac/foundation_boundary_spec.clj`,
`spec/isaac/foundation_module_boundary_spec.clj`) are permanent guards that
foundation and module code stay on the documented public API.