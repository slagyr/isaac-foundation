Feature: CLI
  Isaac provides a command-line interface with discoverable
  commands and built-in help.

  Scenario: No arguments shows usage
    When isaac is run with ""
    Then the stdout contains "Usage: isaac [options] <command> [args]"
    And the stdout contains "Commands:"
    And the exit code is 0

  Scenario: Unknown command shows error and usage
    When isaac is run with "bogus"
    Then the stdout contains "Unknown command: bogus"
    And the stdout contains "Usage: isaac [options] <command> [args]"
    And the exit code is 1

  Scenario: Help for an unknown command
    When isaac is run with "help bogus"
    Then the stdout contains "Unknown command: bogus"
    And the exit code is 1

  Scenario: Top-level --help flag shows usage
    When isaac is run with "--help"
    Then the stdout contains "Usage: isaac [options] <command> [args]"
    And the stdout contains "Commands:"
    And the exit code is 0

  Scenario: Top-level -h flag shows usage
    When isaac is run with "-h"
    Then the stdout contains "Usage: isaac [options] <command> [args]"
    And the stdout contains "Commands:"
    And the exit code is 0

  Scenario: Top-level usage omits root pointer precedence
    When isaac is run with "--help"
    Then the stdout does not contain "May also be set"
    And the stdout contains "--root <dir>    Isaac root directory (default: ~/.isaac)"

  Scenario: Config sources documents root resolution precedence
    When isaac is run with "config sources"
    Then the stdout contains "--root"
    And the stdout contains "ISAAC_ROOT"
    And the stdout contains "~/.config/isaac.edn"
    And the stdout contains "~/.isaac.edn"
    And the stdout contains "~/.isaac"
    And the exit code is 0
