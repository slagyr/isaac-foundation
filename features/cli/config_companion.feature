Feature: Config companion .md — the load side reads the table's own descriptor (isaac-kcck)

  Moved from isaac-agent's features/config/composition.feature and
  features/config/cli.feature. A table's `:companion` descriptor
  (`:field` + `:mode`) plus its `:entity-dir` decide which field a `.md`
  companion fills, on both the LOAD side (isaac.foundation.config.companions/
  companion-md-relative, isaac.foundation.config.entities/resolve-entity-data) and the
  SET side (isaac.foundation.config.mutate/companion-spec) — isaac.foundation.config.companions
  names no kind or field here. Before isaac-kcck, companion-md-relative
  hard-coded the kinds :crew (-> :soul) and :berths (-> :ledger), so these
  five scenarios stayed in isaac-agent even after isaac-mxgn/isaac-601n
  moved the rest of composition/routing to foundation. This fixture proves
  the mechanism (mode :exclusive: inline OR .md, never both) with a fixture
  entity-dir and field, so no agent concept is in the picture.

  Fixture module marigold.kcck.vessels contributes a `:vessels` entity-dir
  table with a plain `:captain` field and a `:notes` field declared as the
  table's `:companion` (mode :exclusive). Manifest-only — no :factory,
  deps.edn, or src.

  Background:
    Given an empty Isaac root at "target/test-kcck-companion"
    And the isaac file "vessels-module/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.kcck.vessels
       :version "0.1.0"
       :isaac.config/schema
       {:vessels {:entity-dir          "vessels"
                  :merge-root-entity? true
                  :companion           {:field :notes :mode :exclusive}
                  :schema              {:name       "vessel table"
                                       :type       :map
                                       :key-spec   {:type :string}
                                       :value-spec {:name   :vessel
                                                   :type   :map
                                                   :schema {:captain {:type :string}
                                                           :notes   {:type :string}}}}}}}
      """
    And the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.kcck.vessels {:local/root "target/test-kcck-companion/vessels-module"}}}
      """

  # ----- Load -----

  Scenario: notes load from a companion .md file when :notes is absent
    Given the isaac file "config/vessels/cordelia.edn" exists with:
      """
      {:captain "Cordelia"}
      """
    And the isaac file "config/vessels/cordelia.md" exists with:
      """
      You are Cordelia, first mate.
      """
    When isaac is run with "config get vessels.cordelia.notes"
    Then the stdout contains "You are Cordelia, first mate."
    And the exit code is 0

  Scenario: defining notes in both :notes and <id>.md is an error
    Given the isaac file "config/vessels/cordelia.edn" exists with:
      """
      {:captain "Cordelia" :notes "Inline notes."}
      """
    And the isaac file "config/vessels/cordelia.md" exists with:
      """
      File notes.
      """
    When isaac is run with "config validate"
    Then the stderr contains "vessels.cordelia.notes"
    And the stderr contains "must be set in .edn OR .md"
    And the exit code is 1

  # ----- Set -----

  Scenario: set writes notes to the companion .md when it already exists
    Given the isaac file "config/vessels/cordelia.edn" exists with:
      """
      {:captain "Cordelia"}
      """
    And the isaac file "config/vessels/cordelia.md" exists with:
      """
      Old notes.
      """
    When isaac is run with "config set vessels.cordelia.notes \"New notes.\""
    Then the isaac file "config/vessels/cordelia.md" does not contain "Old notes."
    And the isaac file "config/vessels/cordelia.edn" does not contain "notes"
    And the log has entries matching:
      | level | event       | path                    | value      | file                 |
      | :info | :config/set | vessels.cordelia.notes  | New notes. | vessels/cordelia.md |
    And the exit code is 0
    When isaac is run with "config get vessels.cordelia.notes"
    Then the stdout contains "New notes."
    And the exit code is 0

  Scenario: set creates a companion .md when a new notes value exceeds 64 characters
    Given the isaac file "config/vessels/cordelia.edn" exists with:
      """
      {:captain "Cordelia"}
      """
    When isaac is run with "config set vessels.cordelia.notes \"You are Cordelia, first mate of the Marigold, steady-handed, sharp-eyed, and always three moves ahead of the weather.\""
    Then the isaac file "config/vessels/cordelia.md" exists
    And the isaac file "config/vessels/cordelia.edn" does not contain "notes"
    And the log has entries matching:
      | level | event       | path                   | file                 |
      | :info | :config/set | vessels.cordelia.notes | vessels/cordelia.md |
    And the exit code is 0
    When isaac is run with "config get vessels.cordelia.notes"
    Then the stdout contains "sharp-eyed"
    And the exit code is 0

  Scenario: set writes short notes inline in the entity file
    Given the isaac file "config/vessels/cordelia.edn" exists with:
      """
      {:captain "Cordelia"}
      """
    When isaac is run with "config set vessels.cordelia.notes \"First mate.\""
    Then the isaac file "config/vessels/cordelia.md" does not exist
    And the isaac file "config/vessels/cordelia.edn" EDN contains:
      | path  | value       |
      | notes | First mate. |
    And the log has entries matching:
      | level | event       | path                   | value       | file                  |
      | :info | :config/set | vessels.cordelia.notes | First mate. | vessels/cordelia.edn |
    And the exit code is 0

  # ----- Inline entry, no entity file -----

  Scenario: a :cron entry inline in isaac.edn loads cleanly with no module declaring :cron (isaac-208u)
    # :cron is foundation's own root-level companion table (its `:prompt`
    # field resolves inline-or-.md the same `:required` way any module's
    # companion field does), but no module here contributes a `:cron`
    # schema at all, so companion-md-relative has no `:entity-dir` to build
    # a `.md` path from. The companion side must still apply only to
    # entries that have an entity file: with nothing to look up, the inline
    # entry loads with its own fields rather than the load crashing while
    # trying to read a bogus "<root>/config" path as if it were a file.
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:cron {:heartbeat {:expr "0 0 * * *" :prompt "x"}}}
      """
    When isaac is run with "config validate"
    Then the stdout contains "OK - config is valid"
    And the exit code is 0
