Feature: config get labels exactly the fields that came from a schema default

  `config get` text output marks a value that came from a schema `:default`
  rather than from the config (isaac-dnib). Two flaws (field report,
  2026-10-02, `config get models.claude-opus` on yopp printed a whole
  file-defined model as "(default)"):

  - The check compared against root-level config only, so every value
    defined in an entity file (config/<dir>/<id>.edn) looked defaulted.
  - A map was labeled as a whole, which cannot say which field defaulted.

  Now: a value set in any config file, root or entity file, is never
  labeled. A defaulted scalar prints `<value> (default)` as before. A map
  prints each defaulted field with a trailing `; default` comment (the
  output stays valid EDN) and nothing after the map. `--edn`/`--json`
  stay plain data.

  Background:
    Given an empty Isaac root at "target/test-dflt-lamps"
    And the isaac file "lamps-module/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.dflt.lamps
       :version "0.1.0"
       :isaac.config/schema
       {:lamps {:entity-dir "lamps"
                :schema     {:name       "lamp table"
                             :type       :map
                             :key-spec   {:type :string}
                             :value-spec {:name   :lamp
                                          :type   :map
                                          :schema {:color   {:type :string}
                                                   :wattage {:type :int :default 60}}}}}}}
      """
    And the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.lamps {:local/root "target/test-dflt-lamps/lamps-module"}}}
      """

  Scenario: a value set in an entity file is not labeled a default
    Given the isaac file "config/lamps/porch.edn" exists with:
      """
      {:color "amber" :wattage 40}
      """
    When isaac is run with "config get lamps.porch.wattage"
    Then the stdout contains "40"
    And the stdout does not contain "default"
    And the exit code is 0

  Scenario: a defaulted field of an entity is labeled
    Given the isaac file "config/lamps/porch.edn" exists with:
      """
      {:color "amber"}
      """
    When isaac is run with "config get lamps.porch.wattage"
    Then the stdout contains "60 (default)"
    And the exit code is 0

  Scenario: an entity map labels each defaulted field, and only those
    Given the isaac file "config/lamps/porch.edn" exists with:
      """
      {:color "amber"}
      """
    When isaac is run with "config get lamps.porch"
    Then the stdout matches:
      | pattern                              |
      | (?m)^\s*:color\s+"amber"\s*$         |
      | (?m)^\s*:wattage\s+60\s+; default\s*$ |
    And the stdout does not contain "(default)"
    And the exit code is 0

  Scenario: an entity map with nothing defaulted carries no labels
    Given the isaac file "config/lamps/porch.edn" exists with:
      """
      {:color "amber" :wattage 40}
      """
    When isaac is run with "config get lamps.porch"
    Then the stdout contains "amber"
    And the stdout does not contain "default"
    And the exit code is 0

  Scenario: --edn prints the defaulted map as plain data
    Given the isaac file "config/lamps/porch.edn" exists with:
      """
      {:color "amber"}
      """
    When isaac is run with "config get lamps.porch --edn"
    Then the stdout matches:
      | pattern        |
      | :wattage\s+60  |
    And the stdout does not contain "default"
    And the exit code is 0
