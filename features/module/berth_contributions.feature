Feature: Contribution validation against a berth's :manifest :schema
  When a consumer module's manifest carries a top-level namespaced
  key, that key is treated as a berth contribution. The foundation
  looks up the matching berth declaration in the module-index,
  validates the contribution against the berth's `:manifest :schema`,
  and either preserves it under the module-index or reports a
  validation error pinpointing what's wrong.

  Reserved un-namespaced top-level keys (`:id`, `:version`, `:factory`,
  `:deps`, `:lifecycle`, `:berths`) are NOT contributions. Any top-level
  namespaced key not matched by an installed berth is a config-load
  error.

  Scenario: A consumer's contribution to a known berth is validated and preserved
    Given an empty Isaac state directory "/tmp/marigold"
    And the isaac file "/tmp/modules/marigold.bridge/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/marigold.bridge/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.bridge
       :version "1.0.0"
       :factory marigold.bridge/create-module
       :berths  {:marigold.bridge/comm
                 {:description "Comm channels."
                  :manifest {:schema {:type       :map
                                      :key-spec   {:type :keyword}
                                      :value-spec {:type :map
                                                   :schema {:label {:type :string :validations [:present?]}}}}}}}}
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
       :marigold.bridge/comm {:longwave {:label "long-wave radio"}}}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "/tmp/modules/marigold.bridge"}
                 :marigold.longwave {:local/root "/tmp/modules/marigold.longwave"}}}
      """
    When the config is loaded
    Then the loaded config has:
      | key                                                                              | value           |
      | /module-index/marigold.longwave/manifest/marigold.bridge~1comm/longwave/label    | long-wave radio |
    And the config has no validation errors

  Scenario: A contribution missing a required field is a config-load error
    Given an empty Isaac state directory "/tmp/marigold"
    And the isaac file "/tmp/modules/marigold.bridge/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/marigold.bridge/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.bridge
       :version "1.0.0"
       :factory marigold.bridge/create-module
       :berths  {:marigold.bridge/comm
                 {:description "Comm channels."
                  :manifest {:schema {:type       :map
                                      :key-spec   {:type :keyword}
                                      :value-spec {:type :map
                                                   :schema {:label {:type :string :validations [:present?]}}}}}}}}
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
       :marigold.bridge/comm {:longwave {}}}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "/tmp/modules/marigold.bridge"}
                 :marigold.longwave {:local/root "/tmp/modules/marigold.longwave"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                                                                          | value           |
      | module-index["marigold.longwave"].marigold.bridge/comm[:longwave].label      | must be present |

  Scenario: A contribution to an unknown berth is a config-load error
    Given an empty Isaac state directory "/tmp/marigold"
    And the isaac file "/tmp/modules/marigold.longwave/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/marigold.longwave/resources/isaac-manifest.edn" exists with:
      """
      {:id                   :marigold.longwave
       :version              "0.1.0"
       :factory              marigold.longwave/create-module
       :marigold.bridge/comm {:longwave {:label "long-wave radio"}}}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.longwave {:local/root "/tmp/modules/marigold.longwave"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                                                          | value                                       |
      | module-index["marigold.longwave"][:marigold.bridge/comm]     | berth not declared by any installed module  |
