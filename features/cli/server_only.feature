@wip
Feature: Server-only commands are handed to a forwarder
  A module command declared :server-only in its :isaac/cli entry does not make
  sense in a process of its own: it must run inside the Isaac server. When a
  forwarder is registered on the :isaac/cli-forwarder berth, the CLI hands it
  the full argv and the root, and returns the forwarder's exit code. A
  forwarder that finds no server answers no-server, and the command then runs
  locally as before. Commands without the flag never reach the forwarder, and
  a command already running inside the server (embedded dispatch) is never
  forwarded again.

  The fixture forwarder prints "forwarded: <argv>" and answers from the root
  file forwarder.edn: {:exit <n>} (default 0) or {:result :no-server}. The
  fixture command "where" prints "process" when it runs in its own process.

  Tracked by isaac-dcxk.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: a server-only command is handed to the forwarder with its argv
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.cli.where     {:local/root "modules/marigold.cli.where"}
                 :marigold.cli.forwarder {:local/root "modules/marigold.cli.forwarder"}}}
      """
    When isaac is run with "where --tag tide"
    Then the stdout contains "forwarded: where --tag tide"
    And the stdout does not contain "process"
    And the exit code is 0

  Scenario: the forwarder's exit code is the command's exit code
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.cli.where     {:local/root "modules/marigold.cli.where"}
                 :marigold.cli.forwarder {:local/root "modules/marigold.cli.forwarder"}}}
      """
    And the isaac file "forwarder.edn" exists with:
      """
      {:exit 3}
      """
    When isaac is run with "where"
    Then the stdout contains "forwarded: where"
    And the exit code is 3

  Scenario: when the forwarder finds no server, the command runs locally
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.cli.where     {:local/root "modules/marigold.cli.where"}
                 :marigold.cli.forwarder {:local/root "modules/marigold.cli.forwarder"}}}
      """
    And the isaac file "forwarder.edn" exists with:
      """
      {:result :no-server}
      """
    When isaac is run with "where"
    Then the stdout contains "process"
    And the exit code is 0

  Scenario: with no forwarder registered, a server-only command runs locally
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.cli.where {:local/root "modules/marigold.cli.where"}}}
      """
    When isaac is run with "where"
    Then the stdout contains "process"
    And the exit code is 0

  Scenario: a command without the flag never reaches the forwarder
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.cli.greeter   {:local/root "modules/marigold.cli.greeter"}
                 :marigold.cli.forwarder {:local/root "modules/marigold.cli.forwarder"}}}
      """
    When isaac is run with "greet"
    Then the stdout contains "Hello from the greeter module!"
    And the stdout does not contain "forwarded"
    And the exit code is 0
