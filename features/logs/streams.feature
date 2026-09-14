Feature: isaac logs — stream discovery and selection
  `isaac logs` views any registered log stream. Streams are declared by
  modules via the :isaac/log-stream berth (name -> {:file, :description}).
  Foundation contributes :cli (logs/cli.log) and :server (logs/server.log) —
  `isaac logs server` does not require isaac.http. Other modules may add
  streams. There is no default stream: with no name, the command lists what's
  available and the user picks.

  Registration is load-time discovery and is decoupled from writing — a stream
  is listable because its module declares it, whether or not its file exists
  yet or its writer is active in this process. -f / -n / --no-color /
  formatting apply to whichever stream is selected.

  Background:
    Given an empty Isaac root at "target/test-logs"
    And the registered log streams:
      | name   | file            | description      |
      | cli    | logs/cli.log    | CLI command logs |
      | server | logs/server.log | HTTP server logs |

  Scenario Outline: With no stream selected, the registered streams are listed
    When isaac is run with "<command>"
    Then the stdout contains "cli"
    And the stdout contains "logs/cli.log"
    And the stdout contains "server"
    And the stdout contains "logs/server.log"

    Examples:
      | command     |
      | logs        |
      | logs --list |

  Scenario: A named stream tails that stream's file
    Given a file "logs/server.log" exists with content "{:ts \"2026-05-12T15:24:51Z\", :level :info, :event :server/started, :port 6674}"
    When isaac is run with "logs server --no-color"
    Then the stdout contains ":server/started"
    And the stdout contains "{:port 6674}"

  Scenario: Limit and formatting flags apply to the selected stream
    Given a file "logs/server.log" exists with content:
      """
      {:ts "2026-05-12T15:24:51Z", :level :info,  :event :a}
      {:ts "2026-05-12T15:24:52Z", :level :error, :event :b}
      """
    When isaac is run with "logs server --no-color --limit 1"
    Then the stdout contains ":b"
    And the stdout does not contain ":a"

  Scenario: A named stream with no file yet prints a friendly message
    When isaac is run with "logs server --no-color"
    Then the stdout does not contain "Exception"

  Scenario: Unknown stream name reports it and lists the available streams
    When isaac is run with "logs nope"
    Then the stdout contains "nope"
    And the stdout contains "cli"
    And the stdout contains "server"
