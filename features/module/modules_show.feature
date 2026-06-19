Feature: isaac modules show <name> — full detail for one module
  `isaac modules show <name>` prints the full coordinate (git url + sha/tag),
  version, status, source, and required-by for a single module — the detail the
  compact `modules list` table omits. --edn for structured output.

  @slow
  Scenario: show prints the full coordinate
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:isaac.comm.acp {:git/url "https://github.com/slagyr/isaac-acp.git"
                                  :git/sha "f8e149930c434d82c488570d88304220174a6c14"}}}
      """
    When isaac is run with "modules show isaac.comm.acp"
    Then the stdout contains "https://github.com/slagyr/isaac-acp.git"
    And the stdout contains "f8e149930c434d82c488570d88304220174a6c14"
    And the exit code is 0

  @slow
  Scenario: show --edn emits the structured detail
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:isaac.comm.acp {:git/url "https://github.com/slagyr/isaac-acp.git"
                                  :git/sha "f8e149930c434d82c488570d88304220174a6c14"}}}
      """
    When isaac is run with "modules show isaac.comm.acp --edn"
    Then the stdout EDN contains:
      | path          | value                                         |
      | id            | :isaac.comm.acp                               |
      | coord.git/url | "https://github.com/slagyr/isaac-acp.git"     |
      | coord.git/sha | "f8e149930c434d82c488570d88304220174a6c14"   |
    And the exit code is 0

  Scenario: show of an unknown module errors
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge {:local/root "modules/marigold.bridge"}}}
      """
    When isaac is run with "modules show nope"
    Then the stderr contains "Unknown module: nope"
    And the exit code is 1