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

The foundation ships exactly one command (`init`) and one built-in berth
(`:cli`). Modules declare their own berths and contribute to others' via
`isaac-manifest.edn` — the platform uses the same extension API that
third-party modules do.

## What's here

- `isaac.main` / `isaac.cli.registry` — CLI dispatch; commands arrive as `:cli`
  berth contributions from module manifests.
- `isaac.module.*` — module discovery (tools.deps coordinates in user
  config), manifest reading/validation, berth processing.
- `isaac.config.*` — config loading, schema composition
  (`:isaac.config/schema` berth), validation checks (`:isaac.config/check`
  berth), entity files, companions.
- `isaac.schema.*` — schema runtime: lexicon extensions, dynamic schema,
  `[:registered-in? ...]` validation.
- Shared utilities: `isaac.fs`, `isaac.logger`, `isaac.system`,
  `isaac.nexus`, `isaac.scheduler`, `isaac.shell`, `isaac.root`.

## Development

Babashka-first. Run things with:

```sh
bb isaac --help    # the CLI
bb spec            # speclj specs
bb features        # gherclj acceptance tests
bb ci              # both
```

The spec tree is exported for consumers as
`io.github.slagyr/isaac-foundation-spec` (`:deps/root "spec"`): step
definitions, the marigold fixture world, and the marigold fixture modules.

The boundary gate (`spec/isaac/foundation_boundary_spec.clj`) is the
permanent guard that no foundation namespace requires server-side code.
