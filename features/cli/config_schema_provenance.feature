Feature: Config schema renders manifest-contributed fields with a provenance prefix (isaac-601n)
  Redundancy check against isaac-agent's features/config/schema_cli_options.
  feature (7 scenarios), same call as isaac-mxgn's Group E for cli.feature's
  schema-drilling scenarios. Four of the seven are pure duplicates of
  foundation's own features/cli/config_schema.feature (isaac-3y69) and were
  deleted rather than moved — same generic mechanism, different table names:
    - "comm slot :type lists user-configurable comm kinds from manifests" ↔
      config_schema.feature's "a [:registered-in?] field lists the berth's
      registered entries as options, excluding non-configurable ones".
    - "config schema for a manifest-supplied field errors when the module
      isn't declared" ↔ config_schema.feature's "config schema gives a
      friendly error for a 2-segment typo, not a slot-id rewrite" — both
      produce the identical generic "Path not found in config schema: …"
      for a path the composed schema doesn't have, regardless of WHY the
      module isn't there (typo vs. undeclared module).
    - "config schema renders the statically-declared tool config fields" ↔
      config_schema.feature's "config schema drills into a single field and
      shows its description" — a static (non-dynamic-schema) field's
      rendering is the same mechanism regardless of table.

  The other three are NOT redundant — confirmed by reading
  config_schema.feature's own fixture and scenarios line by line:
  isaac.foundation.schema.dynamic/merge-dynamic-fields does generically annotate a
  module-merged field with `:isaac/variant`, and isaac.foundation.config.schema.term
  does generically render it as a `[variant]` prefix (both foundation-
  owned, no agent concept) — but no EXISTING config_schema.feature scenario
  ever drills into a dynamic-schema-merged field to assert that prefix, or
  checks the "with no modules, no merged fields at all" negative case. That
  is real, uncovered behavior. These three scenarios pin it, reusing
  config_schema.feature's own bridge/longwave shape with fresh ids
  (marigold.601n.bridge / marigold.601n.longwave / marigold.601n.skybeam)
  so module discovery's manifest cache can't confuse this fixture with the
  repo's builtin one, plus a second contributing kind (skybeam) so "every
  manifest-supplied field inline" has more than one variant to prove it's
  not special-casing a single contributor.

  Background:
    Given an empty Isaac root at "/tmp/601n-provenance"
    And the isaac file "/tmp/modules/marigold.601n.bridge/deps.edn" exists with:
      """
      {:paths ["resources" "src"]}
      """
    And the isaac file "/tmp/modules/marigold.601n.bridge/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.601n.bridge
       :version "1.0.0"
       :factory marigold.601n.bridge/create-module
       :berths  {:marigold.601n.bridge/comm
                 {:description "Relay channels — a fixture config berth at [:relays]."
                  :schema      {:type       :map
                                :key-spec   {:type :keyword}
                                :value-spec {:type   :map
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
                                                                               :message     "not a registered impl of :marigold.601n.bridge/comm"
                                                                               :validations [:present?
                                                                                             [:registered-in? :marigold.601n.bridge/comm]]}
                                                                        :keeper {:type        :string
                                                                               :description "Keeper tending this relay"}}
                                                       :dynamic-schema [:extra-schema]
                                                       :factory        marigold.601n.bridge.comm/create-comm-node!}}}}}}
      """
    And the isaac file "/tmp/modules/marigold.601n.bridge/src/marigold/601n/bridge.clj" exists with:
      """
      (ns marigold.601n.bridge
        (:require
          [isaac.foundation.module.protocol :as module]
          [marigold.601n.bridge.comm]))

      (defn create-module []
        (module/module))
      """
    And the isaac file "/tmp/modules/marigold.601n.bridge/src/marigold/601n/bridge/comm.clj" exists with:
      """
      (ns marigold.601n.bridge.comm)

      (defmulti create-comm-node! (fn [_path slice] (:type slice)))
      """
    And the isaac file "/tmp/modules/marigold.601n.longwave/deps.edn" exists with:
      """
      {:paths ["resources" "src"]}
      """
    And the isaac file "/tmp/modules/marigold.601n.longwave/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.601n.longwave
       :version "0.1.0"
       :factory marigold.601n.longwave/create-module
       :deps    {:marigold.601n.bridge {:local/root "/tmp/modules/marigold.601n.bridge"}}
       :marigold.601n.bridge/comm
       {:longwave {:extra-schema {:helm/freq {:type :string :description "Longwave carrier frequency"}}}}}
      """
    And the isaac file "/tmp/modules/marigold.601n.longwave/src/marigold/601n/longwave.clj" exists with:
      """
      (ns marigold.601n.longwave
        (:require
          [isaac.foundation.module.protocol :as module]
          [marigold.601n.bridge.comm :as bridge.comm]))

      (defn create-module []
        (module/module))

      (defmethod bridge.comm/create-comm-node! :longwave [path slice]
        {:type :longwave :path path :keeper (:keeper slice)})
      """
    And the isaac file "/tmp/modules/marigold.601n.skybeam/deps.edn" exists with:
      """
      {:paths ["resources" "src"]}
      """
    And the isaac file "/tmp/modules/marigold.601n.skybeam/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.601n.skybeam
       :version "0.1.0"
       :factory marigold.601n.skybeam/create-module
       :deps    {:marigold.601n.bridge {:local/root "/tmp/modules/marigold.601n.bridge"}}
       :marigold.601n.bridge/comm
       {:skybeam {:extra-schema {:beacon-code {:type :string :description "Skybeam beacon code"}}}}}
      """
    And the isaac file "/tmp/modules/marigold.601n.skybeam/src/marigold/601n/skybeam.clj" exists with:
      """
      (ns marigold.601n.skybeam
        (:require
          [isaac.foundation.module.protocol :as module]
          [marigold.601n.bridge.comm :as bridge.comm]))

      (defn create-module []
        (module/module))

      (defmethod bridge.comm/create-comm-node! :skybeam [path slice]
        {:type :skybeam :path path :keeper (:keeper slice)})
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.601n.bridge   {:local/root "/tmp/modules/marigold.601n.bridge"}
                 :marigold.601n.longwave {:local/root "/tmp/modules/marigold.601n.longwave"}
                 :marigold.601n.skybeam  {:local/root "/tmp/modules/marigold.601n.skybeam"}}
       :relays  {:helm-station {:type :longwave :keeper "Atticus"}}}
      """

  Scenario: config schema renders a manifest-supplied field with a provenance prefix
    When isaac is run with "config schema relays.value.helm/freq"
    Then the stdout matches:
      | pattern                |
      | helm/freq              |
      | \[longwave\]           |
      | string                 |
      | Longwave carrier frequency |
    And the exit code is 0

  Scenario: config schema <table>.value renders every manifest-supplied field inline, not grouped by type
    When isaac is run with "config schema relays.value"
    Then the stdout matches:
      | pattern      |
      | keeper       |
      | helm/freq    |
      | \[longwave\] |
      | beacon-code  |
      | \[skybeam\]  |
    And the stdout does not match:
      | pattern            |
      | type:\s+longwave\s |
      | type:\s+skybeam\s  |
    And the exit code is 0

  Scenario: config schema <table>.value with no contributing modules shows only base fields
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.601n.bridge {:local/root "/tmp/modules/marigold.601n.bridge"}}}
      """
    When isaac is run with "config schema relays.value"
    Then the stdout matches:
      | pattern |
      | type    |
      | keeper  |
    And the stdout does not match:
      | pattern      |
      | helm/freq    |
      | \[longwave\] |
      | beacon-code  |
      | \[skybeam\]  |
    And the exit code is 0
