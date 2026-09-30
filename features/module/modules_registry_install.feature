Feature: Registry module install composes classpath
  Installing a registry module with sha-only coordinates must not fail classpath
  resolution when transitive deps pin foundation by sha (no tag/sha mismatch).

  # Real git-coord resolution adds isaac.http's :builtin? true manifest to
  # the live JVM classpath for the rest of the process — irreversible once
  # installed. The default (non-slow) suite runs every scenario in one
  # shared JVM, so this permanently leaked isaac.http into later scenarios'
  # builtin/module index (e.g. cli/config_schema.feature's "no modules"
  # scenario). @slow isolates this real-network, classpath-mutating
  # scenario in its own process — the same pattern already used by
  # git_coord_tree.feature — and CI runs it separately via the "Slow
  # features" job (bb features-slow).
  @slow
  Scenario: Install isaac.http then run isaac --version
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:module-registry "registry.edn"}
      """
    And the isaac file "registry.edn" exists with:
      """
      {:isaac.http {:coord {:git/url "https://github.com/slagyr/isaac-http.git"
                              :git/sha "567cae411a424689ae85046318f0543e74b75fe7"}
                      :desc "HTTP server host"}}
      """
    When isaac is run with "modules install isaac.http"
    Then the stdout contains "Installed isaac.http"
    And the exit code is 0
    When isaac is run with "--version"
    Then the stdout contains "isaac"
    And the exit code is 0