Feature: config set follows the same placement precedent for a brand-new entry (isaac-cvri)
  Where a value lands is governed by one precedent, in order: (1) an
  EXISTING entry is written where it already lives; (2) a NEW entry, added
  to a kind whose other entries are ALL already entity files, becomes one
  too; (3) else, if `prefer-entity-files` is set, it becomes a new entity
  file; (4) else it lands inline in root isaac.edn. Rules 1, 3, and 4 are
  implemented today (`choose-set-location` in `isaac.config.mutate`) — rule
  2 is missing: a brand-new entity always falls through to rule 4 today,
  even when every sibling of its kind already lives in its own file.

  Rule 2 is new (isaac-cvri); scenarios 2-4 pin the rules that already
  held, so the fix can't regress them.

  Background:
    Given an empty Isaac root at "/tmp/lshz-roster"
    And the isaac file "/tmp/modules/marigold.lshz.roster/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.lshz.roster
       :version "0.1.0"
       :factory isaac.module.protocol/module

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

  Scenario: a new entry follows suit when every sibling of its kind is already a file
    Given the isaac file "config/roster/first-mate.edn" exists with:
      """
      {:rank "senior"}
      """
    When isaac is run with "config set roster.boatswain.rank able"
    Then the exit code is 0
    And the isaac file "config/roster/boatswain.edn" EDN contains:
      | path | value |
      | rank | able  |
    And the isaac file "isaac.edn" does not contain "boatswain"

  Scenario: a new entry with no existing siblings still lands inline (unaffected)
    When isaac is run with "config set roster.quartermaster.rank petty"
    Then the exit code is 0
    And the isaac file "isaac.edn" EDN contains:
      | path                      | value |
      | roster.quartermaster.rank | petty |
    And the isaac file "config/roster/quartermaster.edn" does not exist

  Scenario: a new entry stays inline when a sibling already lives inline (mixed, not all-files, unaffected)
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.lshz.roster {:local/root "/tmp/modules/marigold.lshz.roster"}}
       :roster  {:navigator {:rank "senior"}}}
      """
    When isaac is run with "config set roster.boatswain.rank able"
    Then the exit code is 0
    And the isaac file "isaac.edn" EDN contains:
      | path                  | value |
      | roster.boatswain.rank | able  |
    And the isaac file "config/roster/boatswain.edn" does not exist

  Scenario: editing an existing entry stays where it already lives, regardless of siblings (unaffected)
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
