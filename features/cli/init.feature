Feature: isaac init
  Scaffolds a default config for fresh installs. Ollama-first so users
  can run Isaac without arranging API keys first. Refuses to clobber
  an existing config. Pairs with an updated 'no config found' error
  that points users at isaac init.

  Scaffolded files (at <home>/config/):
    - isaac.edn         :defaults, :tz, :prefer-entity-files true
    - crew/main.md      YAML frontmatter + starter soul
    - models/llama.edn  Ollama model reference
    - providers/ollama.edn  local Ollama provider
    - cron/heartbeat.md     YAML frontmatter + heartbeat prompt

  Background:
    Given the user home directory is "/tmp/user"

  Scenario: isaac init output lists created files and setup instructions
    Given an empty Isaac root at "target/test-state"
    When isaac is run with "--root target/test-state init"
    Then the exit code is 0
    And the stdout lines match:
      | text                                             |
      | Isaac initialized at target/test-state.          |
      |                                                  |
      | Created:                                         |
      |   config/isaac.edn                               |
      |   config/crew/main.md                            |
      |   config/models/llama.edn                        |
      |   config/providers/ollama.edn                    |
      |   config/cron/heartbeat.md                       |
      |                                                  |
      | Isaac uses Ollama locally. If you don't have it: |
      |                                                  |
      |   brew install ollama                            |
      |   ollama serve &                                 |
      |   ollama pull llama3.2                           |
      |                                                  |
      | Then try:                                        |
      |                                                  |
      |   isaac prompt -m "hello"                        |

  Scenario: isaac init scaffolds each file with the expected content
    Given an empty Isaac root at "target/test-state"
    When isaac is run with "--root target/test-state init"
    Then the EDN isaac file "config/isaac.edn" contains:
      | path                 | value           |
      | defaults.crew        | main            |
      | defaults.model       | llama           |
      | tz                   | America/Chicago |
      | prefer-entity-files  | true            |
    And the isaac file "config/crew/main.md" contains:
      """
      ---
      model: "llama"
      ---

      You are Isaac, a helpful AI assistant.
      """
    And the EDN isaac file "config/models/llama.edn" contains:
      | path     | value    |
      | model    | llama3.2 |
      | provider | ollama   |
    And the EDN isaac file "config/providers/ollama.edn" contains:
      | path     | value                  |
      | base-url | http://localhost:11434 |
      | api      | ollama                 |
    And the isaac file "config/cron/heartbeat.md" contains:
      """
      ---
      expr: "*/30 * * * *"
      crew: "main"
      ---

      Heartbeat. Anything worth noting?
      """

  Scenario: isaac init refuses when a config already exists
    Given a file "/tmp/user/.isaac/config/isaac.edn" exists with content "{:defaults {:crew :main :model :llama}}"
    When isaac is run with "init"
    Then the stderr contains "config already exists at /tmp/user/.isaac/config/isaac.edn; edit it directly."
    And the exit code is 1
