# Gherkin Table Dialect

This is the canonical guide to the table grammar used by Isaac's gherclj
step definitions.

Step code remains the source of truth. This doc is a map of the conventions
already implemented in `spec/isaac/features/steps/*.clj` and
`spec/isaac/features/matchers.clj`.

## Table Shapes

`key | value`

- Vertical assertion/setup tables.
- Common in `config:`, `the loaded config has:`, ACP message builders,
  prompt/request matchers, and provider request assertions.
- The left column is usually a dotted path, but some steps treat it as a
  plain key.

`path | value`

- Patch/write tables for nested EDN data.
- Common in `config is updated:`, `the isaac EDN file "..." exists with:`,
  `the EDN isaac file "..." contains:`, and file/config assertions.
- `path` usually means dot-separated map navigation.

Horizontal tables

- One row per entry, with headers naming the fields to match.
- Common in transcript/session/log matchers.

## Shared Matcher DSL

The shared matcher code lives in `spec/isaac/features/matchers.clj`.

### Paths

- Dotted paths walk nested maps: `message.role`
- `[n]` indexes address vectors: `messages[0].content`
- Only steps that use the shared matcher DSL get `[n]` support automatically.
  Many setup writers only split on `.`.

### Cell Syntax

- Blank cell: expect `nil`
- `#*`: any non-`nil` value
- `#"..."`: full-string regex match, DOTALL-enabled
- `#"...":name`: regex match and capture the whole actual value as `name`
- `#name`: reference a previously captured value
- Integers: parsed as numbers
- `true` / `false`: parsed as booleans
- Everything else: literal string

### `#` Headers

- `#index`: positional matching instead of unordered matching
- Negative indexes are allowed: `-1` means last entry
- Any other `#...` header is metadata and ignored by the shared matcher
- `#comment` is the conventional metadata column for human notes

### Row vs Cell Meaning

- `#comment` is a header convention, not a magic cell value
- In matcher cells, `#...` usually means matcher syntax (`#*`, regex, ref)
- In writer/update tables, `#delete` is the one special value sentinel

## `#delete`

`#` is the established escape prefix for special table behavior. For config
mutation, `#delete` means remove that leaf path instead of writing the string
`"#delete"`.

Supported in:

- `config:`
- `config is updated:`
- `the isaac EDN file "..." exists with:`
- `the EDN isaac file "..." contains:` when used in write mode

Behavior:

- `config:` patches the current in-memory/persisted config and removes the
  dotted key
- `config is updated:` delta-merges into the current config file and removes
  the dotted path
- File writers normally build a fresh EDN map from the table
- If any row uses `#delete`, file writers first load the current file, then
  apply the table as a patch so there is something to delete from

Example:

```gherkin
When config is updated:
  | path            | value   |
  | comms.abby      | #delete |
```

## Value Parsing By Step Family

The same-looking cell is not parsed the same way everywhere.

### Shared matcher tables

- Blank => `nil`
- Integers => numbers
- `true` / `false` => booleans
- Regex/ref/wildcard syntax applies
- Otherwise values stay strings

### `config:`

- Dotted `key` uses `assoc-in` with keywordized segments
- Integers => numbers
- `true` / `false` => booleans
- Otherwise values stay strings
- `#delete` removes the key

### `config is updated:`

- Dotted `path` uses `assoc-in` / delete on keywordized segments
- Integers => numbers
- `true` / `false` => booleans
- Leading `[`, `{`, `:`, or `"` => EDN read
- Otherwise values stay strings
- `#delete` removes the key

### `the isaac EDN file "..." exists with:`

- Dotted `path` uses keywordized segments
- Integers => numbers
- `true` / `false` => booleans
- Leading `[`, `{`, `:`, or `"` => EDN read
- `tools.allow` => comma-separated keyword vector
- Some file/path combinations coerce bare words to keywords:
  `defaults.crew`, `defaults.model`, crew `model`, cron `crew`, provider `api`
- Otherwise values stay strings
- `#delete` removes the key from the current file

### `the EDN isaac file "..." contains:`

- In assert mode, rows are expectations against the file on disk
- In write mode, dotted `path` writes into the file
- Write-mode values parse more simply than `exists with:`:
  integers, booleans, and bare lowercase words become typed values;
  otherwise strings stay strings
- `#delete` is only meaningful in write mode

### ACP message builders

- `key | value` tables use the shared matcher path parser, so `[n]` works here
- `null` => `nil`
- Integers / booleans parse
- Leading `{` or `[` => EDN read

### Tool/session helper tables

- Some steps have their own per-column parsing instead of the shared matcher
- Common special cases:
  - JSON/EDN columns such as `arguments` or `parameters`
  - numeric token/count/timeout columns
  - booleans like `isError`

When in doubt, read the step helper before inventing a new convention.

## Step-Specific Dialects

### Transcript matching

`session "..." has transcript matching:` adds a few rules on top of the shared
matcher DSL.

- Without `#index`, matching is content-based, not strictly positional
- With `#index`, matching becomes positional
- Unless a row explicitly targets `type = session`, session header entries are
  skipped by default
- Blank `message.content` cells are normalized to `#*` for `message` rows
- Compaction summary rows are included unless a `summary` column is present

### Provider request matching

`an outbound HTTP request to "..." matches:` uses a special row form for index
selection:

```gherkin
Then an outbound HTTP request to "https://example.test" matches:
  | key    | value |
  | #index | 1     |
  | method | POST  |
```

That `#index` is a row key, not a header.

### Tool result line matching

`the tool result lines match:` has its own local `#index` header support.

- With `#index`, each row checks a specific line number
- Without it, rows are matched in order as substring checks

## Empty Cells

- Matcher tables: blank means `nil`
- Writer/setup tables: blank usually stays an empty string unless that step has
  custom parsing
- Do not assume blank means delete; use `#delete` explicitly

## Practical Rules

- Reuse an existing table dialect before inventing a new step
- Use `#comment` for human notes in matcher tables
- Use `#delete` for removal, never a blank cell
- Use quoted strings or EDN-looking values only in step families that parse them
- If a scenario needs array indexing, prefer a step that uses the shared matcher
  path parser
- Before adding a new step, run `bb steps`
