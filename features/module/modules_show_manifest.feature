Feature: isaac modules show — what a module is and what it provides
  `isaac modules show <name>` also reports the module's manifest: its
  description, its handbook doc (`:handbook`, a classpath resource in
  the module), the berths it declares with their descriptions, and what it
  contributes, grouped by berth. The same introspection feeds isaac-handbook.
  A `:handbook` that does not resolve is a warning — a missing doc never stops
  the ship.

  Background:
    Given an empty Isaac state directory "/tmp/marigold"
    And the isaac file "/tmp/modules/marigold.bridge/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/marigold.bridge/resources/isaac-manifest.edn" exists with:
      """
      {:id          :marigold.bridge
       :version     "1.0.0"
       :factory     marigold.bridge/create-module
       :description "The ship's bridge: where channels are declared."
       :berths      {:marigold.bridge/comm
                     {:description "Comm channels."
                      :schema      {:type       :map
                                    :key-spec   {:type :keyword}
                                    :value-spec {:type :map :schema {:label {:type :string}}}}}}}
      """
    And the isaac file "/tmp/modules/marigold.longwave/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/marigold.longwave/resources/isaac-manifest.edn" exists with:
      """
      {:id                   :marigold.longwave
       :version              "0.1.0"
       :factory              marigold.longwave/create-module
       :description          "Long-wave radio for the far reaches."
       :handbook             "marigold/longwave/handbook.md"
       :marigold.bridge/comm {:longwave {:label "long-wave radio"}}}
      """
    And the isaac file "/tmp/modules/marigold.longwave/resources/marigold/longwave/handbook.md" exists with:
      """
      ## Purpose
      Talk to ships beyond the horizon.
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "/tmp/modules/marigold.bridge"}
                 :marigold.longwave {:local/root "/tmp/modules/marigold.longwave"}}}
      """

  Scenario: show prints what a module is, its handbook, and what it contributes
    When isaac is run with "modules show marigold.longwave"
    Then the stdout lines contain in order:
      | Description: Long-wave radio for the far reaches. |
      | Handbook:    marigold/longwave/handbook.md        |
      | Contributes:                                      |
      | marigold.bridge/comm  longwave                    |
    And the exit code is 0

  Scenario: show --edn reports declared berths with their descriptions
    When isaac is run with "modules show marigold.bridge --edn"
    Then the stdout EDN contains:
      | path                                      | value                                             |
      | description                               | "The ship's bridge: where channels are declared." |
      | declares.marigold.bridge/comm.description | "Comm channels."                                  |
      | handbook                                  | nil                                               |
    And the exit code is 0

  Scenario: show --edn reports contributions by berth
    When isaac is run with "modules show marigold.longwave --edn"
    Then the stdout EDN contains:
      | path                             | value                         |
      | handbook                         | "marigold/longwave/handbook.md" |
      | contributes.marigold.bridge/comm | [:longwave]                   |
    And the exit code is 0

  Scenario: a handbook that does not resolve is a warning, not an error
    Given the isaac file "/tmp/modules/marigold.longwave/resources/isaac-manifest.edn" exists with:
      """
      {:id                   :marigold.longwave
       :version              "0.1.0"
       :factory              marigold.longwave/create-module
       :handbook             "marigold/longwave/missing.md"
       :marigold.bridge/comm {:longwave {:label "long-wave radio"}}}
      """
    When the config is loaded
    Then the config has validation warnings matching:
      | key                                      | value                                      |
      | module-index["marigold.longwave"].handbook | #"marigold/longwave/missing.md.*not found" |
    And the config has no validation errors
