@wip
Feature: isaac server process start
  `isaac server` starts the Isaac process runner. `:server/started`
  means the process is up. It does not carry HTTP bind host or port.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: Process start logs :server/started without host or port
    When the Isaac runner is started
    Then the log has entries matching:
      | level | event           | host | port |
      | :info | :server/started |      |      |
