@wip
Feature: isaac modules show <name> — full detail for one module
  `isaac modules show <name>` prints the full coordinate (git url + sha/tag),
  version, status, source, and required-by for a single module — the detail the
  compact `modules list` table omits. --edn for structured output.

  Scenario: show prints the full coordinate
    Given Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:greeter {:git/url "https://github.com/slagyr/isaac-greeter.git" :git/sha "abc1234def"}}}
      """
    When isaac is run with "modules show greeter"
    Then the stdout contains "greeter"
    And the stdout contains "https://github.com/slagyr/isaac-greeter.git"
    And the stdout contains "abc1234def"
    And the exit code is 0

  Scenario: show --edn emits the structured detail
    Given Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:greeter {:git/url "https://github.com/slagyr/isaac-greeter.git" :git/sha "abc1234def"}}}
      """
    When isaac is run with "modules show greeter --edn"
    Then the stdout EDN contains:
      | path          | value                                          |
      | id            | :greeter                                       |
      | coord.git/url | "https://github.com/slagyr/isaac-greeter.git"  |
    And the exit code is 0

  Scenario: show of an unknown module errors
    Given Isaac root "/tmp/isaac" contains config:
      """
      {:modules {}}
      """
    When isaac is run with "modules show nope"
    Then the stderr contains "Unknown module: nope"
    And the exit code is 1
