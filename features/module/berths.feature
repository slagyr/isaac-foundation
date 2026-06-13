Feature: Module berth declarations (phase 1 — shape only)
  Module manifests may declare berths — named extension points other
  modules contribute to or users configure. Phase 1 is read-through:
  the schema accepts the new fields (`:berths`, `:deps`, top-level
  `:factory`), the loader preserves them in `:module-index`, and the
  obvious shape mistakes surface as config-load errors. No berth
  behavior runs yet (no contribution validation, no Module protocol
  dispatch, no `:dynamic-schema` merging). Those land in later beans.

  Scenario: A provider manifest's :berths map is preserved in the module-index
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
                 {:description "Comm channels (longwave, skybeam, logbook, ...)."
                  :schema      {:type :map}
                  :config      {:path   [:comms]
                                :schema {:type :map}}}
                 :marigold.bridge/signal-route
                 {:description "Signal route handlers."
                  :schema      {:type :seq}}}}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge {:local/root "/tmp/modules/marigold.bridge"}}}
      """
    When the config is loaded
    Then the loaded config has:
      | key                                                                                          | value                                            |
      | /module-index/marigold.bridge/manifest/berths/marigold.bridge~1comm/description              | Comm channels (longwave, skybeam, logbook, ...). |
      | /module-index/marigold.bridge/manifest/berths/marigold.bridge~1comm/schema          | {:type :map}                                     |
      | /module-index/marigold.bridge/manifest/berths/marigold.bridge~1comm/config/path              | [:comms]                                         |
      | /module-index/marigold.bridge/manifest/berths/marigold.bridge~1signal-route/schema  | {:type :seq}                                     |
    And the config has no validation errors

  Scenario: A berth declaration missing :description is a config-load error
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
       :berths  {:marigold.bridge/comm {:schema   {:type :map}}}}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge {:local/root "/tmp/modules/marigold.bridge"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                                                                       | value           |
      | module-index["marigold.bridge"].berths[:marigold.bridge/comm].description | must be present |

  Scenario: A berth keyed by an un-namespaced keyword is a config-load error
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
       :berths  {:comm {:description "Comm channels."
                        :schema      {:type :map}}}}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge {:local/root "/tmp/modules/marigold.bridge"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                                            | value                                  |
      | module-index["marigold.bridge"].berths[:comm]  | berth key must be a namespaced keyword |

  Scenario: A consumer manifest's :deps map is preserved in the module-index
    Given an empty Isaac state directory "/tmp/marigold"
    And the isaac file "/tmp/modules/marigold.bridge/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/marigold.bridge/resources/isaac-manifest.edn" exists with:
      """
      {:id :marigold.bridge :version "1.0.0" :factory marigold.bridge/create-module}
      """
    And the isaac file "/tmp/modules/marigold.longwave/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/marigold.longwave/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.longwave
       :version "0.1.0"
       :factory marigold.longwave/create-module
       :deps    {:marigold.bridge {:local/root "/tmp/modules/marigold.bridge"}}}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.longwave {:local/root "/tmp/modules/marigold.longwave"}}}
      """
    When the config is loaded
    Then the loaded config has:
      | key                                                              | value                                                  |
      | /module-index/marigold.longwave/manifest/deps/marigold.bridge    | {:local/root "/tmp/modules/marigold.bridge"}           |
    And the config has no validation errors

  Scenario: A :deps entry whose coordinate is not a map is a config-load error
    Given an empty Isaac state directory "/tmp/marigold"
    And the isaac file "/tmp/modules/marigold.longwave/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/marigold.longwave/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.longwave
       :version "0.1.0"
       :factory marigold.longwave/create-module
       :deps    {:marigold.bridge "not-a-coordinate"}}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.longwave {:local/root "/tmp/modules/marigold.longwave"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                                                            | value                    |
      | module-index["marigold.longwave"].deps[:marigold.bridge]       | must be a coordinate map |

  Scenario: A manifest's top-level :factory must be a symbol
    Given an empty Isaac state directory "/tmp/marigold"
    And the isaac file "/tmp/modules/marigold.bridge/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "/tmp/modules/marigold.bridge/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.bridge
       :version "1.0.0"
       :factory "marigold.bridge/create-module"}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge {:local/root "/tmp/modules/marigold.bridge"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                                     | value            |
      | module-index["marigold.bridge"].factory | must be a symbol |

  Scenario: :berths must be a map
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
       :berths  [:marigold.bridge/comm :marigold.bridge/signal-route]}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge {:local/root "/tmp/modules/marigold.bridge"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                                    | value         |
      | module-index["marigold.bridge"].berths | must be a map |
