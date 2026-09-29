Feature: schema-declared defaults and required fields (isaac-dnib)

  Micah's ruling (2026-09-29, final): the loaded config is a conformed-over-raw
  overlay, not a whole-root replace. Load raw (merged files) → conform
  against the schema → overlay the conformed values onto the raw config.
  Defaults and coercions apply, AND unknown/undeclared keys survive (a berth
  table, a crew/model/defaults entity, a root-level field, or a module
  coordinate can carry a key the schema doesn't know about — validation
  still warns about it, but it isn't silently dropped). apron 3.2.1 adds a
  `:default` spec key (fills an absent map key during coerce/conform) and a
  `:required true` shorthand for `:validations [:required]`.

  Per-action rule: runtime (`config get`, whatever a module reads) = the
  conformed-over-raw view, with a defaulted value in TEXT output marked
  `(default)` (`--edn`/`--json` stay plain data, no annotation). Writes
  (`config set`/`unset`/`reformat`) and `config get --raw` = raw only, built
  from the pre-conform merged files, not a stripped schema — `--raw` never
  shows a defaulted key. Validation = conform errors (including a missing
  `:required true` field) plus unknown-key warnings.

  The fixture is a single module — marigold.dflt.beacon — contributing a
  plain top-level :isaac.config/schema fragment (no berth needed) for
  :beacon: :power (int, :default 42) and :keeper (string, :required true).
  Root-level, not berth-owned, since a root-level field is the case that
  historically has NOT kept its conformed value. `:hot-reload` and
  `:modules` are foundation's own existing root fields, reused to pin the
  overlay's coercion and unknown-coordinate-key behavior without a second
  fixture. Ids are unique to this feature (never marigold.bridge /
  marigold.longwave) so module discovery's manifest cache can't confuse this
  fixture with the repo's builtin one.

  Background:
    Given an empty Isaac root at "/tmp/dflt"
    And the isaac file "/tmp/modules/marigold.dflt.beacon/deps.edn" exists with:
      """
      {:paths ["resources" "src"]}
      """
    And the isaac file "/tmp/modules/marigold.dflt.beacon/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.dflt.beacon
       :version "1.0.0"
       :factory marigold.dflt.beacon/create-module
       :isaac.config/schema
       {:beacon {:schema {:type        :map
                          :description "Beacon tuning parameters"
                          :schema      {:power  {:type        :int
                                                 :default     42
                                                 :description "The beacon's power level"}
                                        :keeper {:type        :string
                                                 :required    true
                                                 :description "Who's minding the beacon"}}}}}}
      """
    And the isaac file "/tmp/modules/marigold.dflt.beacon/src/marigold/dflt/beacon.clj" exists with:
      """
      (ns marigold.dflt.beacon
        (:require
          [isaac.module.protocol :as module]))

      (defn create-module []
        (module/module))
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.beacon {:local/root "/tmp/modules/marigold.dflt.beacon"}}}
      """

  @wip
  Scenario: config schema <table> shows the default and required marker for its fields
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.beacon {:local/root "/tmp/modules/marigold.dflt.beacon"}}
       :beacon  {:keeper "Atticus"}}
      """
    When isaac is run with "config schema beacon"
    Then the stdout matches:
      | pattern                                |
      | power\s+int\s+\[beacon\.power\]        |
      | default: 42                            |
      | keeper\s+string.*\*required            |
    And the exit code is 0

  @wip
  Scenario: config schema drilled to a single required field also shows the required marker
    When isaac is run with "config schema beacon.keeper"
    Then the stdout matches:
      | pattern    |
      | \*required |
    And the exit code is 0

  @wip
  Scenario: config schema --edn includes :default for a field with one
    When isaac is run with "config schema beacon.power --edn"
    Then the stdout matches:
      | pattern         |
      | :default\s+42   |
    And the exit code is 0

  @wip
  Scenario: config schema --edn includes :required for a field marked required
    When isaac is run with "config schema beacon.keeper --edn"
    Then the stdout matches:
      | pattern            |
      | :required\s+true   |
    And the exit code is 0

  @wip
  Scenario: config get on an absent key returns the schema default, annotated
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.beacon {:local/root "/tmp/modules/marigold.dflt.beacon"}}
       :beacon  {:keeper "Atticus"}}
      """
    When isaac is run with "config get beacon.power"
    Then the exit code is 0
    And the stdout contains "42"
    And the stdout contains "(default)"

  @wip
  Scenario: config get on a set key returns the set value, unannotated
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.beacon {:local/root "/tmp/modules/marigold.dflt.beacon"}}
       :beacon  {:keeper "Atticus" :power 7}}
      """
    When isaac is run with "config get beacon.power"
    Then the exit code is 0
    And the stdout contains "7"
    And the stdout does not contain "(default)"

  @wip
  Scenario: config get --edn on an absent key returns the plain default value, no annotation
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.beacon {:local/root "/tmp/modules/marigold.dflt.beacon"}}
       :beacon  {:keeper "Atticus"}}
      """
    When isaac is run with "config get beacon.power --edn"
    Then the exit code is 0
    And the stdout contains "42"
    And the stdout does not contain "(default)"

  @wip
  Scenario: config get --raw on an absent key omits the default — raw is what's set
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.beacon {:local/root "/tmp/modules/marigold.dflt.beacon"}}
       :beacon  {:keeper "Atticus"}}
      """
    When isaac is run with "config get beacon.power --raw"
    Then the exit code is 1
    And the stderr contains "not found: beacon.power"

  @wip
  Scenario: an unknown nested key under a schema'd root map survives in config get
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.beacon {:local/root "/tmp/modules/marigold.dflt.beacon"}}
       :beacon  {:keeper "Atticus" :sighting-log "kept in the margin"}}
      """
    When isaac is run with "config get beacon --edn"
    Then the exit code is 0
    And the stdout contains ":sighting-log"
    And the stdout contains "kept in the margin"
    And the stdout contains ":power"

  @wip
  Scenario: a coercible-but-wrong-typed value comes back coerced through config get
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.beacon {:local/root "/tmp/modules/marigold.dflt.beacon"}}
       :beacon    {:keeper "Atticus"}
       :hot-reload "true"}
      """
    When isaac is run with "config get hot-reload"
    Then the exit code is 0
    And the stdout matches:
      | pattern |
      | ^true$  |

  @wip
  Scenario: config get --raw preserves the original, uncoerced value
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.beacon {:local/root "/tmp/modules/marigold.dflt.beacon"}}
       :beacon    {:keeper "Atticus"}
       :hot-reload "true"}
      """
    When isaac is run with "config get hot-reload --raw"
    Then the exit code is 0
    And the stdout contains "\"true\""

  @wip
  Scenario: an undeclared module coordinate key survives in config get modules
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.beacon {:local/root "/tmp/modules/marigold.dflt.beacon"
                                        :pinned-by  "the crew that added it"}}}
      """
    When isaac is run with "config get modules --edn"
    Then the exit code is 0
    And the stdout contains ":pinned-by"
    And the stdout contains "the crew that added it"
    And the stdout contains ":local/root"

  @wip
  Scenario: a module coordinate with none of local/root, mvn/version, or git/url fails validation
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.beacon {:local/root "/tmp/modules/marigold.dflt.beacon"}
                 :adrift               {:pinned-by "nobody knows how"}}
       :beacon  {:keeper "Atticus"}}
      """
    When isaac is run with "config validate"
    Then the exit code is 1
    And the stderr contains "must include at least one of"

  @wip
  Scenario: config set of one key does not write the other field's default into the file
    When isaac is run with "config set beacon.keeper Atticus"
    Then the exit code is 0
    And the isaac file "isaac.edn" EDN contains:
      | path          | value   |
      | beacon.keeper | Atticus |
    And the isaac file "isaac.edn" does not contain "power"

  @wip
  Scenario: an explicit value equal to the default is kept as set, not pruned
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.beacon {:local/root "/tmp/modules/marigold.dflt.beacon"}}
       :beacon  {:keeper "Atticus" :power 42}}
      """
    When isaac is run with "config set beacon.keeper Cordelia"
    Then the exit code is 0
    And the isaac file "isaac.edn" EDN contains:
      | path          | value  |
      | beacon.keeper | Cordelia |
      | beacon.power  | 42     |

  @wip
  Scenario: config validate reports a missing required field
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.beacon {:local/root "/tmp/modules/marigold.dflt.beacon"}}
       :beacon  {:power 10}}
      """
    When isaac is run with "config validate"
    Then the exit code is 1
    And the stderr contains "beacon.keeper"
    And the stderr contains "is required"

  @wip
  Scenario: config validate passes when the required field is present
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.beacon {:local/root "/tmp/modules/marigold.dflt.beacon"}}
       :beacon  {:keeper "Atticus"}}
      """
    When isaac is run with "config validate"
    Then the exit code is 0
    And the stdout contains "OK - config is valid"

  @wip
  Scenario: an invalid field keeps its raw value; the rest of the map still conforms
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.dflt.beacon {:local/root "/tmp/modules/marigold.dflt.beacon"}}
       :beacon  {:keeper "Atticus" :power "lots"}}
      """
    When isaac is run with "config get beacon --edn"
    Then the exit code is 0
    And the stdout contains ":keeper \"Atticus\""
    And the stdout contains ":power \"lots\""
    When isaac is run with "config validate"
    Then the exit code is 1
    And the stderr contains "power"

  @wip
  Scenario: config schema lists namespaced coordinate keys by their full names
    When isaac is run with "config schema modules.value"
    Then the stdout matches:
      | pattern     |
      | :local/root |
      | :deps/root  |
      | :git/url    |
    And the stdout does not match:
      | pattern          |
      | (?m)^\s*:root\b |
    And the exit code is 0

  @wip
  Scenario: config schema drills into a namespaced coordinate key by its dotted path
    When isaac is run with "config schema modules.value.local/root"
    Then the stdout matches:
      | pattern                             |
      | \[modules\.value\.local/root\] schema |
      | :local/root                         |
    And the stderr does not contain "Path not found"
    And the exit code is 0
