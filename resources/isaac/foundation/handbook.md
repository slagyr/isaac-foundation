# isaac.foundation — Isaac's operating handbook, introduction

You are a crew running inside Isaac. This chapter is the introduction to
the handbook: the concepts every other chapter assumes. Foundation owns
the root, config, modules, the scheduler, and logging — but it ships no
crew, no tools, and no comms of its own. `handbook__read` and
`handbook__configure`, the two tools you're using right now, are
contributed by the **isaac-handbook** module, not foundation; every
other capability comes from other modules too, each with its own
chapter. Read this chapter first.

You reach this handbook through `handbook__read`. Call it with no
`topics` for a table of contents; call it with a topic id (a module id
for a whole chapter, `<module-id>#<slug>` for one section) to read that
part directly. This chapter's own topic id is `isaac.foundation`.

You change config through `handbook__configure`, which sets and unsets
config values with the same rules as the operator's `isaac config set` /
`isaac config unset` commands. Where a change has no config path —
installing a module, restarting a process, editing `--root` — this
chapter says so plainly: that part is CLI-only, and you should ask an
operator to run it rather than look for a workaround.

## Vocabulary

Isaac is modeled as a spaceship. The words below are used the same way
in every chapter.

| Term | Meaning |
|---|---|
| Ship | The whole running system. |
| Bridge | Where input is triaged — slash commands vs. plain prompts. |
| Comm | A channel of communication (CLI, ACP, memory, Discord, …). Owned by comm modules; this chapter never lists a specific comm. |
| Crew | An agent: a provider, a model, a soul, and a set of allowed tools. |
| Quarters | A crew member's writable filesystem area, under `<root>`, outside `<root>/config`. |
| Modules | Pluggable capabilities that extend the ship. Foundation is the one module every install has. |
| Berths | Named extension points a module declares; other modules contribute to them, or users configure them. `:isaac/cli` (commands) and `:isaac/log-stream` (log streams) are berths foundation itself declares. |
| Hail, bands | Out-of-band ways to start or resume a turn without a live conversation. A band is a named, config-declared template for one. Owned by the hail module — one line here, its own chapter has the rest. |
| Soul | A crew member's standing orders — a markdown companion file. Owned by the agent module. |
| Session | One ongoing, persisted conversation with a crew member. Owned by the agent module. |
| Episode | A sealed, distilled slice of a session kept for later recall. Owned by the episodes module. |
| Turn | One pass through the tool loop — the unit hail submits and the agent module executes. |

`handbook__read`'s reference topics (not chapters) use their own id
shapes: `module:<id>`, `crew:<id>`, `config:<dotted.path>`.

### Troubleshooting

- A term used in another chapter that isn't in this table is probably
  owned by the module that chapter belongs to — read that chapter, or
  ask `handbook__read` for its table of contents.
- If a topic id you expect (a module id, or `<module-id>#<slug>`) comes
  back "unknown", the module may not be installed, or may not ship a
  handbook chapter yet — see Modules and berths, below.

## Runtime

Isaac runs one of two ways: **babashka** (`bb`) or the **JVM** (`clj`,
or a packaged launcher). Both run the same source. In a development
checkout, `bb isaac <command>` runs everything with babashka's fast
startup. A packaged install goes through `isaac.launcher`, which resolves
the config `:modules` coordinates, composes a classpath from them, then
boots the same `isaac.main` babashka boots.

There's no config path for it, but the handbook's own inventory reports
it: a reference topic names the live runtime as `babashka x.y` or `JVM
x.y` (babashka is detected by the presence of the `babashka.version`
system property; the JVM reports `java.version` instead). Check that
topic through `handbook__read` rather than guessing from a tool's
behavior.

**The trap to know about**: babashka and the JVM can disagree on a
Clojure protocol whose method set changed. A protocol implementation
(an inline `reify`) that's missing a newly-added method fails
differently on each — babashka can tolerate it in places the JVM
throws `AbstractMethodError`. This is a source-level trap for module
authors, not something you can see or fix from inside a turn; if a tool
call fails with an error mentioning a protocol or `AbstractMethodError`,
report it rather than retrying — retrying won't change which runtime is
live.

