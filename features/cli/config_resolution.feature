Feature: The CLI resolves the config once per command
  Measured on zanebot 2026-09-04: `isaac --version` spent 1296 of 1307 ms in
  three full config resolutions (launcher, main, cli-logging); real commands
  did five. Fix 1 (keep): one resolution per process, threaded from the
  launcher plus a process memo. The classpath cache stays. The resolved
  config is not written to cache/cli.edn — every new process reads the
  files (isaac-v1la disk blob reversed).

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

  @wip
  Scenario: a second process still validates even when the classpath cache is warm
    Given an empty Isaac root at "target/test-config-resolution"
    And the isaac EDN file "config/isaac.edn" exists with:
      | path          | value |
      | defaults.crew | main  |
    And a warm classpath cache exists from a prior non-fast-path run
    And the config validation spy is armed
    When isaac is run with "config get defaults"
    Then the exit code is 0
    And the stdout contains "main"
    And the config validation spy was invoked at least 1 times

  @wip
  Scenario: a new entity file is visible without deleting the classpath cache
    Given an empty Isaac root at "target/test-config-resolution"
    And the isaac EDN file "config/isaac.edn" exists with:
      | path          | value |
      | defaults.crew | main  |
    And a warm classpath cache exists from a prior non-fast-path run
    And the isaac file "config/crew/cordelia.edn" exists with:
      """
      {:soul "You keep the longwave log."}
      """
    When isaac is run with "config get crew"
    Then the exit code is 0
    And the stdout contains "cordelia"
    And the stdout contains "longwave log"

  @wip
  Scenario: cache/cli.edn has no config blob and no secret
    Given an empty Isaac root at "target/test-config-resolution"
    And the isaac .env file contains:
      """
      DISCORD_TOKEN=s3cr3t-value
      """
    And the isaac file "config/isaac.edn" exists with:
      """
      {:defaults {:crew "main"}
       :comms {:discord {:token "${DISCORD_TOKEN}"}}}
      """
    When isaac is run with "config get comms"
    Then the isaac file "cache/cli.edn" does not contain "s3cr3t-value"
    And the isaac file "cache/cli.edn" EDN contains:
      | path        | value |
      | data.config |       |
