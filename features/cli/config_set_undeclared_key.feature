Feature: config set/unset refuse a key the composed schema does not declare (isaac-a5dx)

  Split from isaac-cgxa: that bean fixed the path grammar (a namespaced
  segment stays one key, not two nested maps). This is the policy gap it
  left open — a set that would CREATE an unknown key inside a map the
  schema DOES know still wrote and only warned at load (isaac-nq4c). Now
  `config set`/`config unset` refuse a segment a STATIC schema'd map
  doesn't declare — same exit/message shape as a validator refusal
  (isaac-fun8) — naming the parent path and the keys that level knows.
  `--force` (isaac-9mkp) still writes; the load-time warning still fires.
  An OPEN entity table (`:key-spec`/`:value-spec`, e.g. :relays itself —
  any relay id) is unaffected: any id is still accepted without force.

  Reuses isaac-cgxa's fixture module (marigold.cgxa.bridge / .longwave):
  :relays is a :key-spec/:value-spec entity table whose per-entry schema
  merges longwave's `:helm/freq` / `:helm/outer` extra-schema in statically
  (already flattened into the composed schema) alongside the table's own
  `:type` / `:crew` fields.

  Background:
    Given an empty Isaac root at "/tmp/a5dx"
    And the isaac file "/tmp/modules/marigold.cgxa.bridge/deps.edn" exists with:
      """
      {:paths ["resources" "src"]}
      """
    And the isaac file "/tmp/modules/marigold.cgxa.bridge/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.cgxa.bridge
       :version "1.0.0"
       :factory marigold.cgxa.bridge/create-module
       :berths  {:marigold.cgxa.bridge/comm
                 {:description "Relay channels (longwave, skybeam, logbook, ...) — a fixture config berth at [:relays]."
                  :schema      {:type       :map
                                :key-spec   {:type :keyword}
                                :value-spec {:type   :map
                                             :schema {:extra-schema {:type :map}}}}
                  :config      {:path   [:relays]
                                :schema {:type       :map
                                         :key-spec   {:type :keyword}
                                         :value-spec {:type           :map
                                                      :schema         {:type {:type        :keyword
                                                                              :message     "not a registered impl of :marigold.cgxa.bridge/comm"
                                                                              :validations [:present?
                                                                                            [:registered-in? :marigold.cgxa.bridge/comm]]}
                                                                       :crew {:type :string}}
                                                      :dynamic-schema [:extra-schema]
                                                      :factory        marigold.cgxa.bridge.comm/create-comm-node!}}}}}}
      """
    And the isaac file "/tmp/modules/marigold.cgxa.bridge/src/marigold/cgxa/bridge.clj" exists with:
      """
      (ns marigold.cgxa.bridge
        (:require
          [isaac.module.protocol :as module]
          [marigold.cgxa.bridge.comm]))

      (defn create-module []
        (module/module))
      """
    And the isaac file "/tmp/modules/marigold.cgxa.bridge/src/marigold/cgxa/bridge/comm.clj" exists with:
      """
      (ns marigold.cgxa.bridge.comm)

      (defmulti create-comm-node! (fn [_path slice] (:type slice)))
      """
    And the isaac file "/tmp/modules/marigold.cgxa.longwave/deps.edn" exists with:
      """
      {:paths ["resources" "src"]}
      """
    And the isaac file "/tmp/modules/marigold.cgxa.longwave/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.cgxa.longwave
       :version "0.1.0"
       :factory marigold.cgxa.longwave/create-module
       :deps    {:marigold.cgxa.bridge {:local/root "/tmp/modules/marigold.cgxa.bridge"}}
       :marigold.cgxa.bridge/comm
       {:longwave {:extra-schema {:helm/freq  {:type :string}
                                  :helm/outer {:type       :map
                                               :value-spec {:type :string}}}}}}
      """
    And the isaac file "/tmp/modules/marigold.cgxa.longwave/src/marigold/cgxa/longwave.clj" exists with:
      """
      (ns marigold.cgxa.longwave
        (:require
          [isaac.module.protocol :as module]
          [marigold.cgxa.bridge.comm :as bridge.comm]))

      (defn create-module []
        (module/module))

      (defmethod bridge.comm/create-comm-node! :longwave [path slice]
        {:type      :longwave
         :path      path
         :crew      (:crew slice)
         :helm/freq (:helm/freq slice)})
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.cgxa.bridge   {:local/root "/tmp/modules/marigold.cgxa.bridge"}
                 :marigold.cgxa.longwave {:local/root "/tmp/modules/marigold.cgxa.longwave"}}
       :relays  {:helm-station {:type :longwave :crew "atticus"}}}
      """

  Scenario: set of an undeclared key under a schema'd map is refused
    When isaac is run with "config set relays.helm-station.bogus-field 1"
    Then the exit code is 1
    And the stderr contains "relays.helm-station"
    And the stderr contains "bogus-field"
    And the stderr contains "crew, helm/freq, helm/outer, type"
    And the stderr contains "use --force"
    And the isaac file "isaac.edn" does not contain "bogus-field"

  Scenario: set of an undeclared key under an entity table (key-spec) still writes
    When isaac is run with "config set relays.wavecrest.type :longwave"
    Then the exit code is 0
    And the isaac file "isaac.edn" EDN contains:
      | path                  | value     |
      | relays.wavecrest.type | :longwave |

  Scenario: --force writes the undeclared key and the load-time warning fires
    When isaac is run with "config set relays.helm-station.bogus-field 1 --force"
    Then the exit code is 0
    And the isaac file "isaac.edn" EDN contains:
      | path                            | value |
      | relays.helm-station.bogus-field | 1     |
    And the log has entries matching:
      | level | event               | key         | slice                 |
      | :warn | :config/unknown-key | bogus-field | relays[:helm-station] |

  Scenario: unset of an undeclared key under a schema'd map is refused the same way
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.cgxa.bridge   {:local/root "/tmp/modules/marigold.cgxa.bridge"}
                 :marigold.cgxa.longwave {:local/root "/tmp/modules/marigold.cgxa.longwave"}}
       :relays  {:helm-station {:type :longwave :crew "atticus" :bogus-field 1}}}
      """
    When isaac is run with "config unset relays.helm-station.bogus-field"
    Then the exit code is 1
    And the stderr contains "relays.helm-station"
    And the stderr contains "bogus-field"
    And the isaac file "isaac.edn" EDN contains:
      | path                            | value |
      | relays.helm-station.bogus-field | 1     |
