Feature: Config get / validate — redaction, sources, and overlay mechanics are generic (isaac-mxgn)
  Moved from isaac-agent's features/config/cli.feature — `config get`
  (whole-config and by-path), `config sources`, and `config validate`
  (including the stdin `-` and `--as` overlay forms) are foundation's own
  mechanics. Sensitive values sourced from ${VAR} substitution are redacted
  by default; --reveal requires typing "REVEAL" on stdin to surface real
  values. Agent's copy only ever exercised this through :crew/:models/
  :providers; this fixture proves the same mechanism with a fixture
  entity-dir and no agent concept in the picture.

  Fixture module marigold.mxgn.charts contributes a `:vessels` entity-dir
  table with a required `:captain` field (to trigger a generic validation
  error) and a `:beacon-code` field (to trigger ${VAR} redaction). Manifest-
  only — no :factory, deps.edn, or src.

  Background:
    Given an empty Isaac root at "target/test-mxgn-get-validate"
    And the isaac file "charts-module/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.mxgn.charts
       :version "0.1.0"
       :isaac.config/schema
       {:vessels {:entity-dir          "vessels"
                  :merge-root-entity? true
                  :schema              {:name       "vessel table"
                                       :type       :map
                                       :key-spec   {:type :string}
                                       :value-spec {:name   :vessel
                                                   :type   :map
                                                   :schema {:captain     {:type :string :required true}
                                                           :beacon-code {:type :string}
                                                           :call-sign   {:type :string}}}}}}}
      """
    And the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.mxgn.charts {:local/root "target/test-mxgn-get-validate/charts-module"}}
       :vessels {:helm-station {:captain "Cordelia"}}}
      """

  Scenario: config get redacts resolved ${VAR} values by default; an unresolved ${VAR} is named in :unresolved-refs
    # Current behavior (post-isaac-dnib): the whole-config dump does not
    # inline an unresolved substitution at its own path — it drops the key
    # (same as absent) and surfaces the miss once, in a top-level
    # :unresolved-refs map keyed by dotted path. This differs from
    # isaac-agent's original scenario, which expected an inline
    # "<VAR:UNRESOLVED>" marker at the field itself; that marker form is
    # gone from current output (confirmed empirically against this fixture).
    Given environment variable "MXGN_BEACON_CODE" is "sk-test-123"
    And the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.mxgn.charts {:local/root "target/test-mxgn-get-validate/charts-module"}}
       :vessels {:helm-station {:captain     "Cordelia"
                                :beacon-code "${MXGN_BEACON_CODE}"
                                :call-sign   "${MXGN_UNSET_CODE}"}}}
      """
    When isaac is run with "config get"
    Then the stdout contains "<MXGN_BEACON_CODE:redacted>"
    And the stdout contains ":unresolved-refs"
    And the stdout contains "vessels.helm-station.call-sign"
    And the stdout contains "MXGN_UNSET_CODE"
    And the stdout does not contain "sk-test-123"
    And the exit code is 0

  Scenario: config get --raw prints pre-substitution values
    Given environment variable "MXGN_BEACON_CODE" is "sk-test-123"
    And the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.mxgn.charts {:local/root "target/test-mxgn-get-validate/charts-module"}}
       :vessels {:helm-station {:captain "Cordelia" :beacon-code "${MXGN_BEACON_CODE}"}}}
      """
    When isaac is run with "config get vessels.helm-station.beacon-code --raw"
    Then the stdout contains "${MXGN_BEACON_CODE}"
    And the stdout does not contain "sk-test-123"
    And the stdout does not contain "redacted"
    And the exit code is 0

  Scenario: config get --reveal shows real values after typed confirmation
    Given environment variable "MXGN_BEACON_CODE" is "sk-test-123"
    And the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.mxgn.charts {:local/root "target/test-mxgn-get-validate/charts-module"}}
       :vessels {:helm-station {:captain "Cordelia" :beacon-code "${MXGN_BEACON_CODE}"}}}
      """
    And stdin is:
      """
      REVEAL
      """
    When isaac is run with "config get vessels.helm-station.beacon-code --reveal"
    Then the stderr contains "type REVEAL to confirm:"
    And the stdout contains "sk-test-123"
    And the exit code is 0

  Scenario: config get --reveal refuses without typed confirmation
    Given environment variable "MXGN_BEACON_CODE" is "sk-test-123"
    And the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.mxgn.charts {:local/root "target/test-mxgn-get-validate/charts-module"}}
       :vessels {:helm-station {:captain "Cordelia" :beacon-code "${MXGN_BEACON_CODE}"}}}
      """
    And stdin is:
      """
      blah
      """
    When isaac is run with "config get vessels.helm-station.beacon-code --reveal"
    Then the stderr contains "type REVEAL to confirm:"
    And the stderr contains "Refusing to reveal config."
    And the stdout does not contain "sk-test-123"
    And the exit code is 1

  Scenario: config sources lists contributing files
    Given the isaac file "config/vessels/wavecrest.edn" exists with:
      """
      {:captain "Marlow"}
      """
    When isaac is run with "config sources"
    Then the stdout matches:
      | pattern                    |
      | config/isaac\.edn          |
      | config/vessels/wavecrest\.edn |
    And the exit code is 0

  Scenario: validate passes for a well-formed config
    When isaac is run with "config validate"
    Then the stdout contains "OK"
    And the exit code is 0

  Scenario: validate reports errors with exit code 1
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.mxgn.charts {:local/root "target/test-mxgn-get-validate/charts-module"}}
       :vessels {:helm-station {}}}
      """
    When isaac is run with "config validate"
    Then the stderr contains "vessels.helm-station.captain"
    And the stderr contains "is required"
    And the exit code is 1

  Scenario: validate reports warnings but still exits 0
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules      {:marigold.mxgn.charts {:local/root "target/test-mxgn-get-validate/charts-module"}}
       :vessels      {:helm-station {:captain "Cordelia"}}
       :experimental {:feature-flag true}}
      """
    When isaac is run with "config validate"
    Then the stderr contains "warning"
    And the stderr contains "experimental"
    And the stderr contains "unknown key"
    And the stdout contains "OK"
    And the exit code is 0

  Scenario: validate reads stdin as the full config and ignores on-disk files
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:broken-key-that-should-error true}
      """
    And stdin is:
      """
      {:modules {:marigold.mxgn.charts {:local/root "target/test-mxgn-get-validate/charts-module"}}
       :vessels {:helm-station {:captain "Cordelia"}}}
      """
    When isaac is run with "config validate -"
    Then the stdout contains "valid"
    And the exit code is 0

  Scenario: validate --as overlays stdin at the given config path before validating
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.mxgn.charts {:local/root "target/test-mxgn-get-validate/charts-module"}}
       :vessels {}}
      """
    And stdin is:
      """
      {:captain "Cordelia"}
      """
    When isaac is run with "config validate --as vessels.helm-station -"
    Then the stdout contains "valid"
    And the exit code is 0

  Scenario: validate --as rejects file-path style with a hint to use a config path
    Given stdin is:
      """
      {:captain "Cordelia"}
      """
    When isaac is run with "config validate --as vessels/wavecrest.edn -"
    Then the stderr contains "config path"
    And the exit code is 1

  Scenario: get prints a scalar value by dotted keyword path
    When isaac is run with "config get vessels.helm-station.captain"
    Then the stdout contains "Cordelia"
    And the exit code is 0

  Scenario: get prints a scalar value by bracket keyword path
    When isaac is run with "config get vessels[:helm-station].captain"
    Then the stdout contains "Cordelia"
    And the exit code is 0

  Scenario: get prints a nested structure as EDN
    When isaac is run with "config get vessels.helm-station"
    Then the stdout contains ":captain"
    And the stdout contains "Cordelia"
    And the exit code is 0

  Scenario: get exits non-zero for a missing key
    When isaac is run with "config get vessels.helm-station.nope"
    Then the stderr contains "not found: vessels.helm-station.nope"
    And the exit code is 1
