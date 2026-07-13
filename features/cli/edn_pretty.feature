Feature: Human-readable EDN pretty printer (isaac-524u)
  Default config get output uses isaac.util.edn/pretty — width-driven
  block form for maps that exceed the line budget, not clojure.pprint.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: config get renders a map subtree in pretty block form
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                           | value                    |
      | providers.xai.api              | responses                |
      | providers.xai.base-url         | https://api.x.ai/v1      |
      | providers.xai.auth             | api-key                  |
      | providers.xai.api-key          | ${XAI_API_KEY}           |
    When isaac is run with "config get providers.xai"
    Then the exit code is 0
    And the stdout contains ":api"
    And the stdout contains ":base-url"
    And the stdout contains "{"
