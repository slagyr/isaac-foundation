Feature: config validate and config schema agree on which keys are entity tables (isaac-n140)

  Foundation never names another module's config tables. `config validate`'s
  `--as PATH -` overlay and `config schema`'s path resolution used to each
  carry their own hand-maintained set of "known entity table" names — and the
  two sets disagreed (one included `:hail`, the other didn't), so a path like
  `hail.<id>.crew` parsed differently for the two commands. Both now ask the
  composed schema itself: a key is an entity-collection table when its node
  has both a `:key-spec` and a `:value-spec`, whatever module declared it.

  This fixture module (`:marigold.n140.beacons`, a unique id so it never
  collides with another fixture) declares a `:beacons` entity table this way
  — manifest-only, no `:factory`/`deps.edn`/`src` needed since nothing here
  instantiates it.

  Background:
    Given an empty Isaac root at "/tmp/n140-beacons"
    And the isaac file "/tmp/modules/marigold.n140.beacons/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.n140.beacons
       :version "0.1.0"
       :isaac.config/schema
       {:beacons {:entity-dir "beacons"
                  :schema     {:name       "beacon table"
                               :type       :map
                               :key-spec   {:type :string}
                               :value-spec {:name   :beacon
                                            :type   :map
                                            :schema {:crew {:type :string}}}}}}}
      """
    And the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.n140.beacons {:local/root "/tmp/modules/marigold.n140.beacons"}}
       :beacons {}}
      """

  Scenario: config schema resolves the fixture table's entity-id segment structurally
    When isaac is run with "config schema beacons.value"
    Then the stdout matches:
      | pattern                                   |
      | \[beacons\.value\] beacon schema          |
      | crew\s+string\s+\[beacons\.value\.crew\]  |
    And the exit code is 0

  Scenario: config validate's --as overlay parses the 2nd segment as an entity id too, the same as config schema
    Given stdin is:
      """
      "atticus"
      """
    When isaac is run with "config validate --as beacons.cordelia.crew -"
    Then the stdout contains "OK - config is valid"
    And the exit code is 0