Isaac also runs as a long-lived **server** process (`isaac server`),
usually managed by the OS: a macOS LaunchAgent or a Linux systemd user
unit, installed by `isaac service install`. That's CLI-only — there is
no config path that starts or stops the service. **Config changes do
not need a restart.** Foundation watches the whole config tree and
reloads it live; see Config → hot reload, below. If something looks
stale after a config change, that is a bug to report, not a cue to ask
for a restart.

Inside the server process, modules contribute **components** (via the
`:isaac/component` berth). Components are inert data at config-load
time — nothing starts just because config loaded, which is why `isaac
modules list` never starts anything. On server boot, the runner
instantiates and starts every component in module topological order;
on shutdown it stops them in the reverse order.

### Troubleshooting

- A change to `handbook.max-chars` or any other config value not taking
  effect: confirm hot reload is on (`config:hot-reload` should read
  `true` or be unset — unset defaults to on). If it's explicitly `false`,
  that install has opted out of hot reload and does need a restart,
  which is CLI-only.
- A component that never seems to start: components only start inside
  the server process (`isaac server`), never for a plain CLI command.
  Foundation tracks which components are currently running (id, owning
  module, and boot order) and the handbook's inventory lists them —
  check there before assuming one is down; a component missing from
  that list has genuinely not started.

## Files

Everything Isaac reads or writes lives under one **root** directory
(default `~/.isaac`; an operator can point it elsewhere with `--root`,
`ISAAC_ROOT`, or a pointer file — all CLI/environment-level, not a
config path). Under the root:

- `config/` — declarative, hand-editable, version-controllable intent.
  Never changed by Isaac itself except through a `handbook__configure`
  (or `isaac config set`/`unset`) write.
- everything else — mutable runtime state: sessions, transcripts,
  last-run timestamps, queued deliveries. Not addressed by
  `handbook__configure`.

Config content comes in three shapes:

- **EDN** — `.edn` files hold Clojure-style data: keywords (`:like-this`),
  maps (`{:key value}`), sets (`#{:a :b}`), strings (`"quoted"`), and `;`
  comments. A namespaced keyword keeps its `/` even inside a dotted config
  path (`gchat/allow-from` is one segment, not two).
- **Markdown with frontmatter** — an entity file can be `.md` instead of
  `.edn`: a `---`-delimited YAML frontmatter block for its fields, then a
  body. One frontmatter field set to the literal value `_` says "this
  field's value is the markdown body below it" — that's how a crew's
  soul or a cron job's prompt is written as prose instead of a quoted
  EDN string.
- **Companion markdown** — some fields (a crew's soul, a hail band's
  prompt) can live as prose in a `.md` file next to the entity's `.edn`
  file, named for the entity, instead of inline.

Any config key may live inline in `isaac.edn`, as its own `<key>.edn`
file, or as a `<key>/` directory (one file per sub-key, or an `_.edn`
holding several at once). This applies uniformly — `:modules`,
`:defaults`, a module's own table, all the same rule. A key can be a
file *or* a directory, never both; having it in two places at once is a
load error, not a silent merge.

### Troubleshooting

- **A frontmatter block fails to parse or a field lands as the wrong
  value.** YAML frontmatter treats an unquoted `: ` (colon-space) inside
  a scalar as a new mapping, not as text. A description or prompt field
  containing `: ` unquoted can silently corrupt the whole block. Quote
  the value (`description: "explain: how it works"`) whenever it
  contains a colon followed by a space.
- **A `.md` file under a config directory is reported as "dangling".**
  It has no frontmatter of its own and doesn't match any entity's
  companion field — Isaac can't tell what it's for. Either give it
  frontmatter (making it its own entity) or remove it.
- **A key you expect from `handbook__configure`/`config get` isn't
  there.** Check whether it's split into its own file or directory
  instead of living inline in `isaac.edn` — `config get <key>` reads the
  merged result regardless of which shape it's stored in, so if it's
  still missing after that, it's genuinely unset.

