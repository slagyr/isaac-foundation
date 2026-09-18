Feature: Remote-by-default CLI routing (isaac-gar0)
  By default isaac runs every command as a separate local process. When the
  home pointer file (~/.config/isaac.edn, the same file that names :root)
  carries a :cli :remote setting AND the remote CLI module is installed, the
  launcher ships the command to that server instead of booting isaac locally
  — the same-machine server is just the case where the remote is localhost.
  Nothing here reads the token: token resolution is isaac-tvcg's resolver.

  The decision is made from the pointer file alone, before classpath compose
  and before any config resolution. The remote leg is a seam the remote CLI
  module fills; these scenarios install a stub in that seam.

  Background:
    Given the user home directory is "/tmp/user"
    And an empty Isaac root at "target/test-remote-routing"
    And environment variable "ZANE_TOK" is "stub-secret"

  @wip
  Scenario: with no remote setting the command runs locally
    Given a stub remote runner is installed
    When isaac is run with "--version"
    Then the exit code is 0
    And the stub remote runner was not invoked

  @wip
  Scenario: a remote setting ships the command to the server without resolving the config
    Given the file "/tmp/user/.config/isaac.edn" exists with:
      """
      {:root "target/test-remote-routing"
       :cli  {:remote {:url "wss://zanebot.example/cli" :token "${ZANE_TOK}"}}}
      """
    And a stub remote runner is installed
    And the config resolution spy is armed
    When isaac is run with "sessions list"
    Then the stub remote runner received url "wss://zanebot.example/cli" and argv ["sessions","list"]
    And the config resolution spy was invoked exactly 0 times
    And the exit code is 0

  @wip
  Scenario: the remote runner's exit code becomes the local exit code
    Given the file "/tmp/user/.config/isaac.edn" exists with:
      """
      {:cli {:remote {:url "wss://zanebot.example/cli" :token "${ZANE_TOK}"}}}
      """
    And a stub remote runner is installed that exits with code 4
    When isaac is run with "sessions list"
    Then the exit code is 4

  @wip
  Scenario: --local bypasses the remote for one invocation
    Given the file "/tmp/user/.config/isaac.edn" exists with:
      """
      {:cli {:remote {:url "wss://zanebot.example/cli" :token "${ZANE_TOK}"}}}
      """
    And a stub remote runner is installed
    When isaac is run with "--local --version"
    Then the exit code is 0
    And the stdout contains "isaac"
    And the stub remote runner was not invoked

  @wip
  Scenario: ISAAC_CLI_LOCAL=1 bypasses the remote for scripts
    Given the file "/tmp/user/.config/isaac.edn" exists with:
      """
      {:cli {:remote {:url "wss://zanebot.example/cli" :token "${ZANE_TOK}"}}}
      """
    And environment variable "ISAAC_CLI_LOCAL" is "1"
    And a stub remote runner is installed
    When isaac is run with "--version"
    Then the exit code is 0
    And the stub remote runner was not invoked

  @wip
  Scenario: a local-only command always runs locally
    server, service, modules and remote are :local-only — a down server must
    still be startable with the setting on.
    Given the file "/tmp/user/.config/isaac.edn" exists with:
      """
      {:cli {:remote {:url "wss://zanebot.example/cli" :token "${ZANE_TOK}"}}}
      """
    And a stub remote runner is installed
    When isaac is run with "modules list"
    Then the exit code is 0
    And the stub remote runner was not invoked

  @wip
  Scenario: an unreachable remote fails the command with the reason and never falls back to local
    Given the file "/tmp/user/.config/isaac.edn" exists with:
      """
      {:cli {:remote {:url "wss://zanebot.example/cli" :token "${ZANE_TOK}"}}}
      """
    And a stub remote runner is installed that fails with reason "connection refused"
    And the config resolution spy is armed
    When isaac is run with "sessions list"
    Then the exit code is 69
    And the stderr contains "wss://zanebot.example/cli"
    And the stderr contains "connection refused"
    And the stderr contains "--local"
    And the config resolution spy was invoked exactly 0 times

  @wip
  Scenario: a remote setting without the remote CLI module installed is an error naming the module
    Given the file "/tmp/user/.config/isaac.edn" exists with:
      """
      {:cli {:remote {:url "wss://zanebot.example/cli" :token "${ZANE_TOK}"}}}
      """
    When isaac is run with "sessions list"
    Then the exit code is 69
    And the stderr contains "isaac.cli-proxy"
    And the stderr contains "--local"
