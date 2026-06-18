Feature: Registry module install composes classpath
  Installing a registry module with sha-only coordinates must not fail classpath
  resolution when transitive deps pin foundation by sha (no tag/sha mismatch).

  Scenario: Install isaac.server then run isaac --version
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:module-registry "registry.edn"}
      """
    And the isaac file "registry.edn" exists with:
      """
      {:isaac.server {:coord {:git/url "https://github.com/slagyr/isaac-server.git"
                              :git/sha "6960803d2a0f90431051fe98359e6e16ff6fd29c"}
                      :desc "HTTP server host"}}
      """
    When isaac is run with "modules install isaac.server"
    Then the stdout contains "Installed isaac.server"
    And the exit code is 0
    When isaac is run with "--version"
    Then the stdout contains "isaac"
    And the exit code is 0