Feature: Config set / unset report the result first, then a warning count for everything else (isaac-601n)
  Not a move of isaac-agent's features/config/set_report.feature — that
  file's four scenarios are all about a crew tool-directory "broad grant"
  warning, which is agent's own concept (crew tool-directory-allow), and
  stay in agent untouched. This file resolves that bean's own CHECK note:
  is the underlying "warnings elsewhere in the config collapse to a single
  count that points at `isaac config validate`" behavior itself generic?
  It is — the code lives in isaac.config.cli.mutate-common (foundation's
  own `config set`/`unset` result reporting), not in anything agent-owned.
  This file pins that generic mechanism with a Marigold fixture; the
  directory-grant-specific scenarios have no foundation-level equivalent to
  move (there's no generic "broad grant" concept), so they simply stay.

  A config mutation's first line is its outcome — the confirmation on
  stdout, or the error on stderr — before any labelled validation-warning
  count for issues elsewhere in the config.

  Fixture module marigold.601n.report contributes the same shape of
  `:vessels` entity-dir table as config_set_unset.feature (a fresh id to
  avoid any cross-file manifest-cache confusion). Manifest-only — no
  :factory, deps.edn, or src.

  Background:
    Given an empty Isaac root at "target/test-601n-set-report"
    And the isaac file "vessels-module/resources/isaac-manifest.edn" exists with:
      """
      {:id :marigold.601n.report
       :version "0.1.0"
       :isaac.config/schema
       {:vessels
        {:entity-dir "vessels"
         :merge-root-entity? true
         :schema
         {:name "vessel table"
          :type :map
          :key-spec {:type :string}
          :value-spec
          {:name :vessel
           :type :map
           :schema
           {:captain {:type :string}
            :effort {:type :int}}}}}}}
      """
    And the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.601n.report {:local/root "target/test-601n-set-report/vessels-module"}}
       :vessels {:cordelia {:captain "Atticus"}
                 :wavecrest {:captain "Marlow" :bogus-field 1}}}
      """

  Scenario: warnings elsewhere in the config collapse to a count after the confirmation
    When isaac is run with "config set vessels.cordelia.captain Cordelia"
    Then the stdout contains "set vessels.cordelia.captain = \"Cordelia\""
    And the stdout matches:
      | pattern                                                          |
      | \d+ other validation warnings? — run: isaac config validate |
    And the stdout does not contain "unknown key"
    And the exit code is 0

  Scenario: a refused set leads with the error, then the warning count
    When isaac is run with "config set vessels.cordelia.effort not-a-number"
    Then the stderr matches:
      | pattern                                                                   |
      | (?s)^error:.*vessels\.cordelia\.effort.*\d+ other validation warnings? |
    And the stderr does not contain "unknown key"
    And the exit code is 1
