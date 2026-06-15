Feature: Config-berth node lifecycle (generic — no comm machinery)
  The berth engine reconciles every config-berth-claimed path against
  the nexus: the declared :factory builds a node when a slot appears,
  nodes that satisfy Reconfigurable receive on-config-change! when
  their slice changes (plain nodes are recreated), and removed slots
  deregister. Demonstrated on the marigold bridge berth — a fixture
  with its own :dynamic-schema config berth and a multimethod factory
  — so the contract is the machinery's, not comms'.

  Background:
    Given an empty Isaac state directory "/tmp/marigold"

  Scenario: A Reconfigurable node receives on-config-change! when its slice changes
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "modules/marigold.bridge"}
                 :marigold.longwave {:local/root "modules/marigold.longwave"}}
       :relays  {:relay1 {:type :relay-station :helm/freq "121.5"}}}
      """
    When the config is loaded
    Then the nexus node at [:relays :relay1] has state:
      | path             | value    |
      | last-event       | :started |
      | slice.helm/freq  | 121.5    |
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "modules/marigold.bridge"}
                 :marigold.longwave {:local/root "modules/marigold.longwave"}}
       :relays  {:relay1 {:type :relay-station :helm/freq "122.0"}}}
      """
    When the config is reloaded
    Then the nexus node at [:relays :relay1] has state:
      | path             | value    |
      | last-event       | :changed |
      | slice.helm/freq  | 122.0    |

  Scenario: A plain node is recreated when its slice changes
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "modules/marigold.bridge"}
                 :marigold.longwave {:local/root "modules/marigold.longwave"}}
       :relays  {:helm-relay {:type :longwave :crew "captain" :helm/freq "121.5"}}}
      """
    When the config is loaded
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "modules/marigold.bridge"}
                 :marigold.longwave {:local/root "modules/marigold.longwave"}}
       :relays  {:helm-relay {:type :longwave :crew "captain" :helm/freq "122.0"}}}
      """
    When the config is reloaded
    Then the nexus node at [:relays :helm-relay] has state:
      | path      | value |
      | helm/freq | 122.0 |

  Scenario: A removed slot's node is deregistered
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "modules/marigold.bridge"}
                 :marigold.longwave {:local/root "modules/marigold.longwave"}}
       :relays  {:helm-relay {:type :longwave :crew "captain" :helm/freq "121.5"}}}
      """
    When the config is loaded
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "modules/marigold.bridge"}
                 :marigold.longwave {:local/root "modules/marigold.longwave"}}}
      """
    When the config is reloaded
    Then the nexus has no node at [:relays :helm-relay]
