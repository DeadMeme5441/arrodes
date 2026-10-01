# Architecture

## One turn

The TUI sends a command over JSONL RPC. The command host starts a durable runtime
operation. The runtime projects active history and resources into a provider request.
The model can reply or call `repl`; evaluations run in response order. Registered
functions execute under attributed validation, hooks, cancellation and effect locks.
Results and activity are recorded, and the next provider request can reference native
results. The UI combines a consistent snapshot, durable events and transient output.

## Ownership

| Owner | Owns |
| --- | --- |
| Runtime | Store, admitted operation workers, session coordination, session handles and foreground-operation admission |
| Agent service | Session-backed delegation, addressed message wake policy and managed waiting; uses the runtime/store, not a second provider loop |
| Session handle | Evaluator namespace, function registry, provider manager and resource activation |
| Job service | Session-owned function workers, independent cancellation/output, child *function-job* cleanup, durable outcomes |
| Operation | Cancellation, worker lifetime, usage and queued-input delivery boundaries |
| Registry | Evaluation lock, native results, function wrappers and owned closeable resources |
| Resource activation | Attributed extension contributions, cleanup and lazy MCP clients |
| TUI controller | RPC process/connection, navigation generation, drafts and view reconciliation |
| TUI renderer | Focus, selection, scrolling, visual components and bounded inspection |

The evaluator is live execution state. The session is durable conversation/configuration
state. A model change or compaction must not quietly replace the evaluator.

Each agent is a session with its own persistent evaluator and initial operation.
`agents` is installed next to `jobs` in the REPL registry; the provider still exposes
only `repl`. Accepted child operations each have a schedulable worker within a
bounded admission limit, so a parent waiting in Clojure cannot strand its child
behind its own worker. Peer messages and terminal child outcomes are committed
durably, delivered at safe provider/tool/turn boundaries, and acknowledged with
their history entry. Delivery does not consume user queue items or job outcomes.
The root/team roster and `:agent/changed`/`:agent/message` summaries are projections
of those records, not an independent lifecycle owner.

### Backend modules

`arrodes.store` owns session/history commits, configuration, branching, queues,
operations and event reads. Its `store/` modules separate database lifecycle and
transactions (`db`), filesystem ownership (`files`), schema validation/upgrades
(`schema`), durable encoding and SQL (`codec`, `sql`), shared record/routing
primitives (`records`, `routing`), and jobs, agents, transfers and recovery.
Call those owners directly; the root namespace does not re-export moved APIs.

`arrodes.runtime` composes the runtime and owns public session/operation orchestration
and shutdown. Its `runtime/` modules own locks/events/admission (`control`),
evaluator/resource lifecycle (`handles`), operation execution/settlement (`operations`),
input and generation preparation (`preparation`), and provider/REPL continuation,
compaction and context recovery (`model`). These dependencies are static and acyclic.

Internal transfer envelopes use qualified keywords and `clojure.spec.alpha`.
`store/commit!` accepts only qualified fields such as `::command/entries` and
`::command/session` from `arrodes.store.command`, and validates the envelope before
starting a transaction; unknown keys are errors. Prepared model runs use fields
such as `::preparation/config` and are validated at model entry.
Specs check these envelopes, not arbitrary native evaluator values. Nested durable
records, public return maps, SQLite schema 5, RPC 1 and export format 1 are unchanged.

`web.clj` registers native research functions through resource activation.
`web/hosted` owns search-only native provider tools and source normalization;
the provider manager retains credential/profile ownership. `web/reader` owns
bounded inert HTTP extraction, and `web/data` owns qualified result specs.
Hosted work joins before returning, while the existing MCP pool owns explicit
remote research calls. The existing result/artifact layer retains available
research values; no new search cache, agent loop or persistence format is added.

## TUI modules

`tui-app` composes the controller lifecycle and dispatches actions. Its `tui/controller/`
modules own sessions, submission/queue receipts, provider catalogs, attachments, and
transport helpers. `tui-view` composes the renderer tree and lifecycle; `tui/` feature
modules own input/focus, screens, model controls, transcript/scrolling, inspection, and
chrome. Shared context helpers sit below these components. Navigation callbacks are
injected by the view root where peer imports would create a dependency cycle.

Theme resolution is pure CLJC. Pack discovery and preferences are local frontend data;
per-renderer paint bindings apply semantic roles to existing nodes. Preview does not
remount editors, recreate the controller, or change session/runtime state. Built-in EDN
packs are embedded when the TUI compiles. See [theme packs](THEMES.md).

## Durable versus transient

SQLite transactions commit canonical entries, session changes, queue changes,
operations and events. Artifacts store larger immutable content. Result descriptors
honestly distinguish inline, artifact-backed, live-only and unavailable values.
Streaming text/progress is transient; it cannot become a competing durable history.

Startup takes exclusive database/artifact ownership and validates before mutation.
Supported schema-3/4 stores receive a consistent retained SQLite backup and a
transactional upgrade to schema 5. Current stores reopen directly. Unsupported,
newer, foreign, malformed or unsafe stores fail intact; legacy reset markers never
resume deletion. Recovery does not replay effects or change credentials/settings.
See the [format contract](COMPATIBILITY.md) for supported layouts and recovery.

`session.view` provides an atomic snapshot and event cursor. UI hydration buffers events,
replays activity through the cursor, then applies later events without duplicating entries
or resurrecting delivered queue items. An unknown mutation outcome requires reconciliation.

## Boundaries to test

Use pure CLJC tests for projections; runtime/store tests for ownership and transactions;
RPC process tests for framing, cancellation and reverse host requests; native OpenTUI
checks for actual rendered behavior. Distributable executables are built at release time;
try the resulting candidate before publishing it.

## Background work

`jobs.clj` owns admitted function jobs independently of foreground operation admission.
The job table stores identity, origin, status and retained result/output references;
worker threads, functions and native objects stay live. Each job receives a fresh
invocation context and keeps the registry that launched it. Durable changes/events
publish under the session boundary so hydration cursors remain consistent. Evaluator
replacement gates foreground admission, blocks new jobs, and waits outside the session
lock for job cleanup; incomplete cleanup preserves the old registry and store.

Model-step boundaries append completion context and acknowledge those records in one
transaction. These entries use structured retained descriptors so fork/import can remap
result IDs. UI inspection does not consume model notifications. Idle jobs notify the
UI without starting a model call; **only eligible agent messages and completions**
can trigger the separate policy-controlled agent wake path.
