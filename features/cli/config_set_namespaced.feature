Feature: config set keeps namespaced-keyword path segments whole (isaac-cgxa)
  A comm slot's extra-schema keys are all namespaced (gchat/allow-from,
  discord/allow-from, spaces/AAQA….respond). The CLI path grammar used to
  treat the `/` inside a segment as a separator — set wrote two nested
  maps and validate passed, so the stray map sat silently while the real
  keys were untouched. The path parser now treats a `.`-separated segment
  containing `/` as one namespaced keyword; the bracket forms stay the
  escape hatch for a literal slash in a key name.

  The scenarios mirror marigold.bridge / marigold.longwave (chartroom
  vocabulary) with fixture ids of their own — :marigold.cgxa.bridge /
  :marigold.cgxa.longwave — so they never collide with this repo's own
  builtin `modules/marigold.bridge` / `modules/marigold.longwave` fixtures
  (same module id + `:local/root` path shape confuses module discovery's
  manifest cache into serving the repo's real fixture instead of the one
  this feature declares — isaac-cgxa). Bridge declares the :relays config
  table with a dynamic extra-schema berth — the same shape a comm module
  gives :comms — and longwave contributes the namespaced keys :helm/freq
  and :helm/outer to it.

  Background:
    Given an empty Isaac root at "/tmp/cgxa"
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

  Scenario: set relays.x.ns/key writes the namespaced key
    When isaac is run with "config set relays.helm-station.helm/freq 121.5kHz --force"
    Then the exit code is 0
    And the isaac file "isaac.edn" EDN contains:
      | path                          | value    |
      | relays.helm-station.type      | :longwave |
      | relays.helm-station.crew      | atticus  |
      | relays.helm-station.helm/freq | 121.5kHz |

  Scenario: set relays.x.ns/map.ns/key.field writes nested namespaced keys
    When isaac is run with "config set relays.helm-station.helm/outer.inner/key x --force"
    Then the exit code is 0
    And the isaac file "isaac.edn" EDN contains:
      | path                                     | value |
      | relays.helm-station.helm/outer.inner/key | x     |

  Scenario: get reads a namespaced key back
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.cgxa.bridge   {:local/root "/tmp/modules/marigold.cgxa.bridge"}
                 :marigold.cgxa.longwave {:local/root "/tmp/modules/marigold.cgxa.longwave"}}
       :relays  {:helm-station {:type :longwave :crew "atticus" :helm/freq "121.5kHz"}}}
      """
    When isaac is run with "config get relays.helm-station.helm/freq"
    Then the exit code is 0
    And the stdout contains "121.5kHz"

  Scenario: unset removes a namespaced key
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.cgxa.bridge   {:local/root "/tmp/modules/marigold.cgxa.bridge"}
                 :marigold.cgxa.longwave {:local/root "/tmp/modules/marigold.cgxa.longwave"}}
       :relays  {:helm-station {:type :longwave :crew "atticus" :helm/freq "121.5kHz"}}}
      """
    When isaac is run with "config unset relays.helm-station.helm/freq"
    Then the exit code is 0
    And the isaac file "isaac.edn" EDN contains:
      | path                     | value    |
      | relays.helm-station.type | :longwave |
