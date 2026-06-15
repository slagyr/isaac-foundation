Feature: Scheduler trigger firing
  isaac.scheduler.runtime fires registered tasks according to their trigger.
  Each trigger kind (:interval, :delay, :at, :cron) has its own
  next-fire-at computation, dispatched by :kind via multimethod.

  Background:
    Given an Isaac root at "target/test-state"
    And the scheduler is started with the clock at "2026-05-20T10:00:00Z"

  Scenario: :interval fires every N ms
    Given a scheduled task:
      | id   | trigger.kind | trigger.ms |
      | tick | interval     | 100        |
    When the clock advances "350ms"
    Then handler "tick" has fired 3 times

  Scenario: :delay fires once after N ms
    Given a scheduled task:
      | id    | trigger.kind | trigger.ms |
      | retry | delay        | 500        |
    When the clock advances "499ms"
    Then handler "retry" has not fired
    When the clock advances "1ms"
    Then handler "retry" has fired 1 time
    When the clock advances "1s"
    Then handler "retry" has fired 1 time

  Scenario: :at fires once at the absolute instant
    Given a scheduled task:
      | id    | trigger.kind | trigger.instant      |
      | alarm | at           | 2026-05-20T10:00:30Z |
    When the clock advances "29s"
    Then handler "alarm" has not fired
    When the clock advances "1s"
    Then handler "alarm" has fired 1 time

  Scenario: :at in the past fires once on the next tick
    Given a scheduled task:
      | id   | trigger.kind | trigger.instant      |
      | late | at           | 2026-05-20T09:00:00Z |
    When the scheduler ticks
    Then handler "late" has fired 1 time

  Scenario: :cron fires when its expression matches
    Given a scheduled task:
      | id      | trigger.kind | trigger.expr | trigger.zone    |
      | nightly | cron         | 0 3 * * *    | America/Chicago |
    When the clock advances to "2026-05-21T03:00:30-05:00"
    Then handler "nightly" has fired 1 time