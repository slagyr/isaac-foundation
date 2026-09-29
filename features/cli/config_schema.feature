Feature: isaac config schema is generic — no module names it another module's berths (isaac-3y69)
  Foundation never names another module's config, berths, or ids. `isaac config
  schema` used to violate that: its "Try:" examples and --help description were
  hard-coded to agent's `crew`/`providers` fields, slot-id drilling
  (`comms.discord.token` -> `comms.value.token`) only worked for a hard-coded
  `#{"comms" "providers"}` table-name set, and the `options:` line for a
  `:type` field was wired through a comm-specific resolver that reads the
  agent's `:isaac.agent/comm` berth by name.

  This feature pins the generic replacement:
    - "Try:" examples and the --help description are generated from the
      loaded schema: the bare root command; the first non-map (scalar) root
      field in sorted order; the first dynamic-key root field (one with both
      a key-spec and a value-spec) in sorted order plus its `.value`; then
      `--tree`. With foundation alone (no modules), that's `hot-reload` (the
      first scalar) and `modules` (the first, and only, dynamic-key table).
      The static `--help` text always uses foundation's own base schema (no
      config/modules loaded), so it renders the same examples as the
      foundation-only root scenario below.
    - Slot-id drilling (`<table>.<slot-id>.<field>`, rewriting the slot
      segment to `.value`) applies to ANY schema node with both a key-spec
      and a value-spec, not a hard-coded table-name set. The 2-segment typo
      guard (a path needs 3+ segments before the rewrite applies) still
      holds.
    - The `options:` line for a field comes from that field's own
      `[:registered-in? <berth-id>]` (or `[:registered-in? <berth-id>
      <config-path>]`) validation: the accepted values are that berth's
      registered contribution ids, module-index and config-path union, same
      as the `:registered-in?` validator's own `:known` accepted-ids list. A
      contribution whose manifest entry is a map with `:configurable? false`
      is left out of the `options:` list (display only; validation of
      `:registered-in?` is unchanged) — the convention
      `isaac.config.comm-kinds` hand-rolls for `:isaac.agent/comm`,
      generalized so no CLI code names a berth by id.

  The fixture mirrors marigold.bridge / marigold.longwave (see
  features/cli/config_set_namespaced.feature) but uses its own ids —
  :marigold.cnfs.bridge / :marigold.cnfs.longwave — so they never collide
  with this repo's builtin `modules/marigold.bridge` / `modules/marigold.longwave`
  fixtures (same module id + `:local/root` path shape confuses module
  discovery's manifest cache into serving the repo's real fixture instead of
  the one this feature declares). Bridge declares a `:relays` dynamic-key
  config table with a `:type` field registered-in :marigold.cnfs.bridge/comm
  and a `:keeper` field; longwave contributes the `:longwave` entry (plus a
  `:hidden-relay` entry marked `:configurable? false` to exercise the
  exclusion).

  Background:
    Given an empty Isaac root at "/tmp/cnfs"
    And the isaac file "/tmp/modules/marigold.cnfs.bridge/deps.edn" exists with:
      """
      {:paths ["resources" "src"]}
      """
    And the isaac file "/tmp/modules/marigold.cnfs.bridge/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.cnfs.bridge
       :version "1.0.0"
       :factory marigold.cnfs.bridge/create-module
       :berths  {:marigold.cnfs.bridge/comm
                 {:description "Relay channels (longwave, skybeam, logbook, ...) — a fixture config berth at [:relays]."
                  :schema      {:type       :map
                                :key-spec   {:type :keyword}
                                :value-spec {:type   :map
                                             :name   :relay-node
                                             :schema {:extra-schema {:type :map}}}}
                  :config      {:path   [:relays]
                                :schema {:type        :map
                                         :key-spec    {:type :keyword}
                                         :name        "relay table"
                                         :description "Relay channel configurations"
                                         :value-spec  {:type           :map
                                                       :name           :relay-node
                                                       :schema         {:type {:type        :keyword
                                                                               :description "Relay kind to instantiate"
                                                                               :message     "not a registered impl of :marigold.cnfs.bridge/comm"
                                                                               :validations [:present?
                                                                                             [:registered-in? :marigold.cnfs.bridge/comm]]}
                                                                        :keeper {:type        :string
                                                                               :description "Keeper tending this relay"}}
                                                       :dynamic-schema [:extra-schema]
                                                       :factory        marigold.cnfs.bridge.comm/create-comm-node!}}}}}}
      """
    And the isaac file "/tmp/modules/marigold.cnfs.bridge/src/marigold/cnfs/bridge.clj" exists with:
      """
      (ns marigold.cnfs.bridge
        (:require
          [isaac.module.protocol :as module]
          [marigold.cnfs.bridge.comm]))

      (defn create-module []
        (module/module))
      """
    And the isaac file "/tmp/modules/marigold.cnfs.bridge/src/marigold/cnfs/bridge/comm.clj" exists with:
      """
      (ns marigold.cnfs.bridge.comm)

      (defmulti create-comm-node! (fn [_path slice] (:type slice)))
      """
    And the isaac file "/tmp/modules/marigold.cnfs.longwave/deps.edn" exists with:
      """
      {:paths ["resources" "src"]}
      """
    And the isaac file "/tmp/modules/marigold.cnfs.longwave/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.cnfs.longwave
       :version "0.1.0"
       :factory marigold.cnfs.longwave/create-module
       :deps    {:marigold.cnfs.bridge {:local/root "/tmp/modules/marigold.cnfs.bridge"}}
       :marigold.cnfs.bridge/comm
       {:longwave     {:extra-schema {:helm/freq {:type :string}}}
        :hidden-relay {:configurable? false}}}
      """
    And the isaac file "/tmp/modules/marigold.cnfs.longwave/src/marigold/cnfs/longwave.clj" exists with:
      """
      (ns marigold.cnfs.longwave
        (:require
          [isaac.module.protocol :as module]
          [marigold.cnfs.bridge.comm :as bridge.comm]))

      (defn create-module []
        (module/module))

      (defmethod bridge.comm/create-comm-node! :longwave [path slice]
        {:type :longwave :path path :keeper (:keeper slice)})
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.cnfs.bridge   {:local/root "/tmp/modules/marigold.cnfs.bridge"}
                 :marigold.cnfs.longwave {:local/root "/tmp/modules/marigold.cnfs.longwave"}}
       :relays  {:helm-station {:type :longwave :keeper "atticus"}}}
      """

  Scenario: root schema with no modules shows foundation's own fields and generated Try: examples
    Given the isaac file "isaac.edn" exists with:
      """
      {}
      """
    When isaac is run with "config schema"
    Then the stdout matches:
      | pattern                                     |
      | \[isaac\] isaac schema                      |
      | hot-reload\s+.*\[hot-reload\]                |
      | module-registry\s+.*\[module-registry\]      |
      | modules\s+.*\[modules\]                      |
      | Try:                                        |
      | (?m)^\s*isaac config schema$               |
      | isaac config schema hot-reload               |
      | (?m)^\s*isaac config schema modules$       |
      | isaac config schema modules\.value           |
      | isaac config schema --tree                   |
    And the stdout does not contain "crew"
    And the stdout does not contain "providers"
    And the exit code is 0

  Scenario: root schema lists a fixture module's dynamic-key config table
    When isaac is run with "config schema"
    Then the stdout matches:
      | pattern                    |
      | relays\s+.*\[relays\]      |
    And the exit code is 0

  Scenario: config schema <table> renders the map wrapper with key/value rows
    When isaac is run with "config schema relays"
    Then the stdout matches:
      | pattern                                  |
      | \[relays\] relay table schema            |
      | map of                                   |
      | key\s+keyword\s+\[relays\.key\]          |
      | value\s+.*relay-node\s+\[relays\.value\] |
      | Relay channel configurations             |
    And the exit code is 0

  Scenario: config schema <table>.value renders the entry fields
    When isaac is run with "config schema relays.value"
    Then the stdout matches:
      | pattern                                       |
      | \[relays\.value\] relay-node schema           |
      | keeper\s+string\s+\[relays\.value\.keeper\]       |
      | type\s+keyword\s+\[relays\.value\.type\]      |
    And the exit code is 0

  Scenario: config schema <table>.key resolves the map-key spec
    When isaac is run with "config schema relays.key"
    Then the stdout matches:
      | pattern                       |
      | \[relays\.key\] schema        |
      | keyword\s+\[relays\.key\]     |
    And the exit code is 0

  Scenario: config schema drills into a single field and shows its description
    When isaac is run with "config schema relays.value.keeper"
    Then the stdout matches:
      | pattern                                  |
      | \[relays\.value\.keeper\] schema           |
      | :keeper\s+string\s+\[relays\.value\.keeper\] |
      | Keeper tending this relay           |
    And the exit code is 0

  Scenario: config schema drills through a slot id on the fixture table
    When isaac is run with "config schema relays.helm-station.keeper"
    Then the stdout matches:
      | pattern                                       |
      | :keeper\s+string\s+\[relays\.helm-station\.keeper\] |
      | Keeper tending this relay                |
    And the exit code is 0

  Scenario: config schema gives a friendly error for a 2-segment typo, not a slot-id rewrite
    When isaac is run with "config schema relays.valued"
    Then the stderr contains "Path not found in config schema: relays.valued"
    And the stderr does not contain "Exception"
    And the exit code is 1

  Scenario: config schema --tree expands the fixture table's named sub-schema
    When isaac is run with "config schema --tree"
    Then the stdout matches:
      | pattern                             |
      | \[isaac\] isaac schema              |
      | \[relays\.value\] relay-node schema |
      | Keeper tending this relay      |
    And the exit code is 0

  Scenario: a [:registered-in?] field lists the berth's registered entries as options, excluding non-configurable ones
    When isaac is run with "config schema relays.value.type"
    Then the stdout matches:
      | pattern         |
      | options:.*longwave |
    And the stdout does not match:
      | pattern              |
      | options:.*hidden-relay |
    And the exit code is 0

  Scenario: help config lists the schema subcommand
    When isaac is run with "help config"
    Then the stdout matches:
      | pattern                                          |
      | schema \[schema-path\]\s+Print the config schema |
    And the exit code is 0

  Scenario: config schema --help describes --tree with generic examples and no other module's names
    When isaac is run with "config schema --help"
    Then the stdout matches:
      | pattern                     |
      | Usage: isaac config schema  |
      | --tree\s+Expand every named |
      | isaac config schema modules |
    And the stdout does not contain "crew"
    And the stdout does not contain "providers"
    And the exit code is 0
