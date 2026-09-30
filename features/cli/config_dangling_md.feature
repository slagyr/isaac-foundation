Feature: Config validation — dangling .md warnings (isaac-yo8d)

  Companion .md files under any `:entity-dir "<dir>"` table (crew/<id>.md,
  cron/<id>.md, etc. in a real deployment) are expected to live alongside a
  matching .edn entity or config entry. A lone .md file with no matching
  entry is likely a typo or half-done config. Isaac warns at config-load
  time rather than silently ignoring it. Moved from isaac-agent's
  features/config/dangling_md.feature — isaac.config.entities/dangling-md-
  warnings is foundation's own generic function (it walks whatever
  entity-dirs the loaded schema declares); agent's copy only ever exercised
  it through :crew. This fixture proves the same behavior with a fixture
  entity-dir.

  Warnings surface on stderr; exit stays 0.

  Background:
    Given an empty Isaac root at "target/test-cln1-dangling"
    And the isaac file "widgets-module/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.cln1.widgets
       :version "0.1.0"
       :isaac.config/schema
       {:widgets {:entity-dir   "widgets"
                  :frontmatter? true
                  :schema       {:name       "widget table"
                                :type       :map
                                :key-spec   {:type :string}
                                :value-spec {:name   :widget
                                            :type   :map
                                            :schema {:color {:type :string}}}}}}}
      """
    And the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.cln1.widgets {:local/root "target/test-cln1-dangling/widgets-module"}}}
      """

  @wip
  Scenario: a dangling <entity-dir>/<id>.md with no matching entity warns
    Given the isaac file "config/widgets/gizmo.edn" exists with:
      """
      {:color "blue"}
      """
    And the isaac file "config/widgets/ghost.md" exists with:
      """
      I have no matching entity and no frontmatter of my own.
      """
    When isaac is run with "config validate"
    Then the stderr contains "dangling"
    And the stderr contains "widgets/ghost.md"
    And the exit code is 0

  @wip
  Scenario: a single-file <entity-dir>/<id>.md entity is not dangling
    Given the isaac file "config/widgets/gizmo.md" exists with:
      """
      ---
      color: green
      ---

      Gizmo's own entity file.
      """
    When isaac is run with "config validate"
    Then the stdout contains "OK - config is valid"
    And the stderr does not contain "dangling"
    And the exit code is 0
