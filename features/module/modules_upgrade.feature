Feature: isaac modules upgrade — refresh installed modules to registry coords
  Config snapshots :modules coords when install runs; the registry can move on
  without updating installed roots. `modules upgrade` re-fetches the catalog and
  rewrites registry-sourced git coords to the latest entry, like brew upgrade.
  "Upgraded" means the new pin is materialized — the next CLI process must not
  race a cold gitlib checkout.

  # `modules upgrade` calls warm-module-checkouts! to materialize the new
  # coord's gitlib checkout and add it to the live JVM classpath — real
  # network I/O that permanently taints the shared-JVM default suite (same
  # isaac.http :builtin? true leak as modules_registry_install.feature).
  # @slow isolates it in its own process, per CI's "Slow features" job.
  @slow
  Scenario: A stale registry-sourced module is rewritten to the latest coord
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:module-registry "registry.edn"
       :modules {:stale {:git/url "https://github.com/slagyr/isaac-http.git"
                         :git/sha "6960803d2a0f90431051fe98359e6e16ff6fd29c"}}}
      """
    And the isaac file "registry.edn" exists with:
      """
      {:stale {:coord {:git/url "https://github.com/slagyr/isaac-http.git"
                       :git/sha "817a5242b3c85bdcadbc4225c5d75f8fafc64c18"}
               :desc "Stale server module"}}
      """
    When isaac is run with "modules upgrade"
    Then the stdout contains "Upgraded stale: 6960803 -> 817a524"
    And the exit code is 0
    And the isaac file "config/isaac.edn" EDN contains:
      | path          | value                                                                                      |
      | modules.stale | {:git/url "https://github.com/slagyr/isaac-http.git" :git/sha "817a5242b3c85bdcadbc4225c5d75f8fafc64c18"} |

  Scenario: Local and non-registry modules are left untouched
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:module-registry "registry.edn"
       :modules {:local  {:local/root "modules/local"}
                 :orphan {:mvn/version "9.9.9"}}}
      """
    And the isaac file "registry.edn" exists with:
      """
      {:other {:coord {:local/root "modules/other"} :desc "Unrelated"}}
      """
    When isaac is run with "modules upgrade"
    Then the stdout contains "up to date"
    And the exit code is 0
    And the isaac file "config/isaac.edn" EDN contains:
      | path           | value                                              |
      | modules.local  | {:local/root "modules/local"}                      |
      | modules.orphan | {:mvn/version "9.9.9"} |