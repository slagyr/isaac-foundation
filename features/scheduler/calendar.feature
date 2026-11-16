Feature: Scheduler calendar trigger (iCalendar RRULE)
  A :calendar trigger fires on an RFC 5545 RRULE, evaluated in its :zone.
  Accepted parts: FREQ (MINUTELY..YEARLY), INTERVAL, BYDAY (with nth, 1TU /
  -1FR), BYMONTHDAY, BYMONTH, BYHOUR, BYMINUTE, COUNT, UNTIL; :dtstart is a
  sibling field. Seconds are always 0 and a rule without BYMINUTE fires at
  minute 0. INTERVAL > 1 and COUNT need :dtstart, which pins the phase and
  the count; COUNT counts occurrences from :dtstart, missed ones included.
  A nonexistent local time (spring forward) is skipped; an ambiguous one
  (fall back) fires once. A finite rule leaves the schedule after its last
  fire, like :at. Parsing is pure Clojure (bb and JVM). (isaac-e0qe)

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: a weekly rule fires on each listed day at its hour
    Given the scheduler is started with the clock at "2026-11-16T08:00:00-06:00"
    And a scheduled task:
      | id      | trigger.kind | trigger.rule                                | trigger.zone    |
      | standup | calendar     | FREQ=WEEKLY;BYDAY=TU,TH;BYHOUR=9;BYMINUTE=0 | America/Chicago |
    When the clock advances to "2026-11-17T09:00:30-06:00"
    Then handler "standup" has fired 1 time
    When the clock advances to "2026-11-18T09:00:30-06:00"
    Then handler "standup" has fired 1 time
    When the clock advances to "2026-11-19T09:00:30-06:00"
    Then handler "standup" has fired 2 times

  Scenario: a rule without BYMINUTE fires at minute 0
    Given the scheduler is started with the clock at "2026-11-16T08:00:00-06:00"
    And a scheduled task:
      | id      | trigger.kind | trigger.rule         | trigger.zone    |
      | morning | calendar     | FREQ=DAILY;BYHOUR=9  | America/Chicago |
    When the clock advances to "2026-11-16T08:59:59-06:00"
    Then handler "morning" has not fired
    When the clock advances to "2026-11-16T09:00:30-06:00"
    Then handler "morning" has fired 1 time
    When the clock advances to "2026-11-16T09:59:30-06:00"
    Then handler "morning" has fired 1 time

  Scenario: every other Friday follows the phase :dtstart sets
    Given the scheduler is started with the clock at "2026-11-16T08:00:00-06:00"
    And a scheduled task:
      | id     | trigger.kind | trigger.rule                                          | trigger.dtstart     | trigger.zone    |
      | payday | calendar     | FREQ=WEEKLY;INTERVAL=2;BYDAY=FR;BYHOUR=9;BYMINUTE=0   | 2026-11-20T09:00:00 | America/Chicago |
    Then the scheduled tasks include:
      | id     | next-fire-at         |
      | payday | 2026-11-20T15:00:00Z |
    When the clock advances to "2026-11-20T09:00:30-06:00"
    Then handler "payday" has fired 1 time
    And the scheduled tasks include:
      | id     | next-fire-at         |
      | payday | 2026-12-04T15:00:00Z |
    When the clock advances to "2026-11-27T09:00:30-06:00"
    Then handler "payday" has fired 1 time

  Scenario: the first Tuesday of the month
    Given the scheduler is started with the clock at "2026-11-16T08:00:00-06:00"
    And a scheduled task:
      | id     | trigger.kind | trigger.rule                             | trigger.zone    |
      | review | calendar     | FREQ=MONTHLY;BYDAY=1TU;BYHOUR=9;BYMINUTE=0 | America/Chicago |
    Then the scheduled tasks include:
      | id     | next-fire-at         |
      | review | 2026-12-01T15:00:00Z |

  Scenario: COUNT counts from :dtstart, missed occurrences included, then leaves the schedule
    The rule's first two occurrences (Nov 14 and 15) passed before the
    scheduler started; only the third is left.
    Given the scheduler is started with the clock at "2026-11-16T08:00:00-06:00"
    And a scheduled task:
      | id    | trigger.kind | trigger.rule                             | trigger.dtstart     | trigger.zone    |
      | three | calendar     | FREQ=DAILY;COUNT=3;BYHOUR=9;BYMINUTE=0   | 2026-11-14T09:00:00 | America/Chicago |
    When the clock advances to "2026-11-16T09:00:30-06:00"
    Then handler "three" has fired 1 time
    And the scheduled tasks do not include "three"
    When the clock advances to "2026-11-17T09:00:30-06:00"
    Then handler "three" has fired 1 time

  Scenario: UNTIL is a local time in the zone; the rule leaves the schedule after it
    Given the scheduler is started with the clock at "2026-11-16T08:00:00-06:00"
    And a scheduled task:
      | id    | trigger.kind | trigger.rule                                           | trigger.zone    |
      | brief | calendar     | FREQ=DAILY;BYHOUR=9;BYMINUTE=0;UNTIL=20261117T090000   | America/Chicago |
    When the clock advances to "2026-11-16T09:00:30-06:00"
    And the clock advances to "2026-11-17T09:00:30-06:00"
    Then handler "brief" has fired 2 times
    And the scheduled tasks do not include "brief"

  Scenario: a local time skipped by spring forward does not fire that day
    Given the scheduler is started with the clock at "2027-03-13T12:00:00-06:00"
    And a scheduled task:
      | id    | trigger.kind | trigger.rule                     | trigger.zone    |
      | night | calendar     | FREQ=DAILY;BYHOUR=2;BYMINUTE=30  | America/Chicago |
    When the clock advances to "2027-03-14T04:00:00-05:00"
    Then handler "night" has not fired
    When the clock advances to "2027-03-15T02:30:30-05:00"
    Then handler "night" has fired 1 time

  Scenario: a local time repeated by fall back fires once
    Given the scheduler is started with the clock at "2026-10-31T12:00:00-05:00"
    And a scheduled task:
      | id    | trigger.kind | trigger.rule                     | trigger.zone    |
      | night | calendar     | FREQ=DAILY;BYHOUR=1;BYMINUTE=30  | America/Chicago |
    When the clock advances to "2026-11-01T01:30:30-05:00"
    Then handler "night" has fired 1 time
    When the clock advances to "2026-11-01T01:30:30-06:00"
    Then handler "night" has fired 1 time

  Scenario: INTERVAL greater than 1 without :dtstart is refused
    Given the scheduler is started with the clock at "2026-11-16T08:00:00-06:00"
    When I attempt to schedule a task:
      | id     | trigger.kind | trigger.rule                                        | trigger.zone    |
      | payday | calendar     | FREQ=WEEKLY;INTERVAL=2;BYDAY=FR;BYHOUR=9;BYMINUTE=0 | America/Chicago |
    Then an error is raised with message matching "INTERVAL > 1 requires :dtstart"

  Scenario: COUNT without :dtstart is refused
    Given the scheduler is started with the clock at "2026-11-16T08:00:00-06:00"
    When I attempt to schedule a task:
      | id    | trigger.kind | trigger.rule                            | trigger.zone    |
      | three | calendar     | FREQ=DAILY;COUNT=3;BYHOUR=9;BYMINUTE=0  | America/Chicago |
    Then an error is raised with message matching "COUNT requires :dtstart"

  Scenario: a rule part outside the accepted subset is refused by name
    Given the scheduler is started with the clock at "2026-11-16T08:00:00-06:00"
    When I attempt to schedule a task:
      | id   | trigger.kind | trigger.rule                                   | trigger.zone    |
      | last | calendar     | FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1  | America/Chicago |
    Then an error is raised with message matching "unsupported RRULE part: BYSETPOS"
