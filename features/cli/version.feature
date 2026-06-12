Feature: CLI version
  Isaac reports its version via the --version flag, -V short flag,
  or the `version` subcommand. The version string is read from
  src/isaac-manifest.edn. When the working directory is a git
  repository, the short (8-char) commit SHA is appended in parens;
  otherwise the SHA is omitted.

  Scenario: --version prints the manifest version
    When isaac is run with "--version"
    Then the stdout matches:
      | pattern              |
      | ^isaac \d+\.\d+\.\d+ |
    And the exit code is 0

  Scenario: -V short flag matches --version
    When isaac is run with "-V"
    Then the stdout matches:
      | pattern              |
      | ^isaac \d+\.\d+\.\d+ |
    And the exit code is 0

  Scenario: version subcommand matches the flag
    When isaac is run with "version"
    Then the stdout matches:
      | pattern              |
      | ^isaac \d+\.\d+\.\d+ |
    And the exit code is 0

  Scenario: --version works even when no config is present
    Given an empty Isaac root at "/test/no-config"
    When isaac is run with "--version"
    Then the stdout matches:
      | pattern              |
      | ^isaac \d+\.\d+\.\d+ |
    And the exit code is 0
    And the stderr does not contain "no config found"
