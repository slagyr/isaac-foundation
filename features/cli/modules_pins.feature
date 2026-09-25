Feature: Sibling pins against the registry
  Every module repo pins its sibling modules by git sha in deps.edn. Those
  pins move as a set: isaac-agent b6eb475 is built against isaac-foundation
  9ab2527, and a repo that pins the one without the other exercises a build
  no host runs. `isaac modules pins`, run from a module repo, reads the
  sibling pins — from :deps and from every alias — and checks them two ways,
  using the gitlib clones tools.deps already has.

  Coherence first: each pinned sibling's own deps.edn says which siblings it
  requires, and a repo that pins a sibling at a different sha than its
  siblings require is broken. That fails (isaac-v2x1: bumping foundation
  alone in isaac-imessage left `config validate` failing on `defaults.crew`,
  because the :defaults schema lives in isaac-agent's manifest).

  Then the fleet: the registry gives the sha the fleet installs for each
  module, and those modules' own deps.edn give the sha for foundation, which
  the registry does not list. A coherent set that is behind the fleet is
  noted — with the set to move to — but never fails, because every repo is
  behind for the hours of a train and a check the team learns to ignore is
  worse than no check (isaac-57rl). A pin ahead of the registry is likewise
  normal (isaac-yrxx).

  Background:
    Given an Isaac root at "target/test-state"
    And a git repository "fixture-foundation" with commits:
      | message         |
      | foundation: one |
      | foundation: two |
    And a git repository "fixture-agent" with commits:
      | message    |
      | agent: one |
    And the git repository "fixture-agent" gains a "deps.edn" commit "agent: two":
      """
      {:deps {io.github.slagyr/isaac-foundation {:git/url "fixture-foundation"
                                                 :git/sha "{sha of "foundation: two"}"}}}
      """
    And config file "isaac.edn" containing:
      """
      {:module-registry "registry.edn"}
      """
    And the isaac EDN file "registry.edn" exists with:
      | path                      | value                 |
      | isaac.agent.coord.git/url | fixture-agent         |
      | isaac.agent.coord.git/sha | {sha of "agent: two"} |

  Scenario: a sibling pinned at an ancestor of the registry sha is a note, not a failure
    Given a file "deps.edn" exists with content:
      """
      {:deps {io.github.slagyr/isaac-agent {:git/url "fixture-agent" :git/sha "{sha of "agent: one"}"}}}
      """
    When isaac is run with "modules pins"
    Then the stdout matches:
      | pattern                                                       |
      | isaac\.agent.*pinned [0-9a-f]{7}.*registry [0-9a-f]{7}.*older |
      | behind the fleet.*fixture-agent                               |
    And the exit code is 0

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

  Scenario: a coherent but stale set is named, with the set to move to, and does not fail
    Given the git repository "fixture-agent" gains a "deps.edn" commit "agent: three":
      """
      {:deps {io.github.slagyr/isaac-foundation {:git/url "fixture-foundation"
                                                 :git/sha "{sha of "foundation: two"}"}}}
      """
    And the isaac EDN file "registry.edn" exists with:
      | path                      | value                   |
      | isaac.agent.coord.git/sha | {sha of "agent: three"} |
    And a file "deps.edn" exists with content:
      """
      {:deps {io.github.slagyr/isaac-agent      {:git/url "fixture-agent" :git/sha "{sha of "agent: two"}"}
              io.github.slagyr/isaac-foundation {:git/url "fixture-foundation" :git/sha "{sha of "foundation: two"}"}}}
      """
    When isaac is run with "modules pins"
    Then the stdout matches:
      | pattern                                      |
      | behind the fleet.*fixture-agent              |
      | move to:.*fixture-agent [0-9a-f]{7}          |
      | move to:.*fixture-foundation [0-9a-f]{7}     |
      | coherent                                     |
    And the exit code is 0

  Scenario: an incoherent set fails and names the sibling that disagrees
    Given a file "deps.edn" exists with content:
      """
      {:deps {io.github.slagyr/isaac-agent      {:git/url "fixture-agent" :git/sha "{sha of "agent: two"}"}
              io.github.slagyr/isaac-foundation {:git/url "fixture-foundation" :git/sha "{sha of "foundation: one"}"}}}
      """
    When isaac is run with "modules pins"
    Then the stderr matches:
      | pattern                                                                             |
      | incoherent pins                                                                     |
      | fixture-agent [0-9a-f]{7} requires fixture-foundation [0-9a-f]{7}.*this repo pins    |
      | move to:.*fixture-agent [0-9a-f]{7}.*fixture-foundation [0-9a-f]{7}                  |
    And the exit code is 1

  Scenario: the same sibling pinned at two shas in one deps.edn fails
    Given a file "deps.edn" exists with content:
      """
      {:deps    {io.github.slagyr/isaac-foundation {:git/url "fixture-foundation" :git/sha "{sha of "foundation: two"}"}}
       :aliases {:spec {:extra-deps {io.github.slagyr/isaac-foundation-test-support
                                     {:git/url "fixture-foundation" :git/sha "{sha of "foundation: one"}" :deps/root "spec-support"}}}}}
      """
    When isaac is run with "modules pins"
    Then the stderr matches:
      | pattern                                                                  |
      | fixture-foundation pinned [0-9a-f]{7} \(deps\) and [0-9a-f]{7} \(alias :spec\) |
    And the exit code is 1

  # The pins fixture must never share a gitlibs cache slot with another
  # checkout (isaac-zr75). On zanebot every worker checkout used the one
  # ~/.gitlibs, keyed by the relative url "fixture-agent"; when the checkout
  # that first populated it was deleted, git fetch failed for everyone.

  Scenario: the fixture is cached under this checkout, never in the shared gitlibs (isaac-zr75)
    Given a file "deps.edn" exists with content:
      """
      {:deps {io.github.slagyr/isaac-agent {:git/url "fixture-agent" :git/sha "{sha of "agent: two"}"}}}
      """
    When isaac is run with "modules pins"
    Then the exit code is 0
    And the gitlibs cache for "fixture-agent" lives under this checkout's "target" directory

  Scenario: a cached fixture whose remote path no longer exists is recloned, not a failure (isaac-zr75)
    Given the gitlibs cache holds "fixture-agent" with a remote that points at a deleted path
    And a file "deps.edn" exists with content:
      """
      {:deps {io.github.slagyr/isaac-agent {:git/url "fixture-agent" :git/sha "{sha of "agent: one"}"}}}
      """
    When isaac is run with "modules pins"
    Then the stdout matches:
      | pattern                          |
      | behind the fleet.*fixture-agent  |
    And the exit code is 0
