Feature: Server-only commands run only inside the server
  A module command declared :server-only in its :isaac/cli entry must run
  inside the Isaac server. The local CLI never runs it: local help lists it in
  its own section, and typing it refuses with the way to reach it, through
  `isaac remote <name> -- <command>`. Inside the server (embedded dispatch, the
  path `isaac remote` uses) it is a plain command. There is no forwarder.

  The fixture command "where" is :server-only and prints "process" when it
  runs; "greet" is an ordinary command.

  Tracked by isaac-crrm (replaces the isaac-dcxk forwarder).

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.cli.where   {:local/root "modules/marigold.cli.where"}
                 :marigold.cli.greeter {:local/root "modules/marigold.cli.greeter"}}}
      """

  @wip
  Scenario: local help lists server-only commands in their own section (isaac-crrm)
    When isaac is run with "--help"
    Then the stdout contains "Server-only (run on a server: isaac remote <name> -- <command>):"
    And the stdout contains "where"
    And the stdout contains "Print the current process"
    And the exit code is 0

  @wip
  Scenario: a server-only command typed locally refuses with the remote hint (isaac-crrm)
    When isaac is run with "where --tag tide"
    Then the stderr contains "where runs on the server: isaac remote <name> -- where"
    And the stdout does not contain "process"
    And the exit code is 2

  Scenario: an ordinary command still runs locally (isaac-crrm)
    When isaac is run with "greet"
    Then the stdout contains "Hello from the greeter module!"
    And the exit code is 0
