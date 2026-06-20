# Releases

## Tag policy

Version tags (`v0.1.0`, `v0.1.1`, …) are **immutable** once published. Never
force-move a tag that users or downstream repos may already depend on. Ship
changes as the next patch (`v0.1.2`, `v0.1.3`, …).

Consumers pin `:git/tag` + `:git/sha` in `deps.edn`. The Homebrew formula in
[slagyr/homebrew-tap](https://github.com/slagyr/homebrew-tap) pins the release
tarball `url` + `sha256` — moving a tag breaks installs.

## Cutting a release

1. Ensure `main` is green (`bb ci`).
2. Tag the commit (new tag only — do not move an existing one):

   ```sh
   git tag v0.1.2
   git push origin v0.1.2
   ```

3. Bump `:version` in `src/isaac-manifest.edn` to match the tag before tagging.

4. Run the **Release** workflow (`workflow_dispatch`) with that tag. It:
   - Publishes a GitHub Release for the tag
   - Dispatches to `slagyr/homebrew-tap` to auto-bump `Formula/isaac.rb`

The tap bump requires `HOMEBREW_TAP_BUMP_TOKEN` (PAT with `repo` scope on
homebrew-tap) in this repo's secrets.

## Releasing a module

Modules (agent, server, acp, cron, hail, hooks, discord, imessage) are resolved
from the registry **by SHA**, so releasing one is:

1. Bump the module's `:version` in its `isaac-manifest.edn` (this is what
   `isaac modules list` shows).
2. **Sync its inter-module `deps.edn` pins to the registry's current versions.**
   A module's transitive isaac-deps come from its *own* `deps.edn`, not the
   registry — so if you don't refresh them, a fix released in a dependency
   (e.g. a new `isaac.agent`) will **not** reach users via `isaac modules
   upgrade`. Bump the module's `isaac-*` pins (agent/server/…) to the coords in
   `modules.edn` before releasing.
3. Bump that module's `:git/sha` (→ the new commit) in the registry
   `modules.edn`.
4. A git tag (`vX.Y.Z`) is optional — nothing resolves it (the registry is
   SHA-based); tag deliberate releases as a human-readable marker if you like.

Foundation is the exception — it **must** be tagged (the Homebrew formula's
tarball URL points at the tag); see "Cutting a release" above.

### A consumer stuck on an old transitive module

Because transitive versions follow their parent's `deps.edn` pin, a consumer can
sit on an old dependency even after the registry moves on — until the parent
re-releases (step 2 above). To pull the registry's current version right away,
pin it explicitly:

```sh
isaac modules install <name>   # e.g. isaac modules install isaac.agent
```

## Homebrew

```sh
brew install slagyr/tap/isaac        # latest released tag
brew install --HEAD slagyr/tap/isaac # main branch
```