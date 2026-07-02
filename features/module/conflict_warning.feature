Feature: isaac modules list — warn on module version conflicts (yi82)
  When the unified resolve (92p3) mediates a version conflict (module A pins a
  shared dep at v1, module B at v2), `modules list` surfaces separate severity
  buckets: :conflicts for requested newer-than-loaded versions and :drift for
  requested older-than-loaded versions. No divergence -> no warning/drift block.

  # Reuses 92p3's conflict fixtures: marigold.app.conflict (pulls marigold.shared
  # 1.0.0), marigold.app2.conflict (pulls 9.9.9); 1.0.0 deterministically chosen.

  Scenario: list renders the warning table for newer-than-chosen requests only
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app.conflict  {:local/root "modules/marigold.app.conflict"}
                 :marigold.app2.conflict {:local/root "modules/marigold.app2.conflict"}}}
      """
    When isaac is run with "modules list"
    Then the stdout matches:
      | ⚠ +1 version conflict +— requested newer than loaded   |
      | MODULE +VERSION +REQUIRED BY +LOADED                   |
      | marigold\.shared +1\.0\.0 *+✓                        |
      | marigold\.shared +9\.9\.9 +marigold\.app2\.conflict |
    And the stdout does not contain "version drift"
    And the exit code is 0

  Scenario: list renders the drift table for older-than-chosen requests only
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.shared       {:local/root "modules/marigold.shared.v999"}
                 :marigold.app.conflict {:local/root "modules/marigold.app.conflict"}}}
      """
    When isaac is run with "modules list"
    Then the stdout matches:
      | ℹ +1 version drift +— loaded version is newer than some requests |
      | MODULE +VERSION +REQUIRED BY +LOADED                             |
      | marigold\.shared +9\.9\.9 *+✓                                  |
      | marigold\.shared +1\.0\.0 +marigold\.app\.conflict            |
    And the stdout does not contain "version conflict"
    And the exit code is 0

  Scenario: list --edn reports the conflict bucket for newer-than-chosen requests
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app.conflict  {:local/root "modules/marigold.app.conflict"}
                 :marigold.app2.conflict {:local/root "modules/marigold.app2.conflict"}}}
      """
    When isaac is run with "modules list --edn"
    Then the stdout EDN contains:
      | path                              | value                      |
      | conflicts.0.id                    | :marigold.shared           |
      | conflicts.0.chosen                | "1.0.0"                    |
      | conflicts.0.requested.0.version   | "9.9.9"                    |
      | conflicts.0.requested.0.required-by | [:marigold.app2.conflict] |
    And the exit code is 0

  Scenario: list --edn reports the drift bucket for older-than-chosen requests
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.shared       {:local/root "modules/marigold.shared.v999"}
                 :marigold.app.conflict {:local/root "modules/marigold.app.conflict"}}}
      """
    When isaac is run with "modules list --edn"
    Then the stdout EDN contains:
      | path                          | value                    |
      | drift.0.id                    | :marigold.shared         |
      | drift.0.chosen                | "9.9.9"                  |
      | drift.0.requested.0.version   | "1.0.0"                  |
      | drift.0.requested.0.required-by | [:marigold.app.conflict] |
    And the exit code is 0

  Scenario: isaac.server divergence surfaces in the conflict bucket when a requester is newer than loaded
    Given an empty Isaac root at "/tmp/isaac"
    And Isaac root "/tmp/isaac" contains config:
      """
      {:modules {:marigold.app.server.conflict  {:local/root "modules/marigold.app.server.conflict"}
                 :marigold.app2.server.conflict {:local/root "modules/marigold.app2.server.conflict"}}}
      """
    When isaac is run with "modules list --edn"
    Then the stdout EDN contains:
      | path                               | value                            |
      | conflicts.0.id                     | :isaac.server                    |
      | conflicts.0.chosen                 | "0.1.0"                          |
      | conflicts.0.requested.0.required-by | [:marigold.app2.server.conflict] |
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
