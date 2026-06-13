Feature: Config berth processing
  When a berth declares a `:config` shape, the foundation walks the
  user's config at the berth's `:path`, validates each slot against
  the composed schema (base fields plus gathered `:dynamic-schema`),
  calls the schema-level `:factory` per slot, and installs the
  returned Node in the nexus at the same path. Per-type dispatch
  inside the factory happens via a multimethod registered by the
  contributing module's namespace (loaded as a side-effect of
  `requiring-resolve`-ing the module's top-level `:factory` symbol).

  These scenarios use the marigold.bridge and marigold.longwave
  fixture modules under `spec/marigold/...`. Each fixture is a real
  module on disk (deps.edn + resources/isaac-manifest.edn + src/)
  and is the SAME across every scenario in this file. Tests vary
  only the user `isaac.edn` to exercise different cases.

  Scenario: A user slot's :type that isn't a registered contribution is a config-load error
    Given an empty Isaac state directory "/tmp/marigold"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "modules/marigold.bridge"}
                 :marigold.longwave {:local/root "modules/marigold.longwave"}}
       :comms   {:helm-relay {:type :skybeam :crew "captain"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                       | value                                          |
      | comms[:helm-relay].type   | not a registered impl of :marigold.bridge/comm |

  Scenario: A user slot missing a field required by the gathered :extra-schema is a config-load error
    Given an empty Isaac state directory "/tmp/marigold"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "modules/marigold.bridge"}
                 :marigold.longwave {:local/root "modules/marigold.longwave"}}
       :comms   {:helm-relay {:type :longwave :crew "captain"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                              | value           |
      | comms[:helm-relay][:helm/freq]   | must be present |