## Config

### Composition

The **effective** config is a merge, in this order: `isaac.edn` (the
root file), then every split-out `<key>.edn` / `<key>/` (see Files,
above), then any per-module schema defaults. Nothing here is layered by
"environment" — there's one config tree per root.

An entry can inherit from a sibling **template** with `:_base "_name"`:
a one-level merge where the entry's own keys win over the template's,
and `_name` (any key starting with `_`, other than exactly `_`) is
itself never a real, addressable entity — it exists only to be inherited
from. A template naming a nonexistent base, or a cycle of templates, is
a load error.

### Paths and editing

A **config path** is a dotted address into the tree: `crew.marvin.model`,
`defaults.frequencies.crew`, `logging.level`. Brackets address a set
member, a numeric index, or a literal string key: `crew.marvin.tags[:role/worker]`,
`list[0]`, `map["a key"]`. A namespaced keyword inside a path (like
`gchat/allow-from`) is one segment, dot-separated from its neighbors —
the `/` is part of the name, not a separator.

To change a value: call `handbook__configure` (or `isaac config set`)
with the path and the new value. Examples, using the fictional *Marigold*
crew:

```
config set crew.cordelia.model quantum-anvil
config set defaults.effort 6
```

A **set-typed** field (like a crew's tags) takes the *member* in the
path itself, with no value:

```
config set crew.cordelia.tags.role/pilot
config unset crew.cordelia.tags.role/pilot
```

To remove a value entirely, unset it:

```
config unset crew.cordelia.soul
```

`config unset` deletes the key from whichever file defines it, and
deletes an entity file outright if unsetting empties it.

**Refusal.** A write that would leave the config invalid (a required
field still missing, a value of the wrong type) is refused — nothing is
written, and the result explains why. It's never applied halfway. The
CLI's `isaac config set`/`unset` has a `--force` escape hatch for an
operator to write anyway and surface what's left wrong as warnings;
`handbook__configure` has no such override — from inside a turn, a
refused write stays refused.

That matters most for fields that are only valid **together** — two
required fields on the same entity, say, or a grant on one entity plus
a companion entry on another. Setting one at a time would always be
refused on the first call (the second one's still missing, or doesn't
exist yet). `isaac config set`'s stdin-map form
(`echo '{:client-id "…" :client-secret "…"}' | isaac config set
google.oauth -`) gives that atomicity for several fields under one
shared entity. `handbook__configure` covers the broader case — several
path/value pairs spanning **different** entities or top-level keys in
one call, applied atomically: either every pair lands, or none does,
and an invalid combination is refused whole with nothing written. Set
related fields in one call rather than one at a time when they depend
on each other.

**Where a write lands.** Placement follows two rules. An **existing**
entry is written where it already lives (a split-out `<key>.edn`, an
entity file, or a markdown companion) — an entity file never becomes
inline, and an inline entry never becomes a file. A **new** entry gets
its own entity file when the config sets `:prefer-entity-files true`,
otherwise it lands inline in `isaac.edn`.

**Confirmation.** A successful set or unset reports what it did and
where: `set crew.cordelia.model = "quantum-anvil" (crew/cordelia.edn)`.
Read that line back — it's your confirmation the write landed, and it
names the exact path to `config get` if you want to double-check later.

### Schemas

Every config field a module declares carries: a **type** (`:string`,
`:int`, `:boolean`, `:keyword`, `:map`, a set, …), whether it's
**required**, a **default** (see Effective vs. written, below), the
**options** it accepts when the field is a registered choice (a comm's
`:type`, for instance, lists every comm kind actually installed), and a
human **description**. From inside a turn, read all of that plus the
field's **current value** through a `config:<dotted.path>` reference
topic on `handbook__read` (`handbook__read` with topic
`config:logging.level`, for instance) — that's the crew-facing
route. `isaac config schema <path>` is the CLI equivalent, for an
operator at a terminal.

### Effective vs. written

The config a running Isaac actually uses is **conformed-over-raw**: the
raw, merged files, with schema defaults filled in for absent keys and
declared coercions applied — and unknown keys are kept, not dropped
(they still produce a warning). This is the view every tool call and
`config get` (no `--raw`) sees.

- `config get <path>` on a key that's absent, but has a schema default,
  returns that default, marked `(default)` in the plain-text output.
  Once you (or an operator) set the key explicitly, the mark goes away
  and the value shown is exactly what's set.
- `config get <path> --edn` or `--json` never adds `(default)` — it's
  plain data either way, defaulted or not, because a program reading
  structured output can't be expected to strip an annotation.
- `config get <path> --raw` shows only what's actually written to
  disk — a defaulted, never-set key is simply absent there (the command
  reports "not found"). `--raw` is how you check whether a value is
  genuinely set versus just defaulted.
- `config set` / `config unset` always act on the raw files, never on a
  defaulted value — you can't "unset" a value that was never written in
  the first place.

### Secrets and `${VAR}`

Never write a real secret into a config file. Instead, write a
`${VAR}` reference and let the operator's environment (or the root's
`.env` file) supply it:

