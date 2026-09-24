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
                  :schema   {:type       :map
                             :key-spec   {:type :keyword}
                             :value-spec {:type :map
                                          :schema {:label {:type :string :validations [:present?]}}}}}}}
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
                  :schema   {:type       :map
                             :key-spec   {:type :keyword}
                             :value-spec {:type :map
                                          :schema {:label {:type :string :validations [:present?]}}}}}}}
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

  # A berth need not be keyed. `:isaac.google/scopes` is a plain vector of
  # strings, and apron's `conform` only ever reads its top-level schema as an
  # entity: handed the berth schema raw, it coerced the contribution with
  # `->map`, which walks a vector as a seq of map entries — "openid" became a
  # seq of Characters and the first of them was cast to a Map$Entry. So every
  # host with a Google organization failed `config validate` on a berth that
  # was perfectly well-formed. The contribution is wrapped in a one-field
  # entity before conforming, so a :seq berth is checked element-wise.
  # Beans: isaac-1f4g, isaac-a9dp.
  Scenario: A :seq berth's contribution is checked element-wise, not walked as map entries
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
       :berths  {:marigold.bridge/bands
                 {:description "Bands the bridge listens on."
                  :schema   {:type :seq
                             :spec {:type :string}}}}}
      """
    And the isaac file "/tmp/modules/marigold.longwave/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/marigold.longwave/resources/isaac-manifest.edn" exists with:
      """
      {:id                    :marigold.longwave
       :version               "0.1.0"
       :factory               marigold.longwave/create-module
       :marigold.bridge/bands ["long-wave" "short-wave"]}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "/tmp/modules/marigold.bridge"}
                 :marigold.longwave {:local/root "/tmp/modules/marigold.longwave"}}}
      """
    When the config is loaded
    Then the loaded config has:
      | key                                                                        | value      |
      | /module-index/marigold.longwave/manifest/marigold.bridge~1bands/0          | long-wave  |
      | /module-index/marigold.longwave/manifest/marigold.bridge~1bands/1          | short-wave |
    And the config has no validation errors

  Scenario: A :seq berth element of the wrong type is a config-load error naming its index
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
       :berths  {:marigold.bridge/ports
                 {:description "Ports the bridge answers on."
                  :schema   {:type :seq
                             :spec {:type :int}}}}}
      """
    And the isaac file "/tmp/modules/marigold.longwave/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/marigold.longwave/resources/isaac-manifest.edn" exists with:
      """
      {:id                    :marigold.longwave
       :version               "0.1.0"
       :factory               marigold.longwave/create-module
       :marigold.bridge/ports [8080 "not-a-port"]}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "/tmp/modules/marigold.bridge"}
                 :marigold.longwave {:local/root "/tmp/modules/marigold.longwave"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                                                        | value                            |
      | module-index["marigold.longwave"].marigold.bridge/ports[1] | can't coerce "not-a-port" to int |
