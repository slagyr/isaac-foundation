Feature: CLI startup caching

  The launcher caches expensive upfront work (classpath planning,
  module discovery, command registration) so that common fast-path
  commands like --version and --help are quick on subsequent runs.
  Cache lives at <root>/cache/cli.edn and is invalidated when
  timestamps of watched files (config, local module manifests/deps)
  change.

  Scenario: first run (cache miss) writes the cache
    Given an empty Isaac root at "/test/cli-cache-miss"
    And the isaac EDN file "config/isaac.edn" exists with:
      | path    | value |
      | modules | {"local-mod" {:local/root "/test/local-mod"}} |
    And a module manifest at "/test/local-mod/resources/isaac-manifest.edn":
      | key     | value     |
      | id      | :local-mod |
      | version | "1.0.0"   |
    When the isaac launcher is run with "--version"
    Then the stdout matches:
      | pattern              |
      | ^isaac \d+\.\d+\.\d+ |
    And the exit code is 0
    And the isaac file "cache/cli.edn" exists
    And the isaac file "cache/cli.edn" EDN contains:
      | path    | value |
      | version | 1     |

  Scenario: unchanged inputs hit the cache (fast path)
    Given an empty Isaac root at "/test/cli-cache-hit"
    And the isaac EDN file "config/isaac.edn" exists with:
      | path    | value |
      | modules | {"local-mod" {:local/root "/test/local-mod"}} |
    And a module manifest at "/test/local-mod/resources/isaac-manifest.edn":
      | key     | value     |
      | id      | :local-mod |
      | version | "1.0.0"   |
    And the isaac EDN file "cache/cli.edn" exists with:
      | path          | value          |
      | version       | 1              |
      | basis.config  | 1234567890000  |
    When the isaac launcher is run with "--version"
    Then the stdout matches:
      | pattern              |
      | ^isaac \d+\.\d+\.\d+ |
    And the exit code is 0
    And the isaac file "cache/cli.edn" exists
    And the isaac file "cache/cli.edn" EDN contains:
      | path          | value          |
      | version       | 1              |
      | basis.config  | 1234567890000  |

  Scenario: config change invalidates the cache
    Given an empty Isaac root at "/test/cli-cache-inval"
    And the isaac EDN file "config/isaac.edn" exists with:
      | path    | value |
      | modules | {"local-mod" {:local/root "/test/local-mod"}} |
    And a module manifest at "/test/local-mod/resources/isaac-manifest.edn":
      | key     | value     |
      | id      | :local-mod |
      | version | "1.0.0"   |
    And the isaac EDN file "cache/cli.edn" exists with:
      | path          | value          |
      | version       | 1              |
      | basis.config  | 1111111111111  |
    And the isaac EDN file "config/isaac.edn" exists with:
      | path    | value |
      | modules | {"local-mod" {:local/root "/test/local-mod"}} |
    When the isaac launcher is run with "--version"
    Then the stdout matches:
      | pattern              |
      | ^isaac \d+\.\d+\.\d+ |
    And the exit code is 0
    And the isaac file "cache/cli.edn" exists
    And the isaac file "cache/cli.edn" EDN contains:
      | path          | value          |
      | version       | 1              |
      | basis.config  | #*             |

  Scenario: local module manifest change invalidates the cache
    Given an empty Isaac root at "/test/cli-cache-inval-local"
    And the isaac EDN file "config/isaac.edn" exists with:
      | path    | value |
      | modules | {"local-mod" {:local/root "/test/local-mod"}} |
    And a module manifest at "/test/local-mod/resources/isaac-manifest.edn":
      | key     | value     |
      | id      | :local-mod |
      | version | "1.0.0"   |
    And the isaac EDN file "cache/cli.edn" exists with:
      | path          | value          |
      | version       | 1              |
      | basis.local   | 1111111111111  |
    And a module manifest at "/test/local-mod/resources/isaac-manifest.edn":
      | key     | value     |
      | id      | :local-mod |
      | version | "1.0.0"   |
    When the isaac launcher is run with "--version"
    Then the stdout matches:
      | pattern              |
      | ^isaac \d+\.\d+\.\d+ |
    And the exit code is 0
    And the isaac file "cache/cli.edn" exists
    And the isaac file "cache/cli.edn" EDN contains:
      | path          | value          |
      | version       | 1              |
      | basis.local   | #*             |

  Scenario: --help also benefits from cache
    Given an empty Isaac root at "/test/cli-cache-help"
    And the isaac EDN file "config/isaac.edn" exists with:
      | path    | value |
      | modules | {"local-mod" {:local/root "/test/local-mod"}} |
    And a module manifest at "/test/local-mod/resources/isaac-manifest.edn":
      | key     | value     |
      | id      | :local-mod |
      | version | "1.0.0"   |
    And the isaac EDN file "cache/cli.edn" exists with:
      | path          | value          |
      | version       | 1              |
      | basis.config  | 1234567890000  |
    When the isaac launcher is run with "--help"
    Then the stdout contains "Usage: isaac"
    And the exit code is 0
    And the isaac file "cache/cli.edn" exists
    And the isaac file "cache/cli.edn" EDN contains:
      | path          | value          |
      | version       | 1              |
      | basis.config  | 1234567890000  |
