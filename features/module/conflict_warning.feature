@wip
Feature: isaac modules list — warn on module version conflicts (yi82)
  When the unified resolve (92p3) mediates a version conflict (module A pins a
  shared dep at v1, module B at v2), `modules list` surfaces it: a separate
  conflicts table in human output, and :conflicts in --edn/--json. No conflict
  -> no warning.

  # Reuses 92p3's conflict fixtures: marigold.app.conflict (pulls marigold.shared
  # 1.0.0), marigold.app2.conflict (pulls 9.9.9); 1.0.0 deterministically chosen.

  Scenario: list shows a conflicts table when modules disagree on a version
    Given Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app.conflict  {:local/root "modules/marigold.app.conflict"}
                 :marigold.app2.conflict {:local/root "modules/marigold.app2.conflict"}}}
      """
    When isaac is run with "modules list"
    Then the stdout contains "version conflict"
    And the stdout contains "marigold.shared"
    And the stdout contains "1.0.0"
    And the stdout contains "9.9.9"
    And the exit code is 0

  Scenario: list --edn reports the conflict structurally
    Given Isaac root "/tmp/isaac" contains config:
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
    Given Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app {:local/root "modules/marigold.app"}}}
      """
    When isaac is run with "modules list"
    Then the stdout does not contain "version conflict"
    And the exit code is 0
