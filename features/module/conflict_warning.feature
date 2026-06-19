Feature: isaac modules list — warn on module version conflicts (yi82)
  When the unified resolve (92p3) mediates a version conflict (module A pins a
  shared dep at v1, module B at v2), `modules list` surfaces it: a separate
  conflicts table in human output, and :conflicts in --edn/--json. No conflict
  -> no warning.

  # Reuses 92p3's conflict fixtures: marigold.app.conflict (pulls marigold.shared
  # 1.0.0), marigold.app2.conflict (pulls 9.9.9); 1.0.0 deterministically chosen.

  Scenario: list renders the conflicts table — a row per requested version, ✓ on the loaded one
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app.conflict  {:local/root "modules/marigold.app.conflict"}
                 :marigold.app2.conflict {:local/root "modules/marigold.app2.conflict"}}}
      """
    When isaac is run with "modules list"
    # Each row is a regex matched against the whole output ('+' = one-or-more
    # spaces), so the layout is pinned without pinning exact column widths.
    Then the stdout matches:
      | 1 version conflict                                    |
      | MODULE +VERSION +REQUIRED BY +LOADED                  |
      | marigold\.shared +1\.0\.0 +marigold\.app\.conflict +✓ |
      | marigold\.shared +9\.9\.9 +marigold\.app2\.conflict   |
    And the exit code is 0

  Scenario: list --edn reports the conflict structurally
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app.conflict  {:local/root "modules/marigold.app.conflict"}
                 :marigold.app2.conflict {:local/root "modules/marigold.app2.conflict"}}}
      """
    When isaac is run with "modules list --edn"
    Then the stdout EDN contains:
      | path               | value            |
      | conflicts.0.id     | :marigold.shared |
      | conflicts.0.chosen | "1.0.0"          |
    And the exit code is 0

  Scenario: No conflict produces no conflicts table
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app {:local/root "modules/marigold.app"}}}
      """
    When isaac is run with "modules list"
    Then the stdout does not contain "version conflict"
    And the exit code is 0
