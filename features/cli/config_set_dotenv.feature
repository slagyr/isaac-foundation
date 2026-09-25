Feature: config set resolves ${VAR} refs from <root>/.env when validating a staged write (isaac-p4oj)

  isaac.config.mutate/validate-plan stages a write's effect in a scratch
  filesystem, then loads+validates it there before committing — but that
  staging fs only ever received a copy of config/, never <root>/.env. Every
  ${VAR} reference therefore read as unset during validation, even when the
  live root's .env genuinely satisfies it. A comm module's per-kind token
  field (:discord/token, required only when :type is :discord — the real
  shape isaac.comm.discord uses) tripped a phantom "required when type is
  discord" error on ANY `config set`, not just one that touched that field,
  refusing valid writes on every host whose config uses ${VAR} for a
  required-when field. validate-plan now reuses the process's
  already-locked .env snapshot instead of re-locking against the staging
  fs, so the staged load resolves ${VAR} the same way the live load did.

  Background:
    Given an empty Isaac root at "/tmp/p4oj"
    And the isaac file "/tmp/modules/marigold.p4oj.discord/resources/isaac-manifest.edn" exists with:
      """
      {:id      :marigold.p4oj.discord
       :version "0.1.0"
       :factory isaac.module.protocol/module

       :berths
       {:marigold.p4oj.discord/kind
        {:description "Comm channel impls — a :p4oj-comms entity table (named to avoid colliding with any real :comms/:isaac.server classpath fixture) with a namespaced, conditionally-required extra-schema field (:discord/token), mirroring the real isaac.comm.discord shape."
         :schema
         {:type       :map
          :key-spec   {:type :keyword}
          :value-spec {:type   :map
                       :schema {:namespace    {:type :symbol :validations [:present?]}
                                :extra-schema {:type :schema-map}}}}}}

       :marigold.p4oj.discord/kind
       {:discord
        {:namespace    marigold.p4oj.discord.stub
         :extra-schema {:discord/token {:type        :string
                                        :validations [[:present-when? :type :discord]]}}}}

       :isaac.config/schema
       {:p4oj-comms
        {:schema
         {:name           "p4oj-comms table"
          :type           :map
          :key-spec       {:type :id}
          :value-spec     {:name           :comm
                           :type           :map
                           :dynamic-schema {:berth :marigold.p4oj.discord/kind :path [:extra-schema]}
                           :schema         {:type {:type        :id
                                                   :validations [:present?
                                                                 [:registered-in? :marigold.p4oj.discord/kind]]}}}}}}}
      """
    And the isaac file "/tmp/modules/marigold.p4oj.discord/deps.edn" exists with:
      """
      {:paths ["resources"]}
      """
    And the isaac file "isaac.edn" exists with:
      """
      {:modules    {:marigold.p4oj.discord {:local/root "/tmp/modules/marigold.p4oj.discord"}}
       :p4oj-comms {:discord {:type :discord :discord/token "${P4OJ_TOKEN}"}}}
      """

  Scenario: a required-when field satisfied by ${VAR} from <root>/.env does not block an unrelated set
    Given the isaac file ".env" exists with:
      """
      P4OJ_TOKEN=abc123
      """
    When isaac is run with "config set tz UTC"
    Then the exit code is 0
    And the stdout contains "set tz = "
    And the stdout does not contain "is required when type is discord"

  Scenario: the same set on a root without .env warns instead of refusing (isaac-rxun)
    When isaac is run with "config set tz UTC"
    Then the exit code is 0
    And the stdout contains "set tz = "
    And the stdout does not contain "validation error(s) outstanding"
