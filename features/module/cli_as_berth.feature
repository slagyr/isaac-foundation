Feature: :cli declared as a berth (phase 4 of the berth epic)
  Phase 4 of isaac-brth: re-declare `:cli` as a normal berth so the
  same mechanism third-party modules use can host isaac's own
  built-in commands. Proves the loop end-to-end — the foundation
  isn't privileged; it uses the public extension API.

  CLI dispatch precedes server boot, so the foundation processes the
  `:cli` berth BEFORE running Module/on-startup hooks. CLI handlers
  are stateless registrations; lifecycle isn't required to invoke
  them.

  Scenario: A module-declared CLI command dispatches through the :cli berth
    Given an empty Isaac state directory "/tmp/marigold"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "spec/marigold/bridge"}
                 :marigold.longwave {:local/root "spec/marigold/longwave"}}}
      """
    When isaac is run with "longwave-ping"
    Then the stdout contains "pong"
    And the exit code is 0

  Scenario: A :cli berth command resolves symbol-valued :subcommands into its help
    Given an empty Isaac state directory "/tmp/greeter"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:isaac.cli.greeter {:local/root "modules/isaac.cli.greeter"}}}
      """
    When isaac is run with "greet --help"
    Then the stdout contains "Subcommands:"
    And the stdout contains "wave"
    And the stdout contains "Take a bow"
    And the exit code is 0
