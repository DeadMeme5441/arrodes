# Changelog

## Unreleased

## 0.1.8

- Reduce standalone executable weight by writing actual gzip payload bytes and
  excluding SQLite/JNA native libraries for other OS/CPU targets. Keep all Java
  modules, runtime capabilities, parser assets and license material.
- Add packaged `arrodes update`, `--check` and explicit `--version` selection:
  verify published release metadata/checksums, stream to private staging and replace
  the owned executable atomically without touching application data.
- Replace destructive incompatible-store reset with preservation-first upgrades.
  Supported schema-3/4 layouts receive private WAL-inclusive backups before
  transactional migration; unsupported/newer/foreign/malformed stores fail intact,
  and interrupted legacy reset markers never resume deletion.
- Validate known model capabilities before transport, bound connect/read-idle waits,
  reject incomplete or malformed streamed tool responses, and classify actionable
  provider failures while preserving runtime-owned safe retries and partial outcomes.
- Preserve the conversation cache prefix across evaluator replacement using
  append-only reset notices; isolate summary cache scopes and defer automatic
  compaction until another provider request needs context.
- Add `/usage` and a clickable context footer for measured cache-read/write,
  uncached input, output and active-path estimated spend, with explicit unknowns
  and partial totals rather than fabricated zeros.

- Update the optional packaged-release diagnostic to follow reconciled provider
  state, inline model settings and first-send session creation instead of obsolete
  dialogs and transient notification wording.

## 0.1.7

**Upgrade notice:** this release requires SQLite schema 5. Startup resets recognized
incompatible Arrodes stores and their owned artifacts, losing their sessions/history.
Export needed history with the previous compatible build before upgrading. Settings,
credentials, foreign databases and unrelated files are preserved.

- Add on-demand `help` recipes for background work, delegation, partial failures and
  retained results; teach receipt reconciliation, deliberate observation and final
  answers without duplicate peer reports.
- Add inert, context-sensitive `:next` navigation to individual job, agent, delivery
  and retained-result inspections. Preserve session/operation identity and distinguish
  value retrieval from job-outcome acknowledgement.
- Add reusable session/artifact-scoped continuation cursors to `artifact-page` and
  RPC `artifact.read`, including after restart, without changing explicit offsets or
  durable formats.
- Unify REPL discovery under bounded, paged `help` for coding functions, jobs,
  agents and value/workspace helpers. Keep full contracts explicit and preserve
  last-expression evaluation semantics.
- Default native agent inspection to compact paged data and bounded answer/error
  previews; retain full native detail on explicit request instead of injecting
  provider/configuration payloads into ordinary model context.
- Link accepted message receipts atomically to the operations that incorporate
  them; add `agents/delivery`, `agent.delivery` and receipt-aware managed waiting.

- Add session-backed agents with independent persistent REPLs, durable addressed
  messages and completion notices, managed waiting, operation-scoped results, and
  explicit pause/resume and tree stop. Parent cancellation leaves accepted jobs and
  child sessions running; routing resumes only on explicit user action after a stop.
- Add `agent.*` RPC methods and a terminal `/agents` browser for team navigation,
  messaging, results and stop/resume, with off-context indicators and honest
  measured-token aggregates. Preserve the single-`repl` provider action and
  session-owned jobs.
- Use bounded schedulable operation admission and ordered durable event publication
  for concurrent parent/child execution; preserve safe input boundaries and native
  evaluator values.
- Preserve accepted messages across tree stop and retry eligible wakes when capacity
  is released by another team. Nested jobs stopping their own tree report stopping
  without waiting on their own job ancestors.
- Reject new jobs, agents, messages and resume actions from already-cancelled
  invocations, without cancelling previously accepted independent background work.
- Switch to SQLite schema 5. Startup automatically resets exclusively
  owned incompatible **recognized Arrodes** session stores and their owned
  artifacts; old sessions/history/results are lost without migration.
  Foreign SQLite, credentials, settings and unrelated files remain intact;
  RPC framing version 1 and session export format 1 remain.

## 0.1.5

