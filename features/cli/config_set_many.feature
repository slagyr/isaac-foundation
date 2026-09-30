Feature: Atomic multi-path config writes (isaac-cvri)
  isaac.config.mutate/set-many! (name open) applies several {op, path, value}
  operations as ONE plan: staged, validated once against the resulting
  config, and applied all-or-nothing. A blocking error on ANY op refuses the
  whole batch — nothing is written, not even the individually-valid pairs.
  This is the primitive isaac-handbook's handbook__configure (isaac-lshz)
  calls; isaac config set/unset are unchanged and stay single-path.

  New-entity placement inside a batch follows the SAME precedent as plain
  `config set` (edit where it lives; new + siblings all files → new file;
  else prefer-entity-files; else inline) — no separate override. See
  `features/cli/config_set_new_entity_placement.feature` for that rule's
  own CLI-level proof; the last scenario here just confirms a batch gets
  it too, since it reuses the same `set-plan`/`choose-set-location`.

  Background:
    Given the chartroom fixture modules are available
    And config file "isaac.edn" containing:
      """
      {:modules {:marigold.comm.parlor {:local/root "spec/isaac/config/fixtures/modules/marigold.comm.parlor"}}
       :station {:primary "grover"}}
      """

  @wip
  Scenario: two pairs on different top-level keys are written together
    When config is set atomically:
      | op  | path            | value  |
      | set | station.primary | helm   |
      | set | relay.tower.gain | 5     |
    Then the mutation succeeds
    And the config has no validation errors
    When the config is reloaded
    Then the loaded config has:
      | key              | value |
      | station.primary  | helm  |
      | relay.tower.gain | 5     |

  @wip
  Scenario: an invalid pair refuses the whole batch; nothing is written
    When config is set atomically:
      | op  | path                    | value      |
      | set | station.primary        | helm       |
      | set | kit.distant-read.vendor | not-brave  |
    Then the mutation is refused
    When the config is reloaded
    Then the loaded config has:
      | key             | value |
      | station.primary | grover |
    And the config file "isaac.edn" does not contain "helm"

  @wip
  Scenario: an unset rides in the same atomic batch as a set
    Given config file "isaac.edn" containing:
      """
      {:modules {:marigold.comm.parlor {:local/root "spec/isaac/config/fixtures/modules/marigold.comm.parlor"}}
       :station {:primary "grover" :backup "steady"}}
      """
    When config is set atomically:
      | op    | path            | value |
      | unset | station.backup |       |
      | set   | relay.tower.gain | 5    |
    Then the mutation succeeds
    When the config is reloaded
    Then the loaded config has:
      | key              | value |
      | relay.tower.gain | 5     |
    And the config file "isaac.edn" does not contain "steady"

  @wip
  Scenario: an undeclared path in one op refuses the whole batch before any write
    When config is set atomically:
      | op  | path            | value |
      | set | station.primary | helm  |
      | set | station.bogus   | oops  |
    Then the mutation is refused with an error matching "bogus"
    And the config file "isaac.edn" does not contain "helm"

  @wip
  Scenario: a new whole-entity value in a batch follows the same siblings-all-files placement precedent as config set
    Given the isaac file "config/berths/captain.edn" exists with:
      """
      {:gauge "llama"}
      """
    When config is set atomically:
      | op  | path           | value                                                                                  |
      | set | berths.helios  | {:gauge "llama" :ledger "Hold the helios line steady, day and night, no exceptions."} |
    Then the mutation succeeds
    And the isaac file "config/berths/helios.edn" EDN contains:
      | path  | value |
      | gauge | llama |
    And the isaac file "config/berths/helios.md" exists
    And the config file "isaac.edn" does not contain "helios"
