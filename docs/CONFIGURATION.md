# Configuration and project state

Arrodes keeps user and project state outside working repositories. This guide covers application home selection, settings precedence, project resources, trust, and current-format storage.

## Application home

The application home is selected in this order:

1. `--home PATH`
2. `ARRODES_HOME`
3. `~/.arrodes`

The complete tree moves together when you select another home:

```text
HOME/
  config/
    settings.edn       global settings
    tui.edn            saved TUI theme preference
    keybindings.edn    optional backend keybinding map; not TUI remapping
    trust.edn          exact-project trust decisions
  auth/                private provider credentials
  skills/              global skills
  extensions/          global extensions
  prompts/             global prompts
  themes/              data-only TUI theme packs (see THEMES.md)
  packages/            installed global packages and index
  projects/
    <readable-name>-<sha256(real-worktree-root)>/
      project.edn      canonical identity and timestamps
      settings.edn     project settings
      keybindings.edn  optional backend keybinding map
      data/            sessions.sqlite and retained artifacts
      skills/ extensions/ prompts/ themes/ packages/
  cache/
    models/ jna/ bun/ opentui/
  runtime/
    <version>-<platform>-<arch>-<payload-digest>/
```

The packaged TUI uses the fixed keys documented in the README and does not consume `keybindings.edn` for remapping. The file remains part of backend resource state for other hosts or extensions; do not edit it expecting TUI shortcuts to change.

## Project identity and discovery

A Git project is keyed by the canonical real path of its worktree root. Launches from a subdirectory or symlink alias share the same external state. Separate worktrees have separate state. Outside Git, the canonical launch directory is the project root.

Arrodes creates no project dotfolder. After initializing a connection with `arrodes --rpc` as described in the [RPC reference](PROTOCOL.md), request the exact state directory:

```json
{"type":"request","id":"project","method":"project.info","params":{}}
```

The result includes `id`, `root`, `directory`, and current trust information. Embedded Clojure callers can use:

```clojure
(require '[arrodes.platform :as platform])
(platform/project-info home cwd)
```

Discovery itself does not create files.

## Settings files and precedence

Settings files contain one EDN map.

- Global: `HOME/config/settings.edn`
- Project: `PROJECT_STATE/settings.edn`

Global settings are applied first. Settings from the trusted project are deep-merged over them. Explicit launch/session configuration wins over file settings. `nil` removes a key when applying an update.

A concise global configuration can look like this:

```clojure
{:provider :codex-backend
 :model "MODEL_ID_RETURNED_BY_SETUP"
 :thinking :high
 :tools :all
 :instructions "Prefer small, reviewable changes and verify the changed behavior."
 :provider-retries 2
 :fallback-model? false}
```

Use `/providers` to manage connections and discover models. In `/models`, choose a reasoning level and then **Make default for new conversations** to save global defaults and apply them to the current conversation, or **Use in this conversation** to change only the current session. Other saved sessions retain their configuration; directly editing settings files does not retroactively reconfigure sessions.

Supported session-default fields are:

- `:provider` — provider keyword;
- `:model` — exact model ID;
- `:thinking` — `:none`, `:minimal`, `:low`, `:medium`, `:high`, `:xhigh`, or `:max`, subject to the model;
- `:tools` — `:all` or a vector of registered function names;
- `:instructions` — text or a readable file path;
- generation fields such as `:temperature`, `:top-p`, `:max-output-tokens`, `:stop`, `:response-format`, `:cache`, `:auto-compact?`, compaction limits, `:max-steps`, `:provider-retries`, `:fallback-model?`, and `:provider-options`.

`provider-retries` is bounded to five retries at runtime. A false `:fallback-model?` prevents silent model substitution.

### Summary-tree context (opt-in)

