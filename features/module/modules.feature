Feature: isaac modules — compose the assistant via config
  `isaac modules` manages the user's :modules config. `available` browses the
  registry catalog; `install` / `remove` edit :modules in config/isaac.edn;
  `list` shows what is installed (see module/modules_list.feature). These are
  CONFIG operations only — they do not load or run module code.

  The catalog is fetched from a registry. Default is raw-github
  (github.com/slagyr/isaac/modules.edn); :module-registry overrides it with a
  path (relative to the Isaac root) or URL — the seam for tests and private
  registries. install resolves name -> :coord and writes the COORDINATE into
  :modules, so config stays self-contained.

  Scenario: Available lists the registry catalog
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:module-registry "registry.edn"}
      """
    And the isaac file "registry.edn" exists with:
      """
      {:bridge  {:coord {:local/root "modules/marigold.bridge"}      :desc "A bridge module"}
       :greeter {:coord {:local/root "modules/marigold.cli.greeter"} :desc "Prints greetings"}}
      """
    When isaac is run with "modules available --edn"
    Then the stdout EDN contains:
      | path           | value             |
      | modules.0.id   | :bridge           |
      | modules.0.desc | "A bridge module" |
      | modules.1.id   | :greeter          |
    And the exit code is 0

  Scenario: Install resolves the name and writes the coordinate to config
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:module-registry "registry.edn"}
      """
    And the isaac file "registry.edn" exists with:
      """
      {:greeter {:coord {:local/root "modules/marigold.cli.greeter"} :desc "Prints greetings"}}
      """
    When isaac is run with "modules install greeter"
    Then the stdout contains "Installed greeter"
    And the exit code is 0
    And the isaac file "config/isaac.edn" EDN contains:
      | path            | value                                        |
      | modules.greeter | {:local/root "modules/marigold.cli.greeter"} |

  Scenario: Remove deletes a module from config
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:greeter {:local/root "modules/marigold.cli.greeter"}}}
      """
    When isaac is run with "modules remove greeter"
    Then the stdout contains "Removed greeter"
    And the exit code is 0
    And the isaac file "config/isaac.edn" EDN contains:
      | path    | value |
      | modules | {}    |

  Scenario: Installing an unknown module errors and leaves config untouched
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {} :module-registry "registry.edn"}
      """
    And the isaac file "registry.edn" exists with:
      """
      {:greeter {:coord {:local/root "modules/marigold.cli.greeter"} :desc "Prints greetings"}}
      """
    When isaac is run with "modules install nope"
    Then the stderr contains "Unknown module: nope"
    And the exit code is 1
    And the isaac file "config/isaac.edn" EDN contains:
      | path    | value |
      | modules | {}    |

  Scenario: A registry fetch failure aborts without touching config
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {} :module-registry "registry.edn"}
      """
    When isaac is run with "modules install greeter"
    Then the stderr contains "Could not reach the module registry"
    And the exit code is 1
    And the isaac file "config/isaac.edn" EDN contains:
      | path    | value |
      | modules | {}    |
