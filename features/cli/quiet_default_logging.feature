Feature: The CLI never prints structured log entries by default (isaac-89q1)

  Every `isaac` command used to print raw `{:ts ... :level :warn ...}` log
  maps to the terminal ahead of its own output — the config loader logs an
  unknown-key or unresolved-${VAR} warning before the CLI's own log sink is
  installed, so the logger's default (terminal) sink caught it. Structured
  logs are the CLI's own business, not a command's terminal output: they
  always land in logs/cli.log, never stdout/stderr, unless the operator
  explicitly opts in with --log-level or --log-file.

  Background:
    Given an empty Isaac root at "/tmp/isaac-89q1"
    And the isaac file "isaac.edn" exists with:
      """
      {:bogus-top-level-key 1}
      """

  Scenario: A non-config command never prints a log entry, even when config has an unknown key
    Given config:
      | log.output | stderr |
    When isaac is run with "modules list"
    Then the exit code is 0
    And the stderr is empty
    And the CLI log file contains an event "config/unknown-key"

  Scenario: isaac config validate reports the same finding as a human warning line, not EDN
    When isaac is run with "config validate"
    Then the stderr contains "warning: :bogus-top-level-key - unknown key"
    And the stderr does not contain ":level"
    And the stderr does not contain ":event"

  Scenario: An explicit --log-level opts back into seeing logs live on the terminal
    Given config:
      | log.output | stderr |
    When isaac is run with "modules list --log-level warn"
    Then the exit code is 0
    And the stderr contains "config/unknown-key"
