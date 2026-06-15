Feature: Module schema composition
  At config-load time, a berth's config schema composes every declared
  module's :extra-schema fragment into the effective root via
  :dynamic-schema. Slots conform like any other config — values coerce
  to their declared types, then :validations run; the annotation layer
  reports errors with berth-normalized keys (signals[:bert].field).

  Manifest field schemas use c3kit.apron.schema's vocabulary directly,
  restricted to refs (no inline function literals). Conditional presence
  is expressed with the entity-scoped factory ref :present-when?, e.g.
  `[:present-when? :kind :parlor]`. Enum-style validation uses apron's
  `[:one-of? ...]`. The same shape applies to every manifest-extensible
  surface — demonstrated here on the chartroom signal and foundry berths
  via the marigold.comm.parlor and marigold.providers.fizz fixtures.

  Scenario: Module :extra-schema adds slot config keys for its impl
    Given an empty Isaac root at "/tmp/isaac-sc"
    And the chartroom fixture modules are available
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.comm.parlor {:local/root "spec/isaac/config/fixtures/modules/marigold.comm.parlor"}}
       :signals {:bert {:kind :parlor :loft "rooftop"}}}
      """
    When the config is loaded
    Then the loaded config has:
      | key               | value   |
      | signals.bert.loft | rooftop |

  Scenario: Extended slot fields conform — values coerce to declared types
    Given an empty Isaac root at "/tmp/isaac-sc"
    And the chartroom fixture modules are available
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.comm.parlor {:local/root "spec/isaac/config/fixtures/modules/marigold.comm.parlor"}}
       :signals {:bert {:kind :parlor :loft 42}}}
      """
    When the config is loaded
    Then the loaded config has:
      | key               | value |
      | signals.bert.loft | "42"  |

  Scenario: Without the module declared, extended keys are unknown
    Given an empty Isaac root at "/tmp/isaac-sc"
    And the chartroom fixture modules are available
    And the isaac file "isaac.edn" exists with:
      """
      {:signals {:bert {:kind :longwave :loft "rooftop"}}}
      """
    When the config is loaded
    Then the config has validation warnings matching:
      | key                 | value       |
      | signals[:bert].loft | unknown key |

  Scenario: Manifest field marked [:present-when? :kind X] errors when omitted
    Given an empty Isaac root at "/tmp/isaac-sc"
    And the chartroom fixture modules are available
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.comm.parlor {:local/root "spec/isaac/config/fixtures/modules/marigold.comm.parlor"}}
       :signals {:bert {:kind :parlor}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                 | value       |
      | signals[:bert].loft | is required |

  Scenario: Manifest [:one-of? ...] rejects values outside the enum
    Given an empty Isaac root at "/tmp/isaac-sc"
    And the chartroom fixture modules are available
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.comm.parlor {:local/root "spec/isaac/config/fixtures/modules/marigold.comm.parlor"}}
       :signals {:bert {:kind :parlor :loft "rooftop" :mood "elated"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                 | value          |
      | signals[:bert].mood | must be one of |

  Scenario: Manifest [:one-of? ...] accepts values inside the enum
    Given an empty Isaac root at "/tmp/isaac-sc"
    And the chartroom fixture modules are available
    And the isaac file "isaac.edn" exists with:
      """
      {:modules {:marigold.comm.parlor {:local/root "spec/isaac/config/fixtures/modules/marigold.comm.parlor"}}
       :signals {:bert {:kind :parlor :loft "rooftop" :mood "happy"}}}
      """
    When the config is loaded
    Then the loaded config has:
      | key               | value |
      | signals.bert.mood | happy |

  Scenario: Manifest schema validation applies to foundry-template fields
    Given an empty Isaac root at "/tmp/isaac-sc"
    And the chartroom fixture modules are available
    And the isaac file "isaac.edn" exists with:
      """
      {:modules   {:marigold.providers.fizz {:local/root "spec/isaac/config/fixtures/modules/marigold.providers.fizz"}}
       :foundries {:tea {:kind :fizz :fizz-level "high"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                       | value                  |
      | foundries.tea.fizz-level  | can't coerce .* to int |
