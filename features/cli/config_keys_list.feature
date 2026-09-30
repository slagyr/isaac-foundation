Feature: Config keys / list — bare key listing and structured output are generic (isaac-601n)
  Moved from isaac-agent's features/config/cli.feature (isaac-grof) —
  `config keys [path]` prints bare key names at a path (or root keys with
  none given); `config list [path]` prints the same keys with their
  contributing config source; a leaf path prints nothing; both emit
  structured output under --json, as does `config validate --json`.
  isaac.foundation.config.cli owns these commands generically; agent's copy only ever
  exercised them through :providers. This fixture proves the same mechanism
  with a fixture entity-dir, no agent concept in the picture, and confirms
  secret-looking values never leak into a bare key listing.

  Two isaac-agent scenarios also moved here because they document the same
  static, config-independent CLI help/registration text as the keys/list
  commands: "config help lists set and unset subcommands" and "config set
  --help documents stdin form and examples" — both assert isaac.foundation.config.cli.
  set's own hard-coded help copy, unrelated to any loaded config.

  Fixture module marigold.601n.keys contributes a `:vessels` entity-dir
  table with `:captain` (string) and a `:beacon-code` (string, standing in
  for a secret-shaped value) field. Manifest-only — no :factory, deps.edn,
  or src.

  Background:
    Given an empty Isaac root at "target/test-601n-keys-list"
    And the isaac file "vessels-module/resources/isaac-manifest.edn" exists with:
      """
      {:id :marigold.601n.keys
       :version "0.1.0"
       :isaac.config/schema
       {:vessels
        {:entity-dir "vessels"
         :merge-root-entity? true
         :schema
         {:name "vessel table"
          :type :map
          :key-spec {:type :string}
          :value-spec
          {:name :vessel
           :type :map
           :schema
           {:captain {:type :string}
            :beacon-code {:type :string}}}}}}}
      """
    And the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.601n.keys {:local/root "target/test-601n-keys-list/vessels-module"}}}
      """

  Scenario: config keys prints bare key names at a path
    Given the isaac EDN file "config/vessels/wavecrest.edn" exists with:
      | path         | value               |
      | beacon-code  | sk-real-secret-value |
    And the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value |
      | captain | Atticus |
    When isaac is run with "config keys vessels"
    Then the stdout contains "wavecrest"
    And the stdout contains "cordelia"
    And the stdout does not contain "config/vessels"
    And the stdout does not contain "sk-real-secret-value"
    And the exit code is 0

  Scenario: config keys with no path lists root keys
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.601n.keys {:local/root "target/test-601n-keys-list/vessels-module"}}
       :vessels {:cordelia {:captain "Atticus"}}}
      """
    When isaac is run with "config keys"
    Then the stdout contains "vessels"
    And the stdout contains "modules"
    And the stdout does not contain "Atticus"
    And the exit code is 0

  Scenario: config list prints keys with their config source
    Given the isaac EDN file "config/vessels/wavecrest.edn" exists with:
      | path        | value                |
      | beacon-code | sk-real-secret-value |
    When isaac is run with "config list vessels"
    Then the stdout contains "wavecrest"
    And the stdout contains "config/vessels/wavecrest.edn"
    And the stdout does not contain "sk-real-secret-value"
    And the exit code is 0

  Scenario: config list with no path lists root keys and sources
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {:marigold.601n.keys {:local/root "target/test-601n-keys-list/vessels-module"}}
       :vessels {:cordelia {:captain "Atticus"}}}
      """
    When isaac is run with "config list"
    Then the stdout contains "vessels"
    And the stdout contains "config/isaac.edn"
    And the stdout does not contain "Atticus"
    And the exit code is 0

  Scenario: a leaf path prints nothing
    Given the isaac EDN file "config/vessels/wavecrest.edn" exists with:
      | path        | value                |
      | beacon-code | sk-real-secret-value |
    When isaac is run with "config keys vessels.wavecrest.beacon-code"
    Then the stdout is empty
    And the exit code is 0

  Scenario: keys and list emit structured output under --json
    Given the isaac EDN file "config/vessels/wavecrest.edn" exists with:
      | path        | value                |
      | beacon-code | sk-real-secret-value |
    And the isaac EDN file "config/vessels/cordelia.edn" exists with:
      | path    | value   |
      | captain | Atticus |
    When isaac is run with "config keys vessels --json"
    Then the stdout JSON contains:
      | path | expected     |
      | 0    | "cordelia"   |
      | 1    | "wavecrest"  |
    When isaac is run with "config list vessels --json"
    Then the stdout contains "\"source\""
    And the stdout does not contain "sk-real-secret-value"
    And the exit code is 0

  Scenario: config validate --json emits structured warnings
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules      {:marigold.601n.keys {:local/root "target/test-601n-keys-list/vessels-module"}}
       :vessels      {:cordelia {:captain "Atticus"}}
       :experimental {:feature-flag true}}
      """
    When isaac is run with "config validate --json"
    Then the stdout parses as JSON with a warnings array naming the offending path
    And the exit code is 0

  Scenario: config help lists set and unset subcommands
    When isaac is run with "help config"
    Then the stdout matches:
      | pattern                                                  |
      | set <config-path> <value>\s+Set a value at a config path |
      | unset <config-path>\s+Remove a value at a config path    |
    And the exit code is 0

  Scenario: config set --help documents stdin form and examples
    When isaac is run with "config set --help"
    Then the stdout matches:
      | pattern                               |
      | Usage: isaac config set <config-path> |
      | -\s+Read the value as EDN from stdin  |
    And the exit code is 0
