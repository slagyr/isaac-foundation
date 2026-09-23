Feature: Any config key may live inline, as <key>.edn, or as <key>/ (isaac-49zp)
  isaac.edn grows without bound — :modules alone is ~40 lines of git
  coordinates sitting beside crews, comms, models and cron. Today only kinds
  whose owning module declares :entity-dir can be split out, and :modules
  cannot be one of those: :entity-dir is declared by module-contributed config
  slices, but :modules is base foundation schema and is the key that decides
  which modules load.

  So the split stops being kind-specific. A directory is a key and an EDN file
  is a key, for every key, with no special treatment. Filenames are literal
  names, never paths: config/isaac.agent.edn is the key :isaac.agent, and
  nesting is expressed by a file's contents. `_` means "this map's own values"
  at every level. A key is a file or a directory, never both.

  Background:
    Given an empty Isaac root at "target/test-49zp"

  @wip
  Scenario: a key in its own file is loaded
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:defaults {:crew "main"}}
      """
    And the isaac file "config/modules.edn" exists with:
      """
      {:marigold.longwave {:local/root "/tmp/modules/marigold.longwave"}}
      """
    When isaac is run with "config get modules"
    Then the exit code is 0
    And the stdout contains "marigold.longwave"

  @wip
  Scenario: a key that no module declares splits the same way
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {}}
      """
    And the isaac file "config/defaults.edn" exists with:
      """
      {:crew "atticus"}
      """
    When isaac is run with "config get defaults.crew"
    Then the exit code is 0
    And the stdout contains "atticus"

  @wip
  Scenario: a key inline and in its own file is refused
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {} :defaults {:crew "main"}}
      """
    And the isaac file "config/defaults.edn" exists with:
      """
      {:crew "atticus"}
      """
    When isaac is run with "config validate"
    Then the exit code is 1
    And the stderr contains "defaults"
    And the stderr contains "config/defaults.edn"
    And the stderr contains "isaac.edn"

  @wip
  Scenario: a key as both a file and a directory is refused
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {}}
      """
    And the isaac file "config/crew.edn" exists with:
      """
      {"marvin" {:model "grover"}}
      """
    And the isaac file "config/crew/keaton.edn" exists with:
      """
      {:model "sonnet"}
      """
    When isaac is run with "config validate"
    Then the exit code is 1
    And the stderr contains "crew"
    And the stderr contains "config/crew.edn"
    And the stderr contains "config/crew"

  @wip
  Scenario: `_` inside a directory holds that map's own values
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {}}
      """
    And the isaac file "config/crew/_.edn" exists with:
      """
      {"marvin" {:model "grover"}
       "pinky"  {:model "sonnet"}}
      """
    And the isaac file "config/crew/keaton.edn" exists with:
      """
      {:model "haiku"}
      """
    When isaac is run with "config get crew"
    Then the exit code is 0
    And the stdout contains "marvin"
    And the stdout contains "pinky"
    And the stdout contains "keaton"

  @wip
  Scenario: an entity may be a directory whose files are its fields
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {}}
      """
    And the isaac file "config/crew/marvin/_.edn" exists with:
      """
      {:model "grover"}
      """
    And the isaac file "config/crew/marvin/soul.md" exists with:
      """
      You are Marvin, a paranoid android.
      """
    When isaac is run with "config get crew.marvin"
    Then the exit code is 0
    And the stdout contains "grover"
    And the stdout contains "paranoid android"

  @wip
  Scenario: a markdown file named for the entity declares which key its body fills
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {}}
      """
    And the isaac file "config/crew/marvin.md" exists with:
      """
      ---
      model: grover
      soul: _
      ---
      You are Marvin, a paranoid android.
      """
    When isaac is run with "config get crew.marvin"
    Then the exit code is 0
    And the stdout contains "grover"
    And the stdout contains "paranoid android"

  @wip
  Scenario: config set writes to whichever form already holds the key
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {}}
      """
    And the isaac file "config/defaults.edn" exists with:
      """
      {:crew "atticus"}
      """
    When isaac is run with "config set defaults.crew marvin"
    Then the exit code is 0
    And the isaac file "config/defaults.edn" EDN contains:
      | path | value  |
      | crew | marvin |
    And the isaac file "config/isaac.edn" does not contain "defaults"
