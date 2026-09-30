Feature: Config composition — entity-dir mechanics are foundation's own (isaac-mxgn)
  Moved from isaac-agent's features/config/composition.feature — the root
  isaac.edn plus per-entity files (<entity-dir>/<id>.edn, <id>.md frontmatter)
  compose additively, filenames define entity ids, and duplicate ids across
  sources are hard errors. isaac.config.entities is foundation's own generic
  machinery; agent's copy only ever exercised it through :crew/:models/
  :providers. This fixture proves the same mechanism with a fixture
  entity-dir, so no agent concept is in the picture.

  isaac-agent's original composition.feature also covered the "soul loads
  from a companion .md file" / "defining soul in both :soul and <id>.md is
  an error" mechanic. That mechanic did NOT move here: at the time,
  isaac.config.companions/companion-md-relative (the config-LOAD side) was
  hard-coded to the kinds :crew (-> :soul) and :berths (-> :ledger) — unlike
  isaac.config.mutate/companion-spec (the config-SET side), which already
  read a module's own `:companion` descriptor generically. isaac-kcck
  generalized the load side to match the write side and moved those two
  scenarios (plus three companion `config set` scenarios) to
  config_companion.feature, with their own fixture module.

  Fixture module marigold.mxgn.vessels contributes a `:vessels` entity-dir
  table (mirroring crew) with plain scalar fields `:captain` and `:log`.
  Manifest-only — no :factory, deps.edn, or src — so no real classpath
  activation is needed on the virtual test fs.

  Background:
    Given an empty Isaac root at "target/test-mxgn-composition"
    And the isaac file "vessels-module/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.mxgn.vessels
       :version "0.1.0"
       :isaac.config/schema
       {:vessels {:entity-dir          "vessels"
                  :merge-root-entity? true
                  :schema              {:name       "vessel table"
                                       :type       :map
                                       :key-spec   {:type :string}
                                       :value-spec {:name   :vessel
                                                   :type   :map
                                                   :schema {:id      {:type :string :description "Vessel id; must match filename when present"}
                                                           :captain {:type :string}
                                                           :log     {:type :string}}}}}}}
      """
    And the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.mxgn.vessels {:local/root "target/test-mxgn-composition/vessels-module"}}}
      """

  Scenario: vessel members are keyed by id
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.mxgn.vessels {:local/root "target/test-mxgn-composition/vessels-module"}}
       :vessels {:helm-station {:log "You are steady at the helm."}}}
      """
    When isaac is run with "config get vessels.helm-station.log"
    Then the stdout contains "You are steady at the helm."
    And the exit code is 0

  Scenario: loads a vessel from vessels/<id>.edn
    Given the isaac file "config/vessels/wavecrest.edn" exists with:
      """
      {:captain "Cordelia" :log "You keep the second watch."}
      """
    When isaac is run with "config get vessels.wavecrest --edn"
    Then the stdout contains ":captain \"Cordelia\""
    And the stdout contains ":log \"You keep the second watch.\""
    And the exit code is 0

  Scenario: loads a vessel from vessels/<id>.md frontmatter
    Given the isaac file "config/vessels/wavecrest.md" exists with:
      """
      ---
      captain: Cordelia
      log: _
      ---

      You keep the second watch.
      """
    When isaac is run with "config get vessels.wavecrest --edn"
    Then the stdout contains ":captain \"Cordelia\""
    And the stdout contains ":log \"You keep the second watch.\""
    And the exit code is 0

  Scenario: derives vessel id from filename when :id is not specified
    Given the isaac file "config/vessels/ketch.edn" exists with:
      """
      {:captain "Cordelia"}
      """
    When isaac is run with "config get vessels.ketch.captain"
    Then the stdout contains "Cordelia"
    And the exit code is 0

  Scenario: explicit :id must match filename
    Given the isaac file "config/vessels/wavecrest.edn" exists with:
      """
      {:id "ketch" :captain "Cordelia"}
      """
    When isaac is run with "config validate"
    Then the stderr contains "vessels.wavecrest.id"
    And the stderr contains "must match filename"
    And the stderr contains "ketch"
    And the exit code is 1

  Scenario: unknown keys in entity files produce warnings but still load
    Given the isaac file "config/vessels/wavecrest.edn" exists with:
      """
      {:vessels {:wavecrest {:captain "Cordelia"}}}
      """
    When isaac is run with "config validate"
    Then the stderr contains "vessels.wavecrest.vessels"
    And the stderr contains "unknown key"
    And the stdout contains "OK"
    And the exit code is 0

  Scenario: composes vessels from isaac.edn and vessels/*.edn additively
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.mxgn.vessels {:local/root "target/test-mxgn-composition/vessels-module"}}
       :vessels {:helm-station {:log "Steady as she goes."}}}
      """
    And the isaac file "config/vessels/wavecrest.edn" exists with:
      """
      {:captain "Cordelia" :log "Full sail ahead."}
      """
    When isaac is run with "config get vessels --edn"
    Then the stdout contains ":helm-station"
    And the stdout contains "Steady as she goes."
    And the stdout contains ":wavecrest"
    And the stdout contains ":captain \"Cordelia\""
    And the stdout contains "Full sail ahead."
    And the exit code is 0

  Scenario: duplicate vessel id across isaac.edn and vessels/*.edn is a hard error
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.mxgn.vessels {:local/root "target/test-mxgn-composition/vessels-module"}}
       :vessels {:wavecrest {:log "First"}}}
      """
    And the isaac file "config/vessels/wavecrest.edn" exists with:
      """
      {:log "Second"}
      """
    When isaac is run with "config validate"
    Then the stderr contains "vessels.wavecrest"
    And the stderr contains "defined in both isaac.edn and vessels/wavecrest.edn"
    And the exit code is 1

  Scenario: malformed EDN in a config file is reported with the file path
    Given the isaac file "config/vessels/wavecrest.edn" exists with:
      """
      {:captain "Cordelia"
      """
    When isaac is run with "config validate"
    Then the stderr contains "vessels/wavecrest.edn"
    And the stderr contains "EDN syntax error"
    And the exit code is 1

  Scenario: ${VAR} references are substituted from the environment
    Given environment variable "MXGN_VESSEL_CAPTAIN" is "Cordelia"
    And the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.mxgn.vessels {:local/root "target/test-mxgn-composition/vessels-module"}}
       :vessels {:helm-station {:captain "${MXGN_VESSEL_CAPTAIN}"}}}
      """
    And stdin is:
      """
      REVEAL
      """
    When isaac is run with "config get vessels.helm-station.captain --reveal"
    Then the stdout contains "Cordelia"
    And the exit code is 0
