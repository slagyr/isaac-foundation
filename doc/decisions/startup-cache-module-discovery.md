# Startup cache retains module discovery

**Decision date:** 2026-09-12  
**Status:** Accepted

## Context

The startup cache deliberately strips `:module-index` because the index contains process-local module discovery data. Foundation 0.1.24 restored only `:root` on a warm cache hit. Consumers such as server config validation therefore saw no module-provided implementations and rejected valid configured types.

## Decision

Keep module discovery on the warm config-load path. Do not cache the module index yet. The warm path hydrates the cached config, discovers the configured modules using the same filesystem and working-directory context as the cold path, attaches the resulting `:module-index`, and includes discovery errors in the load result.

The index depends on module manifests and resolved directories. Caching that structure safely requires a serializable representation and invalidation across every manifest and transitive module dependency. That complexity is not justified without evidence that warm discovery is the dominant startup cost.

## Measurement

Measured on the Foundation fixture graph (`marigold.bridge` plus `marigold.longwave`) with:

```sh
libexec/isaac --root /tmp/mlw3-root config validate
```

After one cold run at 4.95 seconds, nine warm process runs were 0.45–0.66 seconds (median 0.65 seconds). Warm `--version`, which bypasses config loading on a fresh startup cache, measured 0.56–0.59 seconds. The approximate 0.07-second median difference is acceptable for correctness. These wall-clock samples include process startup and were collected on the development host; they establish an order-of-magnitude floor, not a CI threshold.

## Consequences

- Warm server/config loads retain the complete module index and module-provided validation vocabulary.
- `:module-index` remains absent from the world-readable cache file.
- Warm non-fast-path commands pay module discovery on each process invocation.
- A future cached-index design requires profiling evidence plus explicit serialization and manifest/transitive-dependency invalidation rules.
