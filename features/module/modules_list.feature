Feature: isaac modules list
  `isaac modules list` reports the module set the launcher will load: each module
  configured in :modules, with its source coordinate and shape-validity status.
  `--edn` / `--json` emit structured output (for agents, tooling, and tests);
  the default output is a colorized table.

  Scenario: A configured module is listed with its source
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge {:local/root "modules/marigold.bridge"}}}
      """
    When isaac is run with "modules list --edn"
    Then the stdout EDN contains:
      | path             | value                                   |
      | modules.0.id     | :marigold.bridge                        |
      | modules.0.coord  | {:local/root "modules/marigold.bridge"} |
      | modules.0.status | :ok                                     |
    And the exit code is 0

  Scenario: A malformed module entry is flagged, not crashed
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.broken "not-a-coordinate"}}
      """
    When isaac is run with "modules list --edn"
    Then the stdout EDN contains:
      | path             | value            |
      | modules.0.id     | :marigold.broken |
      | modules.0.status | :invalid         |
    And the exit code is 0

  # @slow: proves DYNAMIC loading — needs a subprocess step (`the isaac launcher is run
  # with …`) that shells out to the real launcher so it composes a fresh classpath; the
  # in-process `isaac is run with` cannot. Fixture marigold.cli.greeter already exists.
  @slow
  Scenario: A configured module is loaded and contributes its command
    Given an empty Isaac root at "/tmp/greeter"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.cli.greeter {:local/root "modules/marigold.cli.greeter"}}}
      """
    When the isaac launcher is run with "greet --help"
    Then the stdout contains "greet"
    And the exit code is 0
