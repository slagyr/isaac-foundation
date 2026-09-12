Feature: Sibling pins against the registry
  Every module repo pins its sibling modules by git sha in deps.edn. A pin
  that falls behind the registry means the repo's tests run against an old
  sibling and a fresh box that installs only that module silently gets the
  old sibling too (2026-09-11: isaac-server pinned agent 0.1.46 while the
  registry said 0.1.66). `isaac modules pins`, run from a module repo, reads
  the sibling pins, looks each up in the registry, and classifies by git
  ancestry using the gitlib clones tools.deps already has: older fails,
  equal passes, ahead passes with a note. A pin ahead of the registry is
  normal for the hours of a train and must not go red (isaac-yrxx).

  Background:
    Given an Isaac root at "target/test-state"
    And a git repository "fixture-agent" with commits:
      | message    |
      | agent: one |
      | agent: two |
    And config file "isaac.edn" containing:
      """
      {:module-registry "registry.edn"}
      """
    And the isaac EDN file "registry.edn" exists with:
      | path                      | value                 |
      | isaac.agent.coord.git/url | fixture-agent         |
      | isaac.agent.coord.git/sha | {sha of "agent: two"} |

  Scenario: a sibling pinned at an ancestor of the registry sha fails the check
    Given a file "deps.edn" exists with content:
      """
      {:deps {io.github.slagyr/isaac-agent {:git/url "fixture-agent" :git/sha "{sha of "agent: one"}"}}}
      """
    When isaac is run with "modules pins"
    Then the stderr matches:
      | pattern                                                        |
      | isaac\.agent.*pinned [0-9a-f]{7}.*registry [0-9a-f]{7}.*older |
    And the exit code is 1

  Scenario: a sibling pinned at the registry sha passes
    Given a file "deps.edn" exists with content:
      """
      {:deps {io.github.slagyr/isaac-agent {:git/url "fixture-agent" :git/sha "{sha of "agent: two"}"}}}
      """
    When isaac is run with "modules pins"
    Then the stdout matches:
      | pattern                       |
      | isaac\.agent.*current         |
    And the exit code is 0

  Scenario: a sibling pinned ahead of the registry passes with a note
    Given the git repository "fixture-agent" gains a commit "agent: three"
    And a file "deps.edn" exists with content:
      """
      {:deps {io.github.slagyr/isaac-agent {:git/url "fixture-agent" :git/sha "{sha of "agent: three"}"}}}
      """
    When isaac is run with "modules pins"
    Then the stdout matches:
      | pattern                     |
      | isaac\.agent.*ahead         |
    And the exit code is 0
