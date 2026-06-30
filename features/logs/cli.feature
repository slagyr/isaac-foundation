Feature: isaac logs — colorized log tail
  Isaac writes structured EDN logs that are hard to scan by eye. The
  'isaac logs' subcommand tails the configured log file and prints one
  colorized line per entry. The on-disk format is unchanged; the viewer
  is read-only.

  By default the subcommand reads the file once and exits, showing only
  the last 20 entries. Pass -f/--follow to keep watching after the
  initial dump. Pass --limit N to show the last N entries (--limit 0
  for all). Color is on by default; pass --no-color to disable. Zebra
  striping (alternating row background) is off by default; pass
  --zebra to enable. --plain is raw passthrough — no parsing,
  color, or zebra. The path is taken from log.file or log.output in
  config; --file overrides it. When the log file does not exist yet,
  isaac logs prints a friendly message instead of crashing.

  Background:
    Given an empty Isaac root at "target/test-logs"

  Scenario: Renders time, level, and event in fixed columns
    Given a file "app.log" exists with content "{:ts \"2026-05-12T15:24:51.491Z\", :level :info, :event :server/started, :port 8080}"
    When isaac is run with "logs --file app.log --no-color"
    Then the stdout matches:
      | pattern                                                            |
      | \d{2}:\d{2}:\d{2}\.\d{3}  INFO   :server/started  \{:port 8080\}  |

  Scenario: Trailing payload renders as a Clojure map literal
    Given a file "app.log" exists with content "{:ts \"2026-05-12T15:24:51Z\", :level :info, :event :acp-ws/opened, :client \"192.168.1.10\", :uri \"/acp\"}"
    When isaac is run with "logs --file app.log --no-color"
    Then the stdout contains "{:client \"192.168.1.10\" :uri \"/acp\"}"
    And the stdout does not contain "client="

  Scenario: Level column is fixed-width across severities
    Given a file "app.log" exists with content:
      """
      {:ts "2026-05-12T15:24:51Z", :level :info,  :event :a}
      {:ts "2026-05-12T15:24:52Z", :level :error, :event :b}
      {:ts "2026-05-12T15:24:53Z", :level :warn,  :event :c}
      {:ts "2026-05-12T15:24:54Z", :level :debug, :event :d}
      {:ts "2026-05-12T15:24:55Z", :level :trace, :event :e}
      """
    When isaac is run with "logs --file app.log --no-color"
    Then the stdout matches:
      | pattern        |
      | INFO   :a      |
      | ERROR  :b      |
      | WARN   :c      |
      | DEBUG  :d      |
      | TRACE  :e      |

  Scenario: :file and :line are dropped from the inline display
    Given a file "app.log" exists with content "{:ts \"2026-05-12T15:24:51Z\", :level :info, :event :hello, :file \"src/x.clj\", :line 42}"
    When isaac is run with "logs --file app.log --no-color"
    Then the stdout does not contain ":file"
    And the stdout does not contain ":line"
    And the stdout does not contain "src/x.clj"

  Scenario: Unparseable lines pass through as raw text
    Given a file "app.log" exists with content "this is not edn"
    When isaac is run with "logs --file app.log --no-color"
    Then the stdout contains "this is not edn"

  Scenario: Prints a friendly message when the log file does not exist
    When isaac is run with "logs --file missing.log --no-color"
    Then the stdout contains "No log file at"
    And the stdout contains "missing.log"
    And the exit code is 0

  Scenario: Default limit caps history to the last 20 entries
    Given a file "app.log" exists with 25 log entries
    When isaac is run with "logs --file app.log --no-color"
    Then the stdout does not contain ":e01"
    And the stdout does not contain ":e05"
    And the stdout contains ":e06"
    And the stdout contains ":e25"

  Scenario: --limit N shows only the last N entries
    Given a file "app.log" exists with 25 log entries
    When isaac is run with "logs --file app.log --no-color --limit 3"
    Then the stdout does not contain ":e22"
    And the stdout contains ":e23"
    And the stdout contains ":e24"
    And the stdout contains ":e25"

  Scenario: --limit 0 shows every entry
    Given a file "app.log" exists with 25 log entries
    When isaac is run with "logs --file app.log --no-color --limit 0"
    Then the stdout contains ":e01"
    And the stdout contains ":e25"

  Scenario: With --follow, picks up entries appended after startup
    Given a file "app.log" exists with content "{:ts \"2026-05-12T15:24:51Z\", :level :info, :event :first}"
    When isaac is run in the background with "logs --file app.log --follow --no-color"
    And the stdout eventually contains ":first"
    And the file "app.log" is appended with "{:ts \"2026-05-12T15:24:52Z\", :level :info, :event :second}"
    Then the stdout eventually contains ":second"

  Scenario: Without --follow, exits after the initial read
    Given a file "app.log" exists with content "{:ts \"2026-05-12T15:24:51Z\", :level :info, :event :one-shot}"
    When isaac is run with "logs --file app.log --no-color"
    Then the stdout contains ":one-shot"
    And the exit code is 0

  Scenario: --no-color strips all ANSI escapes
    Given a file "app.log" exists with content "{:ts \"2026-05-12T15:24:51Z\", :level :info, :event :plainish}"
    When isaac is run with "logs --file app.log --no-color"
    Then the stdout contains ":plainish"
    And the stdout does not contain "["

  Scenario: --zebra enables row striping when color is on
    Given a file "app.log" exists with content:
      """
      {:ts "2026-05-12T15:24:51Z", :level :info, :event :a}
      {:ts "2026-05-12T15:24:52Z", :level :info, :event :b}
      """
    When isaac is run with "logs --file app.log --zebra"
    Then the stdout matches:
      | pattern    |
      | \x1B\[2m\x1B\[2m |

  Scenario: Without --zebra, no row striping by default
    Given a file "app.log" exists with content:
      """
      {:ts "2026-05-12T15:24:51Z", :level :info, :event :a}
      {:ts "2026-05-12T15:24:52Z", :level :info, :event :b}
      """
    When isaac is run with "logs --file app.log"
    Then the stdout does not contain "48;5;"

  Scenario: --plain echoes the original EDN lines verbatim
    Given a file "app.log" exists with content:
      """
      {:ts "2026-05-12T15:24:51Z", :level :info, :event :foo, :port 8080}
      not edn at all
      """
    When isaac is run with "logs --file app.log --plain"
    Then the stdout contains "{:ts \"2026-05-12T15:24:51Z\", :level :info, :event :foo, :port 8080}"
    And the stdout contains "not edn at all"
    And the stdout does not contain "["
