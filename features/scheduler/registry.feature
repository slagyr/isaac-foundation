Feature: Scheduler task registry
  Tasks have stable identities. The scheduler exposes registration,
  cancellation, and listing. Re-registering an existing id is an
  error — replacement is a deliberate ceremony (cancel, then schedule).

  Background:
    Given an Isaac root at "target/test-state"
    And the scheduler is started with the clock at "2026-05-20T10:00:00Z"

  Scenario: list returns all registered tasks
    Given a scheduled task:
      | id     | trigger.kind | trigger.ms |
      | tick-a | interval     | 100        |
    And a scheduled task:
      | id     | trigger.kind | trigger.ms |
      | tick-b | interval     | 200        |
    When I ask for the scheduled tasks
    Then the scheduled tasks include:
      | id     | trigger.kind | trigger.ms |
      | tick-a | interval     | 100        |
      | tick-b | interval     | 200        |

  Scenario: cancel removes a task and stops further fires
    Given a scheduled task:
      | id   | trigger.kind | trigger.ms |
      | tick | interval     | 100        |
    When I cancel "tick"
    And the clock advances "300ms"
    Then handler "tick" has not fired
    And the scheduled tasks do not include "tick"

  Scenario: cancel on an unknown id is a silent no-op
    When I cancel "nonexistent"
    Then no error is logged
    And the scheduled tasks are empty

  Scenario: re-registering an existing id is an error
    Given a scheduled task:
      | id   | trigger.kind | trigger.ms |
      | tick | interval     | 100        |
    When I attempt to schedule a task:
      | id   | trigger.kind | trigger.ms |
      | tick | interval     | 500        |
    Then an error is raised with message matching "already scheduled"
    And the scheduled tasks include:
      | id   | trigger.kind | trigger.ms |
      | tick | interval     | 100        |