Absent `:context-policy` or explicit `:linear` keeps ordinary linear context.
Select **Summary tree** in [`/context`](TUI.md#context-policy-and-inspection), or
set these fields at the top level of a global/trusted-project settings file.
For an explicit session configuration, put them inside `:config :settings`
(`config.settings` over [RPC](PROTOCOL.md#session-context)).

| Setting | Default | Accepted values / bounds |
| --- | --- | --- |
| `:context-policy` | `:linear` | `:linear` or `:summary-tree`; string equivalents accepted |
| `:summary-provider` | Session provider | Provider keyword or string ID |
| `:summary-model` | `"gpt-6-luna"` | Nonblank exact model ID; no substitution |
| `:summary-node-bytes` | `512` | Integer 1–16,777,216; UTF-8 summary target per node |
| `:summary-view-bytes` | `128000` | Integer 1–16,777,216; actual rendered historical-view UTF-8 byte budget, including navigation markup |
| `:summary-max-attempts` | `3` | Integer 1–10; bounded compression attempts per generated node |
| `:summary-timeout-ms` | `60000` | Integer 1–3,600,000; work deadline for each summary node, not the whole history backlog |

For example, explicitly choose the main and summary models independently:

```clojure
{:provider :codex-backend
 :model "gpt-6.1-sol"
 :thinking :high
 :context-policy :summary-tree
 :summary-provider :codex-backend
 :summary-model "gpt-6-luna"
 :summary-node-bytes 512
 :summary-view-bytes 128000
 :summary-max-attempts 3
 :summary-timeout-ms 60000}
```

This is an opt-in example, not a change to the main-model default. Both exact models
must be available to the account. Authenticate through `/providers` and the normal
provider auth store; no credentials belong in these settings or summary records.
Changing `:summary-model` does not change the main model or enable the tree policy.

The node size is a target, not permission to truncate evidence: after the bounded
attempts, the shortest complete nonempty summary may exceed it. Empty or truncated
completions are not stored as fake summaries. The rendered view budget remains a
bound; a budget too small to represent the required history fails explicitly.
Bytes are not tokens or a guarantee that the whole main request fits a model window.

Normal foreground preparation waits cancellably for its complete, fitting
summary-only view; there is no default whole-backlog deadline. Healthy catch-up may
take longer than one node's timeout. Embedded `summaries/ensure-ready!` callers may
still supply an explicit `:timeout-ms` wait bound. Genuine node failures remain
visible; increasing a node timeout is not a substitute for resolving them.

Enabling allows separate, tool-free model work before a main request and background
catch-up after relevant committed work. Opening/reopening a session, inspecting
context, or importing history never generates summaries. Configuration alone does
not immediately build the tree. Completed derived nodes are reusable; failures are
tracked by node and settings, background maintenance does not repeatedly retry a
failed node, and later explicit foreground demand can retry required work.
Changing prompts or settings does not rewrite previously completed cache nodes.

Saved-session changes take effect at a safe outer-turn boundary, not halfway through
native REPL/tool replay. The configured policy may therefore differ from the active
request's policy or budget. A run override and delivered steering/follow-up settings
are explicit intents; later per-key configuration wins even when the same value is
explicitly set again. See [RPC](PROTOCOL.md#session-context) for application and
inspection, [Providers](PROVIDERS.md#summary-model-work) for accounting/cache behavior,
and [Sessions](SESSIONS.md) for canonical history and original-record retrieval.


Provider transports default to `:connect-timeout-ms 15000` and `:timeout-ms 60000`
(stream read-idle timeout). Configure these at runtime/global settings scope, or
under `:provider-options` keyed by provider ID for provider-specific overrides.
Long silent reasoning requests may require a larger read-idle timeout. These
deadlines do not authorize retries after visible output or replay of REPL effects.

Session-agent runtime limits are configured under runtime `:settings`: `:operation-limit`
(default 32, 1–128 admitted foreground operations), `:agent-limit` (default 32,
range 1–128 team members), and `:agent-max-depth` (default 4, range 1–16
delegation levels).
Operation admission counts running and waiting operations; it is independent of
`:job-limit` for background functions.
`operation-threads` is not a compatibility spelling for `operation-limit`.
When launched, a child inherits a selected snapshot of its parent's session
configuration unless its `agents/start!`/`agent.start` options override it. Context
policy and summary settings are not implicitly inherited: a child must opt in
explicitly. Future parent model changes do not mutate child configuration.
Provider credentials remain in the usual provider credential store, not in a child
launch record; a child loads project resources and trust through its own
session/evaluator lifecycle.

Automatic session naming is enabled by default. Set `:auto-title? false` to disable
both the initial local name and background title generation. `:title-model` selects
the exact model ID for naming; `:title-provider` optionally selects another
configured provider. Without overrides, naming uses the session's provider/model
in a separate tool-free request with a 1,024-output-token cap and no requested
reasoning. Only an excerpt of the first message is sent. These settings are also
accepted under a session configuration's `:settings` map.

Web research has independent defaults under `:web`; it never changes the coding
provider/model:

```clojure
{:web {:provider :codex-backend
       :model "EXACT_ACCOUNT_VISIBLE_SEARCH_MODEL"
       :limit 5
       :timeout-ms 30000
       :max-tokens 2048}}
```

Omit `:provider`/`:model` to use the session's exact pair. Per-session
`:config :settings :web` overrides resource settings, and function arguments
override both. A provider change does not inherit another provider's search model.
`:backend :mcp`, `:server`, `:search-tool` and `:read-tool` configure an explicit
MCP alternative; `web-read` independently defaults to HTTP unless
`:read-backend :mcp` is selected. The MCP server's own timeout applies.
See [web research](PROVIDERS.md#web-research) for supported providers, limits,
native return fields and examples. Search credentials stay in the existing
provider/MCP credential sources, never in this settings map.

The older nested maps `:session-defaults`, `:session`, and `:session-config` are still read when opening existing configuration. Setup writes canonical top-level defaults and removes overlapping values from those nested maps.

### Updating settings over RPC

Read the effective, redacted map:

```json
{"type":"request","id":"settings","method":"settings.get","params":{}}
```

Patch global or project settings:

```json
{"type":"request","id":"update","method":"settings.update","params":{"scope":"global","changes":{"provider-retries":2,"fallback-model?":false}}}
```

Use `null` to remove a key. Project updates require project trust. Writes are atomic, and a failed resource reload restores the previous settings file.

Settings reject credential-like values. Put provider credentials in `HOME/auth/`; refer to external secrets in MCP configuration with `${ENVIRONMENT_VARIABLE}`.

## Resource paths

Arrodes discovers these resource groups:

- `:skills`
- `:extensions`
- `:prompts`
- `:themes`

Default directories are the matching folders under `HOME/` and the project state directory. Add explicit locations with vectors in settings:

```clojure
{:skills ["shared-skills" {:path "/opt/team/skills"}]
 :extensions [{:path "extensions/optional.clj" :enabled? false}]
 :prompts ["prompts"]
 :themes ["themes"]}
```

A string is shorthand for `{:path "..."}`. `{:enabled? false}` excludes matching resources and can override a lower-precedence discovery entry. Global relative paths resolve from `HOME`; project relative paths resolve from the external project state directory. Instruction file paths resolve from the session working directory.

Project packages and resources are ignored until the project is trusted. See [Extensions and resources](EXTENSIONS.md) for formats and APIs.

## Trust

Trust is recorded for the exact canonical project root in `HOME/config/trust.edn`. It permits project settings, context files, packages, extensions, and other project resources to load. It is not inherited from an arbitrary ancestor and is not a sandbox.

Read project and trust state with `project.info`. Persist a decision with:

```json
{"type":"request","id":"trust","method":"project.trust","params":{"trusted?":true}}
```

A trust change applies on the next session resource load. Start a new session or use `/reload` (`session.reload` over RPC) to reset the evaluator and activate the new resource set. Reloading discards live definitions and live-only values, but keeps durable history.

The launch flags `--trust` and `--no-trust` provide an explicit decision for that run.

Project context may include `AGENTS.override.md`, `AGENTS.md`, `AGENTS.MD`, `CLAUDE.md`, or `CLAUDE.MD` between the project root and session working directory. Arrodes reads them only when the project is trusted and never writes them.

## Provider setup semantics

`setup.status` is observational: it never prompts, authenticates, or changes configuration. Reading public SDK model metadata may populate `HOME/cache/models`; this is not provider sign-in or live provider model discovery.

`setup.run` is the mutating setup method. It may authenticate or reuse credentials, perform actual model discovery for the selected provider, save global defaults, and record a trust decision. Cancelling stops the active setup request, but completed authentication or writes are not rolled back. Inspect `setup.status`, then continue with `/setup` rather than guessing whether an effect occurred.

Anthropic uses Console API-key or supported cloud-provider credentials, not Claude.ai subscription OAuth. Copilot's previous first-party login is not offered. Codex ChatGPT sign-in remains supported. Provider compatibility does not imply affiliation or endorsement, and the provider's account terms apply.

## Current home layout

Only the current layout is supported. Settings, keybindings, and trust files belong
under `HOME/config/`. Root-level copies are rejected with `unsupported-home-layout`;
Arrodes never moves, merges, or overwrites them. Choose a current-format application
home explicitly when necessary.

An implicit `HOME/data` directory is not selected as project history. An explicit
`--data-dir PATH` selects one data directory. The current store schema is 6;
recognized schema-3/4/5 layouts upgrade after a retained SQLite backup. Artifact
content and credentials/settings are preserved. Unsupported, malformed, newer
and foreign stores are rejected intact.
The [format contract](COMPATIBILITY.md) defines supported layouts, backup names
and explicit recovery; startup never resets an incompatible store.
