Feature: isaac modules — transitive module dependencies (deps.edn-native)
  A module declares its dependencies in its own deps.edn (ordinary tools.deps
  coords). Loading a module pulls and activates the modules it depends on that
  ship an isaac-manifest.edn; a dep WITHOUT a manifest stays a plain library.
  config :modules holds only explicitly-installed modules; `modules list` shows
  the resolved tree, marking implied modules with REQUIRED BY.

  # Fixtures these need (must really load):
  #   marigold.app   — deps.edn depends on marigold.cli.greeter (+ marigold.util)
  #   marigold.app2  — deps.edn depends on marigold.cli.greeter
  #   marigold.util  — plain code, NO isaac-manifest.edn (a library, not a module)
  #   marigold.cli.greeter — existing; ships a manifest contributing `greet`

  @slow
  Scenario: A dependency module's contributions activate transitively
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app {:local/root "modules/marigold.app"}}}
      """
    # marigold.app's deps.edn depends on marigold.cli.greeter; greeter is NOT in :modules.
    When the isaac launcher is run with "greet --help"
    Then the stdout contains "greet"
    And the exit code is 0

  Scenario: A plain library dependency is not treated as a module
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app {:local/root "modules/marigold.app"}}}
      """
    # marigold.app's deps.edn depends on BOTH marigold.cli.greeter (manifest)
    # and marigold.util (plain code, no isaac-manifest.edn).
    When isaac is run with "modules list --edn"
    Then the stdout EDN contains:
      | path         | value                 |
      | modules.0.id | :marigold.app         |
      | modules.1.id | :marigold.cli.greeter |
    And the stdout does not contain "marigold.util"
    And the exit code is 0

  Scenario: list --edn reports provenance — implied modules carry :required-by
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app {:local/root "modules/marigold.app"}}}
      """
    # marigold.app's deps.edn depends on marigold.cli.greeter.
    When isaac is run with "modules list --edn"
    Then the stdout EDN contains:
      | path                  | value                 |
      | modules.0.id          | :marigold.app         |
      | modules.0.required-by | []                    |
      | modules.1.id          | :marigold.cli.greeter |
      | modules.1.required-by | [:marigold.app]       |
    And the stdout contains "modules/marigold.cli.greeter"
    And the exit code is 0

  Scenario: A module required by several installed modules lists all requirers
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app  {:local/root "modules/marigold.app"}
                 :marigold.app2 {:local/root "modules/marigold.app2"}}}
      """
    # Both marigold.app and marigold.app2 depend (deps.edn) on marigold.cli.greeter.
    When isaac is run with "modules list --edn"
    Then the stdout EDN contains:
      | path                  | value                          |
      | modules.2.id          | :marigold.cli.greeter          |
      | modules.2.required-by | [:marigold.app :marigold.app2] |
    And the exit code is 0

  Scenario: The list table renders a REQUIRED BY column
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app {:local/root "modules/marigold.app"}}}
      """
    # marigold.app's deps.edn depends on marigold.cli.greeter.
    When isaac is run with "modules list"
    Then the stdout contains "REQUIRED BY"
    And the exit code is 0
