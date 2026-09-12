Feature: CLI startup caching
  Isaac caches CLI startup metadata so repeated invocations avoid recomputing
  the same work when the watched config has not changed.

  Scenario: --version uses cached startup metadata on a warm run
    Given an empty Isaac root at "target/test-startup-cache"
    When isaac is run with "--version"
    And isaac is run with "--version"
    Then the exit code is 0

  Scenario: --help uses cached startup metadata on a warm run
    Given an empty Isaac root at "target/test-startup-cache"
    When isaac is run with "--help"
    And isaac is run with "--help"
    Then the exit code is 0

  Scenario: --version recomputes when the watched config changes
    Given an empty Isaac root at "target/test-startup-cache"
    When isaac is run with "--version"
    And the isaac file "config/isaac.edn" exists with:
      """
      {:log {:output "stdout"}}
      """
    And isaac is run with "--version"
    Then the exit code is 0

  Scenario: --version recomputes when the cache file is removed
    Given an empty Isaac root at "target/test-startup-cache"
    When isaac is run with "--version"
    And the classpath cache file is removed
    And isaac is run with "--version"
    Then the exit code is 0

  Scenario: non-fast-path command uses warm classpath cache without replanning
    Given an empty Isaac root at "target/test-startup-cache"
    And a warm classpath cache exists from a prior non-fast-path run
    And the classpath plan spy is armed
    When isaac is run with "logs --list"
    Then the exit code is 0
    And the classpath plan spy was invoked exactly 0 times

  Scenario: a warm config hit retains module discovery for module-provided config types
    Given an empty Isaac root at "target/test-startup-cache"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "modules/marigold.bridge"}
                 :marigold.longwave {:local/root "modules/marigold.longwave"}}
       :relays  {:helm-relay {:type :longwave :crew "captain" :helm/freq "121.5"}}}
      """
    When isaac is run with "config validate"
    And the warm config load result spy is armed
    And isaac is run with "config validate"
    Then the warm config load result includes module "marigold.longwave"
    And the warm config load result has no validation errors
    And the exit code is 0

  Scenario: corrupted classpath cache fails open replans and refreshes basis
    Given an empty Isaac root at "target/test-startup-cache"
    And the isaac EDN file "isaac.edn" exists with:
      | modules | {} |
    And a warm classpath cache exists from a prior non-fast-path run
    And the classpath plan spy is armed
    And the classpath cache file is corrupted so apply fails
    When isaac is run with "logs --list"
    Then the exit code is 0
    And the classpath plan spy was invoked at least 1 times
    And the classpath cache was refreshed after replan

  Scenario: classpath timing records cold then warm plan-compose samples
    Given an empty Isaac root at "target/test-startup-cache"
    And the classpath plan spy is armed
    When isaac is run with "logs --list"
    And isaac is run with "logs --list"
    Then the exit code is 0
    And classpath timing evidence shows warm plan-compose faster than cold