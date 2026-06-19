Feature: isaac launcher — exactly one version of any module (92p3)
  The composed classpath must never contain two versions of the same module
  (or foundation). Resolution is a single unified tools.deps basis (one version
  per lib, deterministic); foundation is pinned seed-authoritative; the resolved
  tree carries each module's :version so conflicts are observable.

  # Fixtures (must really load):
  #   marigold.needs-fdn — deps.edn pulls marigold.fixture.foundation.v999 manifest
  #                        {:id :isaac.foundation :version "9.9.9"}
  #   marigold.app  / marigold.app2 — deps.edn each pull marigold.shared, at
  #                        DIFFERENT versions (1.0.0 vs 9.9.9)
  #   marigold.shared — a module shipping a manifest with a :version
  #   marigold.cli.greeter — existing; ships a manifest contributing `greet`

  @slow
  Scenario: A module's transitive foundation never shadows the seed
    Given Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.needs-fdn {:local/root "modules/marigold.needs-fdn"}}}
      """
    When the isaac launcher is run with "--version"
    Then the stdout does not contain "9.9.9"
    And the exit code is 0

  @slow
  Scenario: A module required at two versions loads as exactly one
    Given Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app.conflict  {:local/root "modules/marigold.app.conflict"}
                 :marigold.app2.conflict {:local/root "modules/marigold.app2.conflict"}}}
      """
    # app's deps.edn pulls marigold.shared 1.0.0; app2's pulls 9.9.9 — same id,
    # two versions. Fixtures are pinned so resolution deterministically keeps 1.0.0.
    When isaac is run with "modules list --edn"
    Then the stdout EDN contains:
      | path              | value            |
      | modules.2.id      | :marigold.shared |
      | modules.2.version | "1.0.0"          |
    And the stdout does not contain "9.9.9"
    And the exit code is 0

  Scenario: A module that is both installed and required appears once
    Given Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app         {:local/root "modules/marigold.app"}
                 :marigold.cli.greeter {:local/root "modules/marigold.cli.greeter"}}}
      """
    # marigold.app's deps.edn ALSO requires marigold.cli.greeter — so greeter is
    # both explicit and transitive. It must appear ONCE (explicit wins).
    When isaac is run with "modules list --edn"
    Then the stdout EDN contains:
      | path                  | value                 |
      | modules.0.id          | :marigold.app         |
      | modules.1.id          | :marigold.cli.greeter |
      | modules.1.required-by | []                    |
    # exactly two entries — greeter is NOT duplicated as a third implied row.
    And the isaac modules list has 2 entries
    And the exit code is 0
