Feature: isaac modules deps — emit JVM launch deps/classpath from config
  `isaac modules deps` derives the dependency set needed to launch isaac on the
  JVM from the current root's config :modules — foundation itself, as a
  :local/root dependency on its own directory, plus every resolved module
  coordinate, each carrying the seed-authoritative
  exclusion (io.github.slagyr/isaac-foundation) so the packaged foundation stays
  the single copy. Nothing is materialized: the set is regenerated from config
  each call, mirroring the coords compose-config-modules! adds to bb's classpath.

    --edn        (default) the -Sdeps map; launch with
                 clojure -Sdeps "$(isaac modules deps --edn)" -M -m isaac.foundation.main server
    --classpath  the flattened classpath (shells clojure -Spath); debug / java -cp

  # Fixtures (real local/root modules in this repo):
  #   marigold.app          — deps.edn depends on marigold.cli.greeter + marigold.util
  #   marigold.cli.greeter  — ships an isaac-manifest.edn (a module)
  #   marigold.util         — plain code, NO manifest (a library, not a module)

  Scenario: --edn emits a -Sdeps map with seed-authoritative exclusions
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app {:local/root "modules/marigold.app"}}}
      """
    When isaac is run with "modules deps --edn"
    Then the stdout contains ":deps"
    And the stdout contains "marigold.app"
    # marigold.cli.greeter is pulled transitively via marigold.app's deps.edn
    And the stdout contains "marigold.cli.greeter"
    And the stdout contains ":exclusions"
    And the stdout contains "io.github.slagyr/isaac-foundation"
    # marigold.util has no manifest — a plain library, never a module dep here
    And the stdout does not contain "marigold.util"
    And the exit code is 0

  # Foundation goes in as a dependency on its own directory, not as a bare
  # source path: the clojure CLI then reads foundation's deps.edn for its
  # source AND its libraries, and a source path outside the project — which
  # the CLI warns is deprecated on every launch — never appears.
  @wip
  Scenario: --edn names foundation as a local dependency
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {}}
      """
    When isaac is run with "modules deps --edn"
    Then the stdout contains "io.github.slagyr/isaac-foundation"
    And the stdout contains ":local/root"
    And the exit code is 0

  Scenario: --edn is the default when no flag is given
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app {:local/root "modules/marigold.app"}}}
      """
    When isaac is run with "modules deps"
    Then the stdout contains ":deps"
    And the exit code is 0

  Scenario: --classpath errors clearly when the clojure CLI is absent
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app {:local/root "modules/marigold.app"}}}
      """
    And the command "clojure" is not available
    When isaac is run with "modules deps --classpath"
    Then the stderr contains "requires the clojure CLI"
    And the exit code is 1

  Scenario: --edn and --classpath together is an error
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app {:local/root "modules/marigold.app"}}}
      """
    When isaac is run with "modules deps --edn --classpath"
    Then the stderr contains "choose one of --edn or --classpath"
    And the exit code is 1

  @slow
  Scenario: --classpath emits a launchable classpath resolved by clojure
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app {:local/root "modules/marigold.app"}}}
      """
    When isaac is run with "modules deps --classpath"
    Then the stdout contains "/src"
    And the stdout contains "modules/marigold.app"
    And the stdout contains "org/clojure/clojure"
    And the exit code is 0

  @slow
  Scenario: the emitted --edn deps boot isaac on the JVM
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app {:local/root "modules/marigold.app"}}}
      """
    # clojure -Sdeps "$(isaac modules deps --edn)" -M -m isaac.foundation.main --version
    When the emitted launch deps boot "isaac.foundation.main --version"
    Then the stdout contains "isaac"
    And the exit code is 0

  # The scenario above boots from this checkout, where deps.edn quietly
  # supplies foundation's libraries, and marigold.app drags them in too. A
  # service boots from the Isaac root with real modules that exclude
  # foundation — so: no checkout underfoot, no modules to lean on.
  @slow @wip
  Scenario: the emitted --edn deps boot isaac from the Isaac root
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {}}
      """
    # cd /tmp/isaac && clojure -Sdeps "$(isaac modules deps --edn)" -M -m isaac.foundation.main --version
    When a JVM in the Isaac root boots "isaac.foundation.main --version" from the emitted launch deps
    Then the stdout contains "isaac"
    And the stderr does not contain "deprecated"
    And the exit code is 0