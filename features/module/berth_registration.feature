Feature: Berth registration observability
  Every manifest-only berth entry installed at boot emits a uniform
  :berth/registration log event (berth id, entry id, contributing module).
  A :berth/registration-summary closes the registration phase with
  per-berth counts so missing routes/commands are diagnosable from the
  boot log.

  Background:
    Given an empty Isaac state directory "/tmp/berth-registration"

  Scenario: Each berth entry is logged with berth, entry id, and module
    Given a module "demo" contributing a :isaac.server/route entry :ping
    When the server boots
    Then the log has entries matching:
      | level | event             | berth               | entry | module |
      | :info | :berth/registration | :isaac.server/route | ping  | demo   |

  Scenario: Registrations across berth kinds all appear at boot
    Given the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.bridge   {:local/root "modules/marigold.bridge"}
                 :marigold.longwave {:local/root "modules/marigold.longwave"}}}
      """
    When the config is loaded
    Then the log has entries matching:
      | level | event             | berth                         | entry         | module            |
      | :info | :berth/registration | :isaac/cli                    | longwave-ping | marigold.longwave |
      | :info | :berth/registration | :marigold.bridge/signal-route | longwave-ping | marigold.longwave |

  Scenario: Boot emits a per-berth registration summary
    Given a module "demo" contributing a :isaac.server/route entry :ping
    When the server boots
    Then the log contains a berth registration summary