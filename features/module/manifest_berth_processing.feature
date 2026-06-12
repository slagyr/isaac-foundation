Feature: Manifest-only berth processing
  Berths without a `:config` shape collect contributions purely from
  module manifests. For each contribution, the foundation walks the
  berth's `:manifest :schema` looking for a `:factory` field at the
  entry level. If present, foundation calls `(factory entry)` per
  contribution. The factory does whatever the berth needs — most
  commonly, register the contribution in the nexus at a well-known
  path so the platform can find it later.

  Routes are the canonical case: each module contributes route
  handlers via its manifest; the foundation registers them in the
  nexus, where the HTTP router reads from.

  These scenarios share the marigold.bridge / marigold.longwave
  fixture modules under `spec/marigold/...`. The bridge fixture
  declares `:marigold.bridge/signal-route` as a manifest-only berth
  whose entry-level schema specifies a registration factory; the
  longwave fixture contributes a route entry.

  Scenario: A route contribution is registered in the nexus via its per-entry factory
    Given an empty Isaac state directory "/tmp/marigold"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "spec/marigold/bridge"}
                 :marigold.longwave {:local/root "spec/marigold/longwave"}}}
      """
    When the config is loaded
    Then the nexus has a route [:get "/longwave/ping"] with handler marigold.longwave/ping-handler
    And the config has no validation errors
