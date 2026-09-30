Feature: Module setup
  `isaac init` belongs to foundation alone: it creates the root and a
  bare isaac.edn. Everything a module needs to get going comes from the
  module's own setup, which it opts into by contributing to foundation's
  setup berth. A setup looks at the current config and proposes writes;
  foundation applies them through the same validated, atomic path as
  `isaac config set`, skips any path that already has a value, and prints
  every write. Setup runs on `modules install` and `modules upgrade`, and
  again by hand with `isaac modules setup <id>`.

  A setup may also return hints: plain lines printed after its writes,
  for things config can't do (install a tool, pull a model).

  The marigold.setup fixture proposes marigold.greeting "ahoy" and
  marigold.chimes 3, with the hint "Polish the bell before first use."

  Background:
    Given the user home directory is "/tmp/user"

  Scenario: init creates only the root and a bare isaac.edn
    Given an empty Isaac root at "target/test-state"
    When isaac is run with "--root target/test-state init"
    Then the exit code is 0
    And the stdout lines match:
      | text                                    |
      | Isaac initialized at target/test-state. |
      |                                         |
      | Created:                                |
      |   config/isaac.edn                      |
    And the isaac file "config/isaac.edn" EDN contains:
      | path                | value           |
      | tz                  | America/Chicago |
      | prefer-entity-files | true            |
    And the isaac file "config/isaac.edn" does not contain "defaults"

  Scenario: installing a module runs its setup and prints each write
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:module-registry "registry.edn"}
      """
    And the isaac file "registry.edn" exists with:
      """
      {:marigold.setup {:coord {:local/root "modules/marigold.setup"} :desc "Setup fixture"}}
      """
    When isaac is run with "modules install marigold.setup"
    Then the exit code is 0
    And the stdout lines contain in order:
      | text                               |
      | Installed marigold.setup           |
      | Set up marigold.setup:             |
      |   marigold.greeting = "ahoy"       |
      |   marigold.chimes = 3              |
      | Polish the bell before first use.  |
    And the isaac file "config/isaac.edn" EDN contains:
      | path              | value |
      | marigold.greeting | ahoy  |
      | marigold.chimes   | 3     |

  Scenario: setup never overwrites a value that is already set
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules  {:marigold.setup {:local/root "modules/marigold.setup"}}
       :marigold {:greeting "hello"}}
      """
    When isaac is run with "modules setup marigold.setup"
    Then the exit code is 0
    And the stdout lines contain in order:
      | text                   |
      | Set up marigold.setup: |
      |   marigold.chimes = 3  |
    And the stdout does not contain "ahoy"
    And the isaac file "config/isaac.edn" EDN contains:
      | path              | value |
      | marigold.greeting | hello |
      | marigold.chimes   | 3     |

  Scenario: rerunning setup on a configured root changes nothing
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules  {:marigold.setup {:local/root "modules/marigold.setup"}}
       :marigold {:greeting "ahoy" :chimes 3}}
      """
    When isaac is run with "modules setup marigold.setup"
    Then the exit code is 0
    And the stdout contains "marigold.setup is already set up"

  Scenario: --dry-run prints the writes without making them
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.setup {:local/root "modules/marigold.setup"}}}
      """
    When isaac is run with "modules setup marigold.setup --dry-run"
    Then the exit code is 0
    And the stdout lines contain in order:
      | text                                 |
      | Would set up marigold.setup:         |
      |   marigold.greeting = "ahoy"         |
      |   marigold.chimes = 3                |
    And the isaac file "config/isaac.edn" does not contain "ahoy"

  Scenario: a module without a setup says so
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:greeter {:local/root "modules/marigold.cli.greeter"}}}
      """
    When isaac is run with "modules setup greeter"
    Then the exit code is 0
    And the stdout contains "greeter has no setup"
