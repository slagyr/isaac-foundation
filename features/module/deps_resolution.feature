Feature: :deps resolution via tools.deps / babashka internals
  A consumer manifest's `:deps` declares modules it requires. The
  foundation resolves them using `tools.deps`/babashka — if the
  declared module is already present in user `:modules` or cached
  in `~/.gitlibs`, no fetch. Otherwise the coordinate is resolved
  through normal `tools.deps` machinery (local-root, git, etc.).
  Resolved modules become part of the module-index just as if the
  user had declared them in `:modules`. If the coordinate can't be
  resolved, config-load errors.

  Scenarios use `:local/root` coordinates so resolution stays
  offline. The git-fetch path is covered by `tools.deps`' own
  test suite; isaac's responsibility is the delegation contract.

  Scenario: A consumer's :deps are auto-resolved and added to the module-index
    Given an empty Isaac state directory "/tmp/marigold"
    And the isaac file "/tmp/modules/marigold.bridge/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/marigold.bridge/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.bridge
       :version "1.0.0"
       :factory marigold.bridge/create-module}
      """
    And the isaac file "/tmp/modules/marigold.longwave/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/marigold.longwave/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.longwave
       :version "0.1.0"
       :factory marigold.longwave/create-module
       :deps    {:marigold.bridge {:local/root "/tmp/modules/marigold.bridge"}}}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.longwave {:local/root "/tmp/modules/marigold.longwave"}}}
      """
    When the config is loaded
    Then the loaded config has:
      | key                                             | value             |
      | /module-index/marigold.bridge/manifest/id       | marigold.bridge   |
      | /module-index/marigold.bridge/manifest/version  | 1.0.0             |
    And the config has no validation errors

  Scenario: A :deps entry whose coordinate can't be resolved is a config-load error
    Given an empty Isaac state directory "/tmp/marigold"
    And the isaac file "/tmp/modules/marigold.longwave/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/marigold.longwave/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.longwave
       :version "0.1.0"
       :factory marigold.longwave/create-module
       :deps    {:marigold.bridge {:local/root "/nonexistent/path"}}}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.longwave {:local/root "/tmp/modules/marigold.longwave"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                                                       | value                          |
      | module-index["marigold.longwave"].deps[:marigold.bridge]  | failed to resolve coordinate   |
