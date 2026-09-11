Feature: Component lifecycle
  Modules contribute runtime components via :isaac/component. Contributions
  are inert factory data gathered at config load (CLI-safe). Only runner boot
  instantiates and starts them in module topological order; shutdown stops
  them in reverse.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: a module's component starts on runner boot
    Given the widget test component module is registered
    And config:
      | key                 | value |
      | server.auth.token   | test  |
    When the Isaac runner is started
    Then the log has entries matching:
      | level | event             | component |
      | :info | :component/started  | widget  |
    When the Isaac runner is stopped
    Then the log has entries matching:
      | level | event             | component |
      | :info | :component/stopped  | widget  |

  Scenario: a component does NOT start on CLI
    Given the widget test component module is registered
    And config:
      | key                 | value |
      | server.auth.token   | test  |
    When the config is loaded
    Then the log has no entries matching:
      | event            |
      | :component/started |

  Scenario: components start in topological order
    Given the alpha and bravo test component modules are registered
    And config:
      | key                 | value |
      | server.auth.token   | test  |
    When the Isaac runner is started
    Then the component start order is "bravo, alpha"
    When the Isaac runner is stopped
    Then the component stop order is "alpha, bravo"
