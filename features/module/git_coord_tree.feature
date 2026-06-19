Feature: isaac modules list — transitive discovery for git coordinates (90df)
  Transitive module discovery reads each module's deps.edn from the tools.deps
  materialized path (~/.gitlibs for git coords). :local/root marigold fixtures
  exercised 0yp1/yi82 in-process; this @slow suite proves the same tree and
  conflict surfacing on real git coordinates.

  # Foundation fixture modules (marigold.*) are addressable as git coords with
  # :deps/root so we need not publish separate fixture repos.

  @slow
  Scenario: a git-coord module surfaces transitive modules with REQUIRED BY
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:isaac.comm.acp {:git/url "https://github.com/slagyr/isaac-acp.git"
                                  :git/sha "f8e149930c434d82c488570d88304220174a6c14"}}}
      """
    When isaac is run with "modules list --edn"
    Then the stdout EDN contains:
      | path                  | value            |
      | modules.0.id          | :isaac.comm.acp  |
      | modules.1.id          | :isaac.agent     |
      | modules.1.coord.git/url | "https://github.com/slagyr/isaac-agent.git" |
      | modules.1.required-by | [:isaac.comm.acp] |
    And the exit code is 0

  @slow
  Scenario: git-coord version conflicts surface in the conflicts table
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app.conflict  {:git/url "https://github.com/slagyr/isaac-foundation.git"
                                          :git/sha "305c337a69427cebbd2ae5471ac6684f0ae321a5"
                                          :deps/root "modules/marigold.app.conflict"}
                 :marigold.app2.conflict {:git/url "https://github.com/slagyr/isaac-foundation.git"
                                          :git/sha "305c337a69427cebbd2ae5471ac6684f0ae321a5"
                                          :deps/root "modules/marigold.app2.conflict"}}}
      """
    When isaac is run with "modules list --edn"
    Then the stdout EDN contains:
      | path               | value            |
      | conflicts.0.id     | :marigold.shared |
      | conflicts.0.chosen | "1.0.0"          |
    And the exit code is 0