- Add session-owned background Clojure jobs with independent cancellation/output, owned child cleanup, retained native results, paged logs, and once-only completion delivery at model boundaries. Restart records interrupted work without replaying effects.
- Render jobs inline with existing execution artifacts, use the full-terminal `/jobs` browser and existing inspector, and add `job.*` RPC controls. Jobs survive foreground turns; evaluator teardown cancels and awaits them.
- Return compact REPL job statuses by default, with `:detailed? true` for full provenance and diagnostics; native job result values remain unchanged.
- Classify requested job cancellation consistently as cancelled and retain the original interruption as a diagnostic cause.
- Add independent, reusable job-output cursors and retained-tail reads, including after restart. The existing inspector supports End/Latest and preserves its page or tail on refresh.
- Resume automatic following when scrolling back to the bottom during a streamed reply, including when new text arrives before the next frame.
- Require current SQLite schema 3 and current home layout. Fresh stores initialize directly; incompatible stores/layouts are rejected without migration, conversion, or deletion. Remove older job-record fallbacks. Job ownership is not copied by fork/import.

## 0.1.4

- Show compaction progress separately from assistant replies and recover once from explicit context-limit rejections without replaying completed REPL effects.
- Name new conversations from the first message, then refine the title through an owned background model call; preserve manual names and keep title usage separate from conversation context.
- Stabilize the transcript viewport during streaming, remove competing post-paint follow corrections, and add blank rows below the session title and above the composer.
- Fix the first `/sessions` invocation during startup being dismissed by empty-chat initialization; preserve early input and show session-list loading, empty and error states.
- Improve REPL function discovery with return contracts and focused help; return structured matches/listings with reusable paths and completeness; expose workspace docstrings, retained failure details and paged results/artifacts. Search/list and skill/prompt catalog shapes replace the former vectors without a legacy mode; existing stored results are unchanged.
- Preserve MCP schemas and structured content in discovery/results, attach server/tool provenance, and identify uncertain remote-call outcomes. Render native string evaluations as readable text.
- Use the latest completed provider call's reported usage for session context and automatic compaction. Include cached and cache-write input, honor totals without double-counting breakdowns, remove character-based estimates, and invalidate stale measurements after compaction. Existing session records and provider replay state remain compatible and unchanged.
- Keep internal compaction and branch-summary streams out of assistant replies, including partial summaries from failed or cancelled calls.

## 0.1.3

- Add data-only theme packs with semantic colors/text treatments, live preview/cancel, and a saved UI preference; include the silver/gold Arrodes theme and Dracula.
- Refactor the TUI into feature modules for screens, model controls, input, transcript, inspection, chrome, and controller responsibilities.
- Add theme-colored turn dividers, outlined execution artifacts, syntax-colored code boundaries, and bordered tables while keeping prose open and full width.
- Start the assistant turn before its first reasoning/tool activity so activity and final prose remain under the same speaker.
- Show each session's last recorded message timestamp in local time, independently of configuration changes.

## 0.1.2

- Simplify the header to the session name and distinguish user turns, assistant prose, code, and execution with spacing and restrained surfaces.
- Make reasoning and execution headings clickable to expand or collapse their blocks.
- Resume following when wheel scrolling reaches the transcript bottom and only show Jump to latest while content remains below.
- Move compact model/provider, project/branch, and reported context metadata below the composer; expire routine confirmations without an alert toolbar.
- Allow left/right arrows to cross model-browser columns at search and effort boundaries.
- Put effort and apply controls directly in the model listing screen, with responsive placement and consistent arrow/Tab navigation.
- Open an empty composer on launch and /new; create a session only on Send, and resume saved sessions only by explicit selection.
- Apply a default model to the current session too, including reasoning; validate both scopes before saving.
- Load models for the selected provider with cached results, provider-specific discovery, and inline connection/error status.
- Add a welcome screen with recent sessions, a bottom-anchored composer, and separated model/project metadata.
- Show searchable slash commands expanding upward above the composer and restore typing immediately after mouse reading or clicking the editor.
- Use full-screen navigation and inspection views instead of floating popups, combine reasoning and model application controls, and consolidate provider command aliases.
- Add a provider-management browser, searchable model selection and explicit conversation/default settings.
- Refine the terminal conversation with a yellow/grey-on-black theme and expandable source/output.
- Add a maintained documentation map, agent workflows, and local candidate verification commands.
- Simplify CI to one Linux core/TUI test job per PR. Build each release target once, stage a draft, and publish the same artifacts.

## 0.1.1

Initial public release for macOS and glibc Linux on arm64 and x64, with a persistent
Clojure evaluator, durable sessions, terminal/RPC interfaces, supported authentication,
and checksum-verified installation.
