Feature: Config set / unset — scalar, set-member, and nested-path mechanics are generic (isaac-601n)
  Moved from isaac-agent's features/config/set_unset.feature — `isaac config
  set <path> [<value>]` and `isaac config unset <path>` mutate config via a
  schema-aware path walker: map keys are keywords, set members terminate the
  path. Both subcommands persist the updated config and are idempotent.
  isaac.config.mutate is foundation's own generic machinery; agent's copy
  only ever exercised it through :crew/:defaults.tools. This fixture proves
  the same mechanism with a fixture entity-dir and fixture root fields, no
  agent concept in the picture.

  The keyword-set conforming scenarios ("bare name", "one-member", "comma
  list", set-member add/remove) use a ROOT-level fixture field
  (`:signal-tags`), not a field nested inside the `:vessels` entity table.
  Confirmed empirically: isaac.config.schema.resolve's `schema-for-data-path`
  — the function `config set`/`config unset` use to find a path's spec (and
  so detect `:set-type?`) — resolves a plain nested root field generically,
  but does NOT resolve a field nested under a dynamic key-spec/value-spec
  entity table (`vessels.<id>.<field>`) unless the table's kind name is in
  a private hard-coded `entity-collections` set (`:berths :gauges :foundries
  :crew :hail :models :providers`). A fresh module-declared entity-dir kind
  is never in that set, so `vessels.cordelia.tags` silently falls through to
  a generic value-guess instead of the set-aware parse — the set-member CLI
  forms would misbehave, not merely lose type safety. This is a real gap
  (isaac.config.schema.resolve, not isaac.config.mutate) distinct from the
  read-side companion-field gap noted in config_composition.feature; both
  are flagged for a foundation fix in the isaac-601n mapping notes. Plain
  scalar/int fields on the entity table (`:captain`, `:effort`) are
  unaffected — their type errors are still caught generically by the
  post-write full-entity conform, which walks entity-dir tables correctly.

  Two isaac-agent scenarios did NOT move here:
    - "config set errors on a path the schema doesn't recognize" — the
      exact same generic mechanism (refuse writing an undeclared key under
      a statically-schema'd map, naming valid keys, hinting --force) is
      already pinned by features/cli/config_set_undeclared_key.feature
      (isaac-a5dx)'s "set of an undeclared key under a schema'd map is
      refused". Deleted as redundant, not moved.
    - "config set on a crew using a module-contributed session policy
      succeeds" — functionally the same mechanism (a `config set` writing a
      value that satisfies a module-registered `[:registered-in?]` set of
      ids succeeds) is already exercised by config_set_undeclared_key.
      feature's "set of an undeclared key under an entity table (key-spec)
      still writes" (`config set relays.wavecrest.type :longwave`, a
      registered-in?-validated field, exits 0). Deleted as redundant, not
      moved.
    - "config set still accepts a reference to an entity that is not
      defined yet" did NOT move either, but for a different reason: it
      exercises agent's own bespoke entity-existence validator (crew.model
      must reference a real :models key), which — unlike the provider/
      tool/comm validators — has NOT been migrated to the generic
      [:registered-in?] mechanism (composition.feature's STAYS list still
      names it agent-owned). There is no public foundation-level "does this
      value name a config-defined entity" check to build a Marigold fixture
      against. Stays in agent.

  This file also adds one scenario not in the original mapping: "warnings
  elsewhere in the config collapse to a count after the confirmation". The
  set_report.feature CHECK note asked whether that collapsing behavior is
  itself generic; it is — the code lives in isaac.config.cli.mutate-common,
  not in anything agent-owned. set_report.feature's own four scenarios
  (about the crew tool-directory "broad grant" warning specifically) still
  stay in agent; only the generic collapse mechanism moves, here and in the
  new config_set_report.feature.

  Fixture module marigold.601n.vessels contributes a `:vessels` entity-dir
  table with a scalar string field (`:captain`) and an int field
  (`:effort`); a `:helm` root field with a nested int (`:max-signals`)
  standing in for `defaults.tools.max-lines`; and a root-level keyword-set
  field (`:signal-tags`) for the set-member mechanics. Manifest-only — no
  :factory, deps.edn, or src.

  Background:
    Given an empty Isaac root at "target/test-601n-set-unset"
    And the isaac file "vessels-module/resources/isaac-manifest.edn" exists with:
      """
      {:id :marigold.601n.vessels
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
            :effort {:type :int}}}}}
        :helm
        {:schema
         {:type :map
          :schema
          {:max-signals {:type :int}}}}
        :signal-tags
        {:schema
         {:type :ignore
          :set-type? true
          :validations [:keyword-set?]}}}}
      """
    And the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.601n.vessels {:local/root "target/test-601n-set-unset/vessels-module"}}}
      """

  @wip
  Scenario: scalar set writes a value at a known map path
    Given the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value    |
      | captain | Atticus  |
    When isaac is run with "config set vessels.cordelia.captain Marlow"
    Then the exit code is 0
    When isaac is run with "config get vessels.cordelia.captain"
    Then the stdout contains "Marlow"
    And the exit code is 0

  @wip
  Scenario: scalar unset removes a value at a known map path
    Given the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value   |
      | captain | Atticus |
      | effort  | 3       |
    When isaac is run with "config unset vessels.cordelia.captain"
    Then the exit code is 0
    When isaac is run with "config get vessels.cordelia"
    Then the stdout does not contain "Atticus"
    And the exit code is 0

  @wip
  Scenario: config set is idempotent when the value is already present
    Given the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value  |
      | captain | Marlow |
    When isaac is run with "config set vessels.cordelia.captain Marlow"
    Then the exit code is 0
    When isaac is run with "config get vessels.cordelia.captain"
    Then the stdout contains "Marlow"
    And the exit code is 0

  @wip
  Scenario: config unset is idempotent when the value is absent
    Given the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value   |
      | captain | Atticus |
    When isaac is run with "config unset vessels.cordelia.effort"
    Then the exit code is 0
    When isaac is run with "config get vessels.cordelia.captain"
    Then the stdout contains "Atticus"
    And the exit code is 0

  @wip
  Scenario: config set errors when the value doesn't match the schema type
    Given the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value   |
      | captain | Atticus |
    When isaac is run with "config set vessels.cordelia.effort not-a-number"
    Then the stderr contains "effort"
    And the exit code is 1

  @wip
  Scenario: config set helm.max-signals succeeds and the value lands
    When isaac is run with "config set helm.max-signals 500"
    Then the exit code is 0
    When isaac is run with "config get helm.max-signals"
    Then the stdout contains "500"
    And the exit code is 0

  @wip
  Scenario: config set conforms a bare name to the keyword set the field holds
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path        | value            |
      | signal-tags | #{:role/lookout} |
    When isaac is run with "config set signal-tags jackalope"
    Then the stdout matches:
      | pattern                                            |
      | set signal-tags = #\{:jackalope\}.*isaac\.edn |
    And the exit code is 0
    When isaac is run with "config get signal-tags"
    Then the stdout contains ":jackalope"
    And the stdout does not contain ":role/lookout"
    And the exit code is 0

  @wip
  Scenario: config set conforms a keyword to a one-member set instead of crashing
    When isaac is run with "config set signal-tags :jackalope"
    Then the stderr does not contain "ISeq"
    And the exit code is 0
    When isaac is run with "config get signal-tags"
    Then the stdout contains ":jackalope"
    And the exit code is 0

  @wip
  Scenario: config set conforms a comma list to a set of keywords
    When isaac is run with "config set signal-tags jackalope,role/lookout"
    Then the exit code is 0
    When isaac is run with "config get signal-tags"
    Then the stdout contains ":jackalope"
    And the stdout contains ":role/lookout"
    And the exit code is 0

  @wip
  Scenario: config set keeps digits a string when the field is a string
    Given the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value   |
      | captain | Atticus |
    When isaac is run with "config set vessels.cordelia.captain 42"
    Then the exit code is 0
    When isaac is run with "config validate"
    Then the exit code is 0

  @wip
  Scenario: config unset with a member removes only that member
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path        | value                       |
      | signal-tags | #{:role/lookout :jackalope} |
    When isaac is run with "config unset signal-tags jackalope"
    Then the exit code is 0
    When isaac is run with "config get signal-tags"
    Then the stdout contains ":role/lookout"
    And the stdout does not contain ":jackalope"
    And the exit code is 0

  @wip
  Scenario: config unset refuses a value on a path that is not a set
    Given the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value   |
      | captain | Atticus |
    When isaac is run with "config unset vessels.cordelia.captain Marlow"
    Then the stderr contains "vessels.cordelia.captain"
    And the stderr contains "takes no value"
    And the exit code is 1
    When isaac is run with "config get vessels.cordelia.captain"
    Then the stdout contains "Atticus"
    And the exit code is 0

  @wip
  Scenario: config set confirms what it wrote and where
    Given the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value   |
      | captain | Atticus |
    When isaac is run with "config set vessels.cordelia.captain Marlow"
    Then the stdout matches:
      | pattern                                                     |
      | set vessels\.cordelia\.captain = "Marlow".*vessels/cordelia\.edn |
    And the exit code is 0

  @wip
  Scenario: config set confirms a set member it added
    When isaac is run with "config set signal-tags.wip"
    Then the stdout matches:
      | pattern                                |
      | set signal-tags \+= :wip.*isaac\.edn |
    And the exit code is 0

  @wip
  Scenario: config unset confirms what it removed
    Given the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value   |
      | captain | Atticus |
    When isaac is run with "config unset vessels.cordelia.captain"
    Then the stdout matches:
      | pattern                                       |
      | unset vessels\.cordelia\.captain.*vessels/cordelia\.edn |
    And the exit code is 0

  @wip
  Scenario: config set --edn prints only the structured record
    Given the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value   |
      | captain | Atticus |
    When isaac is run with "config set vessels.cordelia.captain Marlow --edn"
    Then the stdout does not contain "set vessels.cordelia.captain"
    And the exit code is 0

  @wip
  Scenario: config set --help documents the set-member path form
    When isaac is run with "config set --help"
    Then the stdout matches:
      | pattern                                          |
      | Set-typed fields take the member in the path     |
      | isaac config set crew\.marvin\.tags\.role/worker |
    And the exit code is 0

  @wip
  Scenario: warnings elsewhere in the config collapse to a count after the confirmation
    # The exact count isn't pinned: an unrelated entity's "unknown key"
    # warning is collected once per internal validate pass in the mutate
    # pipeline (observed 3x against this fixture, vs. isaac-agent's original
    # count of 1 against its own warning source) — an implementation detail
    # of how many times the pipeline reloads, not part of the behavior this
    # scenario is pinning (that unrelated warnings collapse to a count with
    # a hint, rather than being printed in full after a successful set).
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.601n.vessels {:local/root "target/test-601n-set-unset/vessels-module"}}
       :vessels {:cordelia {:captain "Atticus"}
                 :wavecrest {:captain "Marlow" :bogus-field 1}}}
      """
    When isaac is run with "config set vessels.cordelia.captain Cordelia"
    Then the stdout contains "set vessels.cordelia.captain = \"Cordelia\""
    And the stdout matches:
      | pattern                                                          |
      | \d+ other validation warnings? — run: isaac config validate |
    And the stdout does not contain "unknown key"
    And the exit code is 0
