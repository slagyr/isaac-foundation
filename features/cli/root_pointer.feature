Feature: Root-pointer config file
  Isaac locates its root directory via a lookup chain. A pointer file
  at ~/.config/isaac.edn (or ~/.isaac.edn as fallback) can specify
  an alternate root, avoiding --root on every command.

  Lookup order (first hit wins):
    1. --root CLI flag
    2. ISAAC_ROOT environment variable
    3. ~/.config/isaac.edn with {:root "/path"}
    4. ~/.isaac.edn with {:root "/path"}
    5. ~/.isaac (built-in default)

  The resolved config is proved via :tz, a foundation-owned config key.

  Background:
    Given the user home directory is "/tmp/user"

  Scenario: Isaac reads its root from ~/.config/isaac.edn
    Given the file "/tmp/user/.config/isaac.edn" exists with:
      """
      {:root "/tmp/elsewhere/.isaac"}
      """
    And the file "/tmp/elsewhere/.isaac/config/isaac.edn" exists with:
      """
      {:tz "Pacific/Honolulu"}
      """
    When isaac is run with "config get tz"
    Then the stdout contains "Pacific/Honolulu"
    And the exit code is 0

  Scenario: --root flag overrides the pointer file
    Given the file "/tmp/user/.config/isaac.edn" exists with:
      """
      {:root "/tmp/pointer-path/.isaac"}
      """
    And the file "/tmp/pointer-path/.isaac/config/isaac.edn" exists with:
      """
      {:tz "America/New_York"}
      """
    And the file "/tmp/flag-path/.isaac/config/isaac.edn" exists with:
      """
      {:tz "Asia/Tokyo"}
      """
    When isaac is run with "--root /tmp/flag-path/.isaac config get tz"
    Then the stdout contains "Asia/Tokyo"
    And the stdout does not contain "America/New_York"
    And the exit code is 0
