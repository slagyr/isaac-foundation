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

## Homebrew

```sh
brew install slagyr/tap/isaac        # latest released tag
brew install --HEAD slagyr/tap/isaac # main branch
```