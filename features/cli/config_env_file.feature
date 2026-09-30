Feature: <root>/.env layers into ${VAR} substitution below shell env (isaac-yo8d)

  Operators put secrets in <isaac-root>/.env and reference them from config
  via ${VAR} syntax. This file loads as an additional env source, layered
  into c3kit's env precedence below shell env and cwd-local .env. Moved
  from isaac-agent's features/config/env_file.feature — the substitution
  mechanism is foundation's own (isaac.config.env), exercised there only
  through agent's :providers entity table. This fixture proves the same
  behavior with no agent concept in the picture.

  Background:
    Given an empty Isaac root at "target/test-cln1-env"
    And the isaac file "widgets-module/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.cln1.widgets
       :version "0.1.0"
       :isaac.config/schema
       {:widgets {:entity-dir "widgets"
                  :schema     {:name       "widget table"
                              :type       :map
                              :key-spec   {:type :string}
                              :value-spec {:name   :widget
                                          :type   :map
                                          :schema {:api-key {:type :string}}}}}}}
      """
    And the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.cln1.widgets {:local/root "target/test-cln1-env/widgets-module"}}}
      """

  @wip
  Scenario: ${VAR} resolves from the isaac .env file
    Given the isaac file ".env" exists with:
      """
      CLN1_WIDGET_KEY=sk-from-isaac
      """
    And the isaac file "config/widgets/gizmo.edn" exists with:
      """
      {:api-key "${CLN1_WIDGET_KEY}"}
      """
    And stdin is:
      """
      REVEAL
      """
    When isaac is run with "config get widgets.gizmo.api-key --reveal"
    Then the stdout contains "sk-from-isaac"
    And the exit code is 0

  @wip
  Scenario: OS environment variables take precedence over the isaac .env file
    Given environment variable "CLN1_WIDGET_KEY" is "sk-from-os"
    And the isaac file ".env" exists with:
      """
      CLN1_WIDGET_KEY=sk-from-isaac
      """
    And the isaac file "config/widgets/gizmo.edn" exists with:
      """
      {:api-key "${CLN1_WIDGET_KEY}"}
      """
    And stdin is:
      """
      REVEAL
      """
    When isaac is run with "config get widgets.gizmo.api-key --reveal"
    Then the stdout contains "sk-from-os"
    And the exit code is 0

  @wip
  Scenario: config loads when the isaac .env file is absent
    Given the isaac file "config/widgets/gizmo.edn" exists with:
      """
      {:api-key "no-substitution-needed"}
      """
    When isaac is run with "config get widgets.gizmo.api-key"
    Then the stdout contains "no-substitution-needed"
    And the exit code is 0
