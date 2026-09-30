Feature: string-keyed tables keep one entry per key (isaac-4eay)
  A table whose `:key-spec` is `{:type :string}` may be written with
  keyword keys: EDN files usually spell `{:north {...}}`. The loaded config
  overlays conformed values onto the raw config (isaac-dnib), which has to
  recognize that raw `:north` and conformed "north" are the same entry.
  Before this fix the conformed key came out as ":north" (colon included),
  so both survived and every such table carried a phantom twin of each entry.

  The fixture is marigold.strkey.harbor: a root-level :moorings table with
  string keys and an int :berth per mooring. Ids are unique to this feature.

  Background:
    Given an empty Isaac root at "/tmp/strkey"
    And the isaac file "/tmp/modules/marigold.strkey.harbor/deps.edn" exists with:
      """
      {:paths ["resources" "src"]}
      """
    And the isaac file "/tmp/modules/marigold.strkey.harbor/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.strkey.harbor
       :version "1.0.0"
       :factory marigold.strkey.harbor/create-module
       :isaac.config/schema
       {:moorings {:schema {:type        :map
                            :description "Harbor moorings by name"
                            :key-spec    {:type :string}
                            :value-spec  {:type   :map
                                          :schema {:berth {:type :int :description "Berth number"}}}}}}}
      """
    And the isaac file "/tmp/modules/marigold.strkey.harbor/src/marigold/strkey/harbor.clj" exists with:
      """
      (ns marigold.strkey.harbor
        (:require
          [isaac.foundation.module.protocol :as module]))

      (defn create-module []
        (module/module))
      """

  @wip
  Scenario: keyword keys in a string-keyed table load as one entry each
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules  {:marigold.strkey.harbor {:local/root "/tmp/modules/marigold.strkey.harbor"}}
       :moorings {:north {:berth 3} :south {:berth 7}}}
      """
    When isaac is run with "config keys moorings"
    Then the exit code is 0
    And the stdout lines match:
      | text  |
      | north |
      | south |

  @wip
  Scenario: the single entry carries its conformed values
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules  {:marigold.strkey.harbor {:local/root "/tmp/modules/marigold.strkey.harbor"}}
       :moorings {:north {:berth "3"}}}
      """
    When isaac is run with "config get moorings --edn"
    Then the exit code is 0
    And the stdout EDN contains:
      | path        | value |
      | north.berth | 3     |