```
config set google.oauth.client-secret ${GOOGLE_CLIENT_SECRET}
```

Reading it back never shows the value: a resolved secret prints as
`<GOOGLE_CLIENT_SECRET:redacted>`, and one that can't be resolved at all
prints as `<GOOGLE_CLIENT_SECRET:UNRESOLVED>` — that second form is
itself a diagnostic: the variable isn't set anywhere Isaac can see. The
full value only ever appears via a CLI-only, human-confirmed `--reveal`
flag — there's no handbook path to it, by design.

### Templates

See Composition, above, for `:_base` — it's config's inheritance
mechanism and applies to any map entity in any table, not just one
kind.

### Validation

A config load produces **errors** and **warnings**, and they mean
different things:

- An **error** — a required field missing, a value that fails its
  schema — blocks that load. On hot reload specifically, an error means
  the reload is rejected outright: Isaac logs `:config/reload-failed`
  (with the path and the reason) and **keeps the config already
  running**, unchanged. A turn never sees a broken config — it always
  sees the last config that loaded cleanly.
- A **warning** — an unknown key, an unresolved `${VAR}`, a stale
  handbook path, a named-override between two modules — never blocks
  anything. It's surfaced by `isaac config validate` as a plain line
  (`warning: :bogus-key - unknown key`), never as a structured log
  entry on a terminal.

### Hot reload

Foundation watches the entire `config/` tree (every `.edn` and `.md`
file it recognizes, at any depth) and reloads automatically on change —
this is the default, and it's what makes `handbook__configure` writes
take effect without a restart. It can be turned off with `config set
hot-reload false`; a value of `[:server :hot-reload]` still works but is
retired in favor of the top-level `hot-reload`.

### Troubleshooting

- **A `handbook__configure` write is refused** and you weren't
  expecting it: read the error — it names the field and what's wrong.
  If it's refusing because a *related* field is also required, set both
  in the same call rather than asking an operator to `--force` it — that
  escape hatch is CLI-only and leaves the config in a state you asked
  for but didn't actually satisfy.
- **A value won't go away.** Confirm you're unsetting the exact path
  that's set — `config get <path> --raw` shows whether it's really
  there, and where.
- **`(default)` shows up where you expected a real value**, or vice
  versa — this is the conformed-over-raw behavior above; use
  `--raw` to see the ground truth of what's written.
- **A secret shows `<VAR:UNRESOLVED>`.** The environment variable isn't
  set where Isaac can see it (process env or `<root>/.env`). This is an
  operator-side fix, not something `handbook__configure` can repair.
- **A change to config doesn't seem to take effect.** Check hot reload
  is on (see above) before assuming anything else is wrong. Then check
  the server log for `:config/reload-failed` — if your write made some
  *other* part of the config invalid, the whole reload was rejected and
  the config you changed is still running its old value, silently from
  your side.

## Modules and berths

