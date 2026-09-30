Feature: config set places a new entry by preference, an existing one where it lives (isaac-c4em)
  Placement has two rules. An EXISTING entry is written where it already
  lives: an entity file stays a file, an inline entry stays inline, never
  converted. A NEW entry becomes its own entity file when the config sets
  `:prefer-entity-files true`, and otherwise lands inline in isaac.edn.
  (isaac-cvri's "siblings are all files" rule is removed.)

  Background:
    Given an empty Isaac root at "/tmp/lshz-roster"
    And the isaac file "/tmp/modules/marigold.lshz.roster/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.lshz.roster
       :version "0.1.0"
       :factory isaac.foundation.module.protocol/module

       :isaac.config/schema
       {:roster {:entity-dir "roster"
                 :schema     {:name       "roster table"
                              :type       :map
                              :key-spec   {:type :string}
                              :value-spec {:name   :roster-entry
                                           :type   :map
                                           :schema {:rank {:type :string}}}}}}}
      """
    And the isaac file "/tmp/modules/marigold.lshz.roster/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.lshz.roster {:local/root "/tmp/modules/marigold.lshz.roster"}}}
      """

  Scenario: without the preference, a new entry lands inline even when its siblings are files
    Given the isaac file "config/roster/first-mate.edn" exists with:
      """
      {:rank "senior"}
      """
    When isaac is run with "config set roster.boatswain.rank able"
    Then the exit code is 0
    And the isaac file "isaac.edn" EDN contains:
      | path                  | value |
      | roster.boatswain.rank | able  |
    And the isaac file "config/roster/boatswain.edn" does not exist

  Scenario: with the preference, a new entry becomes its own entity file
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules             {:marigold.lshz.roster {:local/root "/tmp/modules/marigold.lshz.roster"}}
       :prefer-entity-files true}
      """
    When isaac is run with "config set roster.boatswain.rank able"
    Then the exit code is 0
    And the isaac file "config/roster/boatswain.edn" EDN contains:
      | path | value |
      | rank | able  |
    And the isaac file "isaac.edn" does not contain "boatswain"

  Scenario: editing an entry that lives in its own file stays in that file
    Given the isaac file "config/roster/first-mate.edn" exists with:
      """
      {:rank "senior"}
      """
    When isaac is run with "config set roster.first-mate.rank chief"
    Then the exit code is 0
    And the isaac file "config/roster/first-mate.edn" EDN contains:
      | path | value |
      | rank | chief |
    And the isaac file "isaac.edn" does not contain "first-mate"

  Scenario: editing an inline entry stays inline, even with the preference
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules             {:marigold.lshz.roster {:local/root "/tmp/modules/marigold.lshz.roster"}}
       :prefer-entity-files true
       :roster              {:navigator {:rank "senior"}}}
      """
    When isaac is run with "config set roster.navigator.rank chief"
    Then the exit code is 0
    And the isaac file "isaac.edn" EDN contains:
      | path                  | value |
      | roster.navigator.rank | chief |
    And the isaac file "config/roster/navigator.edn" does not exist
