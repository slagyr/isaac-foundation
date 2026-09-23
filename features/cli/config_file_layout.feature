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

  These scenarios use the chartroom fixture berths (:signals, :berths) rather
  than real kinds like :crew, whose schema lives in isaac-agent and is not
  reachable from foundation.

  Background:
    Given the chartroom fixture modules are available

  @wip
  Scenario: a module-declared key may live in its own file
    Given config file "isaac.edn" containing:
      """
      {:tz "UTC"}
      """
    And config file "signals.edn" containing:
      """
      {:parlour {:kind "parlor" :loft "upper" :color "blue"}}
      """
    When the config is loaded
    Then the config has no validation errors
    And the loaded config has:
      | key                   | value  |
      | signals.parlour.loft  | upper  |
      | signals.parlour.color | blue   |

  @wip
  Scenario: a base foundation key splits the same way
    Given config file "isaac.edn" containing:
      """
      {:tz "UTC"}
      """
    And config file "defaults.edn" containing:
      """
      {:crew "atticus"}
      """
    When the config is loaded
    Then the config has no validation errors
    And the loaded config has:
      | key           | value   |
      | defaults.crew | atticus |

  @wip
  Scenario: a key inline and in its own file is refused
    Given config file "isaac.edn" containing:
      """
      {:tz "UTC" :defaults {:crew "main"}}
      """
    And config file "defaults.edn" containing:
      """
      {:crew "atticus"}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key      | value                   |
      | defaults | #"(?s).*defaults\.edn.*" |

  @wip
  Scenario: a key as both a file and a directory is refused
    Given config file "isaac.edn" containing:
      """
      {:tz "UTC"}
      """
    And config file "signals.edn" containing:
      """
      {:parlour {:kind "parlor" :loft "upper"}}
      """
    And config file "signals/attic.edn" containing:
      """
      {:kind "parlor" :loft "attic"}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key     | value                  |
      | signals | #"(?s).*signals\.edn.*" |

  @wip
  Scenario: `_` inside a directory holds that map's own values
    Given config file "isaac.edn" containing:
      """
      {:tz "UTC"}
      """
    And config file "signals/_.edn" containing:
      """
      {:parlour {:kind "parlor" :loft "upper"}
       :cellar  {:kind "parlor" :loft "lower"}}
      """
    And config file "signals/attic.edn" containing:
      """
      {:kind "parlor" :loft "attic"}
      """
    When the config is loaded
    Then the config has no validation errors
    And the loaded config has:
      | key                  | value |
      | signals.parlour.loft | upper |
      | signals.cellar.loft  | lower |
      | signals.attic.loft   | attic |

  @wip
  Scenario: an entity may be a directory whose files are its fields
    Given config file "isaac.edn" containing:
      """
      {:tz "UTC"}
      """
    And config file "signals/parlour/_.edn" containing:
      """
      {:kind "parlor" :loft "upper"}
      """
    And config file "signals/parlour/color.edn" containing:
      """
      "blue"
      """
    When the config is loaded
    Then the config has no validation errors
    And the loaded config has:
      | key                   | value |
      | signals.parlour.loft  | upper |
      | signals.parlour.color | blue  |

  @wip
  Scenario: a markdown file named for the entity declares which key its body fills
    Given config file "isaac.edn" containing:
      """
      {:tz "UTC"}
      """
    And config file "berths/captain.md" containing:
      """
      ---
      gauge: helm-mark-iii
      ledger: _
      ---
      You are the Captain.
      """
    When the config is loaded
    Then the config has no validation errors
    And the loaded config has:
      | key                    | value                |
      | berths.captain.gauge   | helm-mark-iii        |
      | berths.captain.ledger  | You are the Captain. |

  @wip
  Scenario: editing a key's own file is picked up on reload
    Given config file "isaac.edn" containing:
      """
      {:tz "UTC"}
      """
    And config file "signals.edn" containing:
      """
      {:parlour {:kind "parlor" :loft "upper"}}
      """
    When the config is loaded
    And config file "signals.edn" containing:
      """
      {:parlour {:kind "parlor" :loft "attic"}}
      """
    And the config is reloaded
    Then the loaded config has:
      | key                  | value |
      | signals.parlour.loft | attic |