A **module** is an installed unit of capability — foundation itself,
plus anything added under config `:modules` (a map of module id to a
git or local-path coordinate). Every module ships an `isaac-manifest.edn`
declaring its id, version, description, optional `:handbook` chapter
path, the **berths** it declares, and what it **contributes** to berths
other modules declare.

A **berth** is a named extension point — `:isaac/cli` for CLI commands,
`:isaac/log-stream` for log streams, a comm module's own berth for comm
kinds, and so on. One module declares a berth's shape; any module
(including the same one) can contribute entries to it.

Installing, removing, or upgrading a module is **CLI-only** — there is
no config-path way to add a module coordinate through
`handbook__configure` in the sense of resolving and pinning it (you
*could* technically write `:modules` directly, but resolving a name to a
coordinate, fetching it, and rebuilding the classpath needs
`isaac modules install`/`upgrade`, run by an operator):

```
isaac modules install marigold.longwave
isaac modules upgrade
isaac modules remove marigold.longwave
```

To inspect what's installed:

```
isaac modules list          # every installed module, with version-conflict warnings
isaac modules show marigold.longwave         # description, handbook path, berths, contributions
isaac modules show marigold.longwave --edn   # same, structured
```

**Conflicts** come in two shapes, resolved differently:

- **Structural** — two modules disagree on a berth's own shape, or a
  config table's shell. That's an **error**; it can't be resolved by
  ordering.
- **Named** — two modules contribute an entry under the *same name* to
  the same berth (two comms both named `longwave`, say). The module that
  comes later in the config's `:modules` order wins; this is a
  deliberate override, logged as a warning, not a failure.

A module's `:handbook` entry names a classpath-relative markdown file —
that's the chapter you're reading. A handbook path that doesn't resolve
is a warning (`isaac modules show <id>` still runs; the chapter is just
missing from `handbook__read`'s table of contents), never an error — a
broken doc path never stops the ship.

### Troubleshooting

- **A module id doesn't show up in `handbook__read`'s table of
  contents.** Either it isn't installed (`isaac modules list`), or its
  manifest has no `:handbook` entry, or the entry's path doesn't resolve
  (check `isaac modules show <id>` for a `Handbook:` warning).
  Foundation's own chapter always comes first in the table of contents;
  every other installed module follows, sorted by module id.
- **Two modules seem to fight over one setting.** Check whether it's a
  named collision (last-in-`:modules`-order wins, logged as a warning —
  reorder `:modules` to change the winner) or a structural one (a real
  conflict that needs a module fix, not a config change).
- **A module you expect isn't loading at all.** That's most likely a
  version conflict or a missing dependency — `isaac modules list` prints
  those as a conflict/drift table. Resolving it (bumping a pin,
  reinstalling) is CLI-only.

## Scheduler

The scheduler is machinery other modules build on to run recurring or
delayed work inside the server process — it is not, by itself, something
you configure directly through `handbook__configure`. A module registers
a **task**: a stable id, a **trigger**, a handler function, and optional
**policies**.

A scheduled job you'd actually want to change is config, owned by
whichever module surfaces it — the cron module, for instance, declares
a `:cron` table where each job is an entity under `config/cron/<id>.md`:
frontmatter for `:crew`, `:expr` (the cron expression), and the rest,
with the job's `:prompt` as required companion prose in the markdown
body below the frontmatter. You edit a cron job the same way as any
other entity — `handbook__configure`, including its companion prose
field — and its own chapter covers the field list.

**Triggers**:

| Kind | Fires |
|---|---|
| `:interval` | every N milliseconds |
| `:delay` | once, N milliseconds from registration |
| `:at` | once, at an absolute instant (a past instant fires on the next tick) |
| `:cron` | on a cron expression, evaluated in a given time zone |

**Policies** (all optional):

- `:coalesce` — `:skip` drops a fire that would overlap a still-running
  one; `:queue` runs overlapping fires one after another instead.
- `:on-error` — `:log` (default) logs a handler failure and keeps
  scheduling; `:retry` backs off exponentially and disables the task
  after too many consecutive failures.
- `:timeout-ms` — interrupts a handler that runs longer than this.

Tasks have stable ids; re-registering an id that's already scheduled is
an error (cancel it first, deliberately, rather than silently replacing
it).

### Troubleshooting

- **Something that should run on a schedule doesn't seem to.** The
  scheduler only runs inside the server process — a plain CLI command
  never fires a task. Confirm the server is up.
- **A scheduled thing stopped running entirely.** If its policy is
  `:on-error :retry`, enough consecutive failures disable it outright
  (logged as `:scheduler/disabled`, reason `:too-many-errors`) — it has
  to be re-registered, not just waited out.
- If a task lives behind a specific module's own config (a cron job, for
  instance), troubleshoot it in that module's chapter — this section is
  the shared mechanism, not any one module's schedule.

