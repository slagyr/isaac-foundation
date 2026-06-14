Feature: Module coordinates
  isaac.edn declares :modules as a map of module-id to a tools.deps
  coordinate. At config-load time, every coordinate is resolved (via
  babashka.deps in bb context, clojure.tools.deps in clj), the
  resolved classpath is added, each module's isaac-manifest.edn is read from
  the classpath, and the module index is built. Resolution is
  load-time so manifest schema fragments are available before cfg
  validation.

  :local/root paths resolve relative to the current working
  directory.

  Scenario: Modules declared with :local/root coordinates resolve at boot
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.cli.greeter {:local/root "modules/marigold.cli.greeter"}}}
      """
    When the config is loaded
    Then the loaded config has:
      | key                                         | value             |
      | /module-index/marigold.cli.greeter/manifest/id | marigold.cli.greeter |

  Scenario: Hard error when a :local/root coordinate path is missing
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:isaac.comm.ghost {:local/root "modules/isaac.comm.ghost"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                            | value                            |
      | modules["isaac.comm.ghost"]   | local/root path does not resolve |

  Scenario: Module with manifest at src/ (not resources/) is discoverable via :local/root dot
    Given an empty Isaac root at "/tmp/isaac"
    And the effective working directory is "modules/marigold.comm.noop"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.comm.noop {:local/root "."}}}
      """
    When the config is loaded
    Then the loaded config has:
      | key                                           | value               |
      | /module-index/marigold.comm.noop/manifest/id | marigold.comm.noop |

  Scenario: Legacy vector :modules shape produces a migration error
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules [isaac.comm.telly]}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key      | value                                                     |
      | modules  | must be a map of id to coordinate \(legacy vector shape\) |
