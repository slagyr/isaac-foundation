Feature: Module-contributed CLI subcommands
  A module that declares an `:isaac/cli` extension in its manifest can register
  new `isaac <name>` subcommands.  The early discovery pass in main.clj
  runs before command dispatch so the command is visible at invocation time.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: module-contributed command is dispatched
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:isaac.cli.greeter {:local/root "modules/isaac.cli.greeter"}}}
      """
    When isaac is run with "greet"
    Then the stdout contains "Hello from the greeter module!"
    And the exit code is 0

  Scenario: module-contributed command appears in help output
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:isaac.cli.greeter {:local/root "modules/isaac.cli.greeter"}}}
      """
    When isaac is run with ""
    Then the stdout contains "greet"
    And the exit code is 0

  Scenario: no modules configured — unknown command still errors
    When isaac is run with "greet"
    Then the stdout contains "Unknown command: greet"
    And the exit code is 1

  Scenario: module-contributed command has its own --help page
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:isaac.cli.greeter {:local/root "modules/isaac.cli.greeter"}}}
      """
    When isaac is run with "greet --help"
    Then the stdout matches:
      | pattern                          |
      | Usage: isaac greet               |
      | Print a greeting                 |
    And the exit code is 0

  Scenario: isaac help <module-cmd> reaches the same page
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:isaac.cli.greeter {:local/root "modules/isaac.cli.greeter"}}}
      """
    When isaac is run with "help greet"
    Then the stdout matches:
      | pattern                          |
      | Usage: isaac greet               |
      | Print a greeting                 |
    And the exit code is 0
