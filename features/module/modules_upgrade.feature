@wip
Feature: isaac modules upgrade — refresh installed modules to the latest registry coords
  `isaac modules upgrade [name...]` re-fetches the registry and rewrites each
  REGISTRY-SOURCED module in :modules to the latest coord, reporting old -> new.
  :local/root and non-registry coords are left untouched. No args = all; names =
  selective. (This is the fix for stale config coords after a registry bump.)

  Scenario: upgrade rewrites a stale module to the latest registry coord
    Given Isaac root "/tmp/isaac" contains config:
      """
      {:module-registry "registry.edn"
       :modules {:greeter {:git/url "https://github.com/slagyr/isaac-greeter.git" :git/sha "0000000"}}}
      """
    And the isaac file "registry.edn" exists with:
      """
      {:greeter {:coord {:git/url "https://github.com/slagyr/isaac-greeter.git" :git/sha "abc1234"} :desc "G"}}
      """
    When isaac is run with "modules upgrade"
    Then the stdout contains "greeter"
    And the stdout contains "abc1234"
    And the isaac file "config/isaac.edn" EDN contains:
      | path                    | value     |
      | modules.greeter.git/sha | "abc1234" |
    And the exit code is 0

  Scenario: upgrade leaves :local/root and non-registry modules untouched
    Given Isaac root "/tmp/isaac" contains config:
      """
      {:module-registry "registry.edn"
       :modules {:dev {:local/root "modules/dev"}}}
      """
    And the isaac file "registry.edn" exists with:
      """
      {:greeter {:coord {:local/root "modules/greeter"} :desc "G"}}
      """
    When isaac is run with "modules upgrade"
    Then the isaac file "config/isaac.edn" EDN contains:
      | path        | value                       |
      | modules.dev | {:local/root "modules/dev"} |
    And the stdout contains "up to date"
    And the exit code is 0
