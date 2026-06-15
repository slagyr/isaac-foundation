Feature: Scheduler per-task policies
  Each task can specify :coalesce, :on-error, and :timeout-ms. These
  govern how the scheduler reacts when fires overlap, handlers throw,
  or handlers hang.

  Background:
    Given an Isaac root at "target/test-state"
    And the scheduler is started with the clock at "2026-05-20T10:00:00Z"

  Scenario: coalesce :skip drops overlapping fires
    Given a scheduled task:
      | id   | trigger.kind | trigger.ms | handler-runtime | coalesce |
      | slow | interval     | 100        | 1ms             | skip     |
    When the clock advances "300ms" and pending handlers complete
    Then handler "slow" started 1 time

  Scenario: coalesce :queue runs overlapping fires sequentially
    Given a scheduled task:
      | id   | trigger.kind | trigger.ms | handler-runtime | coalesce |
      | slow | interval     | 100        | 1ms             | queue    |
    When the clock advances "300ms" and pending handlers complete
    Then handler "slow" started 3 times

  Scenario: on-error :log (default) logs and keeps scheduling
    Given a scheduled task:
      | id    | trigger.kind | trigger.ms | handler-throws |
      | flaky | interval     | 100        | true           |
    When the clock advances "300ms"
    Then handler "flaky" has fired 3 times
    And the log has entries matching:
      | level | event                      | id    |
      | error | :scheduler/handler-error   | flaky |
      | error | :scheduler/handler-error   | flaky |
      | error | :scheduler/handler-error   | flaky |

  Scenario: on-error :retry delays the next fire after a throw using exponential backoff
    Given a scheduled task:
      | id    | trigger.kind | trigger.ms | handler-throws | on-error | backoff-ms | max-backoff-ms | retry-attempts |
      | retry | interval     | 100        | true           | retry    | 500        | 60000          | 10             |
    When the clock advances "100ms"
    Then handler "retry" has fired 1 time
    # First retry uses 500ms backoff -> fires at 600ms.
    When the clock advances "499ms"
    Then handler "retry" has fired 1 time
    When the clock advances "1ms"
    Then handler "retry" has fired 2 times
    # Second retry uses 1000ms backoff (exponential) -> fires at 1600ms.
    When the clock advances "999ms"
    Then handler "retry" has fired 2 times
    When the clock advances "1ms"
    Then handler "retry" has fired 3 times

  Scenario: on-error :retry stops the task after :retry-attempts consecutive throws
    Given a scheduled task:
      | id    | trigger.kind | trigger.ms | handler-throws | on-error | backoff-ms | max-backoff-ms | retry-attempts |
      | flaky | interval     | 100        | true           | retry    | 1          | 1              | 3              |
    When the clock advances "100ms"
    Then handler "flaky" has fired 1 time
    When the clock advances "1ms"
    Then handler "flaky" has fired 2 times
    When the clock advances "1ms"
    Then handler "flaky" has fired 3 times
    Then the log has entries matching:
      | level | event                | id    | reason            | attempts |
      | warn  | :scheduler/disabled  | flaky | :too-many-errors  | 3        |
    And the scheduled tasks do not include "flaky"

  Scenario: on-error :retry uses default backoff and attempts when omitted
    Given a scheduled task:
      | id    | trigger.kind | trigger.ms | handler-throws | on-error |
      | retry | interval     | 100        | true           | retry    |
    When the clock advances "100ms"
    Then handler "retry" has fired 1 time
    # Default backoff-ms is 1000ms -> next fire at 1100ms.
    When the clock advances "999ms"
    Then handler "retry" has fired 1 time
    When the clock advances "1ms"
    Then handler "retry" has fired 2 times

  Scenario: timeout-ms interrupts hung handlers
    Given a scheduled task:
      | id   | trigger.kind | trigger.ms | handler-runtime | timeout-ms |
      | hang | interval     | 100        | 5s              | 20ms       |
    When the clock advances "300ms" and pending handlers complete
    Then the log has entries matching:
      | level | event              | id   |
      | warn  | :scheduler/timeout | hang |
    And handler "hang" started 1 time