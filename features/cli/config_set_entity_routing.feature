Feature: Config set / unset — entity-file routing mechanics are generic (isaac-601n)
  Moved from isaac-agent's features/config/cli.feature (the set/unset
  portion not covered by config_set_unset.feature) — `config set`/`unset`
  decide WHERE a write lands: inline in isaac.edn, the entity's existing
  `<entity-dir>/<id>.edn` file, its `<id>.md` frontmatter when that's where
  the entity lives, or a freshly created entity file when
  `:prefer-entity-files` is set. isaac.foundation.config.mutate owns this routing
  generically; agent's copy only ever exercised it through :crew.
  `:prefer-entity-files` is foundation's own existing base schema field
  (it shows up in config_defaults.feature's own root-field listing).

  Three isaac-agent scenarios did NOT move here — they exercise the
  companion (`:soul`-like) .md mechanic specifically ("set writes soul to
  the companion .md when it already exists", "set creates a companion .md
  when a new soul exceeds 64 characters", "set writes short soul inline in
  the entity file"). At the time, the config-LOAD side of that mechanic
  (isaac.foundation.config.companions/companion-md-relative) was hard-coded to kinds
  :crew/:berths, even though the config-SET side (isaac.foundation.config.mutate/
  companion-spec) already read a module's own `:companion` descriptor
  generically. isaac-kcck generalized the load side and moved those three
  scenarios (plus two companion `config get`/`validate` scenarios) to
  config_companion.feature, with their own fixture module.

  One isaac-agent scenario did not move because it's a title-identical
  duplicate within the SAME original file family: "set errors on a path
  the schema does not recognize" — literally the same scenario title (and
  mechanism) already in set_unset.feature's mapping, itself deleted there
  as redundant with config_set_undeclared_key.feature. Not moved again here.

  Fixture module marigold.601n.routing contributes a `:vessels` entity-dir
  table with `:captain` (string) and `:effort` (int) fields. Manifest-only
  — no :factory, deps.edn, or src.

  Background:
    Given an empty Isaac root at "target/test-601n-routing"
    And the isaac file "vessels-module/resources/isaac-manifest.edn" exists with:
      """
      {:id :marigold.601n.routing
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
      {:modules {:marigold.601n.routing {:local/root "target/test-601n-routing/vessels-module"}}}
      """

  Scenario: set writes a new entity to isaac.edn by default
    When isaac is run with "config set vessels.cordelia.captain Atticus"
    Then the isaac file "config/isaac.edn" EDN contains:
      | path                  | value   |
      | vessels.cordelia.captain | Atticus |
    And the exit code is 0

  Scenario: set writes to the existing entity file when one already defines the key
    Given the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value   |
      | captain | Atticus |
    When isaac is run with "config set vessels.cordelia.captain Marlow"
    Then the isaac file "config/vessels/cordelia.edn" EDN contains:
      | path    | value  |
      | captain | Marlow |
    And the isaac file "config/isaac.edn" does not contain "cordelia"
    And the exit code is 0

  Scenario: set writes to isaac.edn when the entity is already defined there
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.601n.routing {:local/root "target/test-601n-routing/vessels-module"}}
       :vessels {:cordelia {:captain "Atticus"}}}
      """
    When isaac is run with "config set vessels.cordelia.captain Marlow"
    Then the isaac file "config/isaac.edn" EDN contains:
      | path                     | value  |
      | vessels.cordelia.captain | Marlow |
    And the isaac file "config/vessels/cordelia.edn" does not exist
    And the exit code is 0

  Scenario: set writes new entities to entity files when prefer-entity-files is true
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules             {:marigold.601n.routing {:local/root "target/test-601n-routing/vessels-module"}}
       :prefer-entity-files true}
      """
    When isaac is run with "config set vessels.cordelia.captain Atticus"
    Then the isaac file "config/vessels/cordelia.edn" EDN contains:
      | path    | value   |
      | captain | Atticus |
    And the isaac file "config/isaac.edn" does not contain "cordelia"
    And the exit code is 0

  Scenario: set edits the frontmatter of an entity that lives in <id>.md
    Given the isaac file "config/vessels/cordelia.md" exists with:
      """
      ---
      captain: Atticus
      ---

      You are Cordelia.
      """
    When isaac is run with "config set vessels.cordelia.captain Marlow"
    Then the isaac file "config/vessels/cordelia.md" does not contain "Atticus"
    And the isaac file "config/vessels/cordelia.edn" does not exist
    And the exit code is 0
    When isaac is run with "config get vessels.cordelia.captain"
    Then the stdout contains "Marlow"
    And the exit code is 0

  Scenario: unset removes a frontmatter field from an entity that lives in <id>.md
    Given the isaac file "config/vessels/cordelia.md" exists with:
      """
      ---
      captain: Atticus
      effort: 3
      ---

      You are Cordelia.
      """
    When isaac is run with "config unset vessels.cordelia.effort"
    Then the isaac file "config/vessels/cordelia.md" does not contain "effort"
    And the isaac file "config/vessels/cordelia.edn" does not exist
    And the exit code is 0
    When isaac is run with "config get vessels.cordelia"
    Then the stdout does not contain "effort"
    And the stdout contains "Atticus"
    And the exit code is 0

  Scenario: set refuses to write a value that fails type validation
    When isaac is run with "config set vessels.cordelia.effort not-a-number"
    Then the stderr contains "effort"
    And the isaac file "config/isaac.edn" does not contain "not-a-number"
    And the exit code is 1

  Scenario: unset removes a key from the file where it lives
    Given the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value   |
      | captain | Atticus |
      | effort  | 3       |
    When isaac is run with "config unset vessels.cordelia.effort"
    Then the isaac file "config/vessels/cordelia.edn" EDN contains:
      | path    | value   |
      | captain | Atticus |
    And the isaac file "config/vessels/cordelia.edn" does not contain "effort"
    And the exit code is 0

  Scenario: unset that empties an entity file deletes it
    Given the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value   |
      | captain | Atticus |
    When isaac is run with "config unset vessels.cordelia.captain"
    Then the isaac file "config/vessels/cordelia.edn" does not exist
    And the exit code is 0

  Scenario: set writes a whole entity read from stdin
    Given stdin is:
      """
      {:captain "Marlow" :effort 5}
      """
    When isaac is run with "config set vessels.cordelia -"
    Then the isaac file "config/isaac.edn" EDN contains:
      | path                     | value  |
      | vessels.cordelia.captain | Marlow |
      | vessels.cordelia.effort  | 5      |
    And the exit code is 0

  Scenario: set replaces an existing entity rather than merging
    Given the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value   |
      | captain | Atticus |
      | effort  | 3       |
    And stdin is:
      """
      {:captain "Marlow"}
      """
    When isaac is run with "config set vessels.cordelia -"
    Then the isaac file "config/vessels/cordelia.edn" EDN contains:
      | path    | value  |
      | captain | Marlow |
    And the isaac file "config/vessels/cordelia.edn" does not contain "effort"
    And the exit code is 0
