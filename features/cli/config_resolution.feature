Feature: The CLI resolves the config once per command
  Measured on zanebot 2026-09-04: `isaac --version` spent 1296 of 1307 ms in
  three full config resolutions (launcher, main, cli-logging); real commands
  did five. Resolution is ~400–650 ms each (root isaac.edn parse+validate+
  normalize ≈330 ms, 82 entity files ≈75 ms). The classpath cache is fine
  (3 ms). Fix 1: one resolution per process, threaded from the launcher.
  Fix 2: the startup cache also carries the resolved config, basis-keyed
  and fail-open, so a warm run skips validation entirely (isaac-v1la).

  Scenario: a fast-path command resolves the config exactly once
    Given an empty Isaac root at "target/test-config-resolution"
    And the config resolution spy is armed
    When isaac is run with "--version"
    Then the exit code is 0
    And the config resolution spy was invoked exactly 1 times

  Scenario: a real command resolves the config exactly once
    Given an empty Isaac root at "target/test-config-resolution"
    And the isaac EDN file "config/isaac.edn" exists with:
      | path            | value |
      | defaults.crew   | main  |
    And the config resolution spy is armed
    When isaac is run with "config get defaults"
    Then the exit code is 0
    And the config resolution spy was invoked exactly 1 times

  Scenario: a warm startup cache serves the resolved config without validating
    Given an empty Isaac root at "target/test-config-resolution"
    And the isaac EDN file "config/isaac.edn" exists with:
      | path            | value |
      | defaults.crew   | main  |
    And a warm startup cache exists from a prior run
    And the config validation spy is armed
    When isaac is run with "config get defaults"
    Then the exit code is 0
    And the stdout contains "main"
    And the config validation spy was invoked exactly 0 times

  Scenario: a watched config change re-resolves and refreshes the cached config
    Given an empty Isaac root at "target/test-config-resolution"
    And a warm startup cache exists from a prior run
    And the config validation spy is armed
    And the isaac file "config/isaac.edn" exists with:
      """
      {:defaults {:crew "marvin"}}
      """
    When isaac is run with "config get defaults"
    Then the exit code is 0
    And the stdout contains "marvin"
    And the config validation spy was invoked at least 1 times
    And the startup cache was refreshed after replan

  Scenario: a corrupted cached config falls back to full resolution
    Given an empty Isaac root at "target/test-config-resolution"
    And a warm startup cache exists from a prior run
    And the classpath cache file is corrupted so apply fails
    When isaac is run with "config get defaults"
    Then the exit code is 0
