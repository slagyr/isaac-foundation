Feature: Any config entry may inherit from a template via :_base (isaac-h2ck)
  Templating exists, works well, and only hail can use it: template-band?,
  merge-bands and `base` all live in isaac-hail/src/isaac/hail/band_resolve.clj.
  Nothing else in Isaac inherits, so zanebot's seven crews each repeat their
  tool allow-lists and directory grants — one policy change is seven edits.

  The mechanism moves to foundation and applies to any config map entity.
  Using a template is explicit: an entry names its own base. Nothing is
  inherited by proximity. `_<name>` is a template and is never addressable as a
  real entity; `_` exactly is the map's own values (isaac-49zp).

  These scenarios use the chartroom fixture berth :signals rather than a real
  kind like :crew, whose schema lives in isaac-agent and is not reachable from
  foundation.

  Background:
    Given the chartroom fixture modules are available

  Scenario: an entry inherits the fields of its template
    Given config file "isaac.edn" containing:
      """
      {:modules {:marigold.comm.parlor {:local/root "spec/isaac/config/fixtures/modules/marigold.comm.parlor"}}
       :signals {:_parlour-base {:kind "parlor" :loft "upper" :color "blue"}
                 :parlour       {:_base "_parlour-base" :mood "happy"}}}
      """
    When the config is loaded
    Then the config has no validation errors
    And the loaded config has:
      | key                   | value |
      | signals.parlour.loft  | upper |
      | signals.parlour.color | blue  |
      | signals.parlour.mood  | happy |

  Scenario: the entry's own keys win over the template's
    Given config file "isaac.edn" containing:
      """
      {:modules {:marigold.comm.parlor {:local/root "spec/isaac/config/fixtures/modules/marigold.comm.parlor"}}
       :signals {:_parlour-base {:kind "parlor" :loft "upper" :color "blue"}
                 :parlour       {:_base "_parlour-base" :color "green"}}}
      """
    When the config is loaded
    Then the config has no validation errors
    And the loaded config has:
      | key                   | value |
      | signals.parlour.color | green |
      | signals.parlour.loft  | upper |

  Scenario: a template is never validated or instantiated as a real entry
    Given config file "isaac.edn" containing:
      """
      {:modules {:marigold.comm.parlor {:local/root "spec/isaac/config/fixtures/modules/marigold.comm.parlor"}}
       :signals {:_parlour-base {:color "blue"}
                 :parlour       {:_base "_parlour-base" :kind "parlor" :loft "upper"}}}
      """
    When the config is loaded
    Then the config has no validation errors
    And the loaded config has:
      | key                   | value |
      | signals.parlour.color | blue  |

  Scenario: a :_base naming no template is a load error
    Given config file "isaac.edn" containing:
      """
      {:modules {:marigold.comm.parlor {:local/root "spec/isaac/config/fixtures/modules/marigold.comm.parlor"}}
       :signals {:parlour {:_base "_missing" :kind "parlor" :loft "upper"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key               | value               |
      | signals[:parlour] | #"(?s).*_missing.*" |

  Scenario: a template cycle is a load error naming the cycle
    Given config file "isaac.edn" containing:
      """
      {:modules {:marigold.comm.parlor {:local/root "spec/isaac/config/fixtures/modules/marigold.comm.parlor"}}
       :signals {:_alpha  {:_base "_beta"}
                 :_beta   {:_base "_alpha"}
                 :parlour {:_base "_alpha" :kind "parlor" :loft "upper"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key               | value             |
      | signals[:parlour] | #"(?s).*_alpha.*" |