## Logs

Isaac writes structured logs (EDN lines: `:ts`, `:level`, `:event`, plus
whatever the log call adds) to named **streams**. Modules declare a
stream via the `:isaac/log-stream` berth; foundation itself declares
`cli` (`logs/cli.log`) and `server` (`logs/server.log`). A stream is
listable because a module declared it — whether or not its file exists
yet.

To view a stream: `isaac logs` (no name) lists every registered stream;
`isaac logs <name>` tails it, one colorized line per entry, most recent
last, capped at the last 20 by default (`--limit N`, `--limit 0` for
everything, `-f`/`--follow` to keep watching).

Levels, from quietest to most verbose: `report`, `error`, `warn`,
`info`, `debug` (a `trace` level exists on log entries but is more
verbose than `debug` and isn't a settable output level). Set the process
log level with `config set logging.level warn`; set where logs go with
`config set logging.output stderr` (`:file` is the default; other
options are `:stdout` and `:none`).

**A CLI command never prints a structured log line to its own
terminal**, no matter what config warnings fire during that command's
own config load — logs always go to `logs/cli.log` unless an operator
explicitly opts in with `--log-file`/`--log-level` (CLI flags, not
config). `isaac config validate` is the one place a config-load warning
appears as plain text in command output, deliberately, not as a log
line.

### Troubleshooting

- **You expect a log entry and don't see it on a terminal.** That's
  correct default behavior, not a bug — check the `cli` or `server`
  stream file instead (`isaac logs cli` / `isaac logs server`).
- **A stream you expect isn't listed.** Its module may not be
  installed, or the module hasn't declared that stream — `isaac logs`
  with no name shows every stream currently registered.
- **An unknown stream name.** `isaac logs <typo>` reports the name as
  unknown and lists what is available, rather than failing silently.

## Appendix: the CLI and remote routing

Everything above assumes a request landing directly on this instance.
Isaac's CLI can instead be **remote by default**: if the operator's home
pointer file (the same file that can set `:root`) names a `:cli :remote`
target and the remote-CLI module is installed, an ordinary `isaac`
invocation ships the command to that server over the network instead of
running locally — a same-machine server is just the case where the
remote happens to be localhost. This decision is made before any config
is even loaded, from the pointer file alone.

A few commands are always **local-only** regardless of a remote
setting — `server`, `service`, `modules`, and `remote` itself, since a
down server has to be startable locally even when remote routing is
configured. An operator can force a single invocation local with
`--local`, or every invocation in a script with `ISAAC_CLI_LOCAL=1`.

None of this is reachable through `handbook__configure` from inside a
turn — it governs how a *human's* CLI invocation gets routed, before any
crew or tool is involved. It's documented here because `modules show`,
`config schema`, and similar introspection commands an operator runs to
help you are themselves subject to it.

### Troubleshooting

- **An operator reports a CLI command "hangs" or times out.** If remote
  routing is configured, check the remote server is actually reachable;
  an unreachable remote fails fast with the URL and reason in the error
  rather than silently falling back to local.
- **A command an operator expects to run remotely runs locally instead
  (or vice versa).** Check for `--local` / `ISAAC_CLI_LOCAL=1` first,
  then confirm the command isn't one of the always-local-only ones
  listed above.
