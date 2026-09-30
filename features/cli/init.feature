Feature: isaac init
  Creates a fresh root with a bare config/isaac.edn. Refuses to clobber an
  existing config. What each module needs comes from its own setup
  (features/module/module_setup.feature, isaac-82nx).

  Background:
    Given the user home directory is "/tmp/user"

  Scenario: isaac init refuses when a config already exists
    Given a file "/tmp/user/.isaac/config/isaac.edn" exists with content "{:defaults {:frequencies {:crew :main} :crew {:model :llama}}}"
    When isaac is run with "init"
    Then the stderr contains "config already exists at /tmp/user/.isaac/config/isaac.edn; edit it directly."
    And the exit code is 1
