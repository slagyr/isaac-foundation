Feature: Any config entry may inherit from a template via :_base (isaac-h2ck)
  Templating exists, works well, and only hail can use it: template-band?,
  merge-bands and `base` all live in isaac-hail/src/isaac/hail/band_resolve.clj.
  Nothing else in Isaac inherits, so zanebot's seven crews each repeat their
  tool allow-lists and directory grants — one policy change is seven edits.

  The mechanism moves to foundation and applies to any config map entity.
  Using a template is explicit: an entry names its own base. Nothing is
  inherited by proximity. `_<name>` is a template and is never addressable as a
  real entity; `_` exactly is the map's own values (isaac-49zp).

  Background:
    Given an empty Isaac root at "target/test-h2ck"

  @wip
  Scenario: an entry inherits the fields of its template
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {}
       :crew    {"_worker" {:model "grover" :tags #{:role/worker}}
                 "marvin"  {:_base "_worker" :soul "You are Marvin."}}}
      """
    When isaac is run with "config get crew.marvin"
    Then the exit code is 0
    And the stdout contains "grover"
    And the stdout contains "role/worker"
    And the stdout contains "You are Marvin."

  @wip
  Scenario: the entry's own keys win, maps merge key-wise, vectors replace
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {}
       :crew    {"_worker" {:model "grover"
                            :tools {:allow [:fs/read :exec/run] :directories {:allow ["/tmp"]}}}
                 "marvin"  {:_base "_worker"
                            :model "sonnet"
                            :tools {:allow [:fs/read]}}}}
      """
    When isaac is run with "config get crew.marvin"
    Then the exit code is 0
    And the stdout contains "sonnet"
    And the stdout contains "directories"

  @wip
  Scenario: a template is not addressable as a real entity
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {}
       :crew    {"_worker" {:model "grover"}
                 "marvin"  {:_base "_worker"}}}
      """
    When isaac is run with "crew list"
    Then the exit code is 0
    And the stdout contains "marvin"
    And the stdout does not contain "_worker"

  @wip
  Scenario: a :_base naming no template is a load error
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {}
       :crew    {"marvin" {:_base "_missing"}}}
      """
    When isaac is run with "config validate"
    Then the exit code is 1
    And the stderr contains "marvin"
    And the stderr contains "_missing"

  @wip
  Scenario: a template cycle is a load error naming the cycle
    Given the isaac file "config/isaac.edn" exists with:
      """
      {:modules {}
       :crew    {"_a"     {:_base "_b"}
                 "_b"     {:_base "_a"}
                 "marvin" {:_base "_a"}}}
      """
    When isaac is run with "config validate"
    Then the exit code is 1
    And the stderr contains "_a"
    And the stderr contains "_b"
