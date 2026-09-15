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
| Runtime | Store, executor, session handles and foreground-operation admission |
| Session handle | Evaluator namespace, function registry, provider manager and resource activation |
| Operation | Cancellation, worker lifetime, usage and queued-input delivery boundaries |
| Registry | Evaluation lock, native results, function wrappers and owned closeable resources |
| Resource activation | Attributed extension contributions, cleanup and lazy MCP clients |
| TUI controller | RPC process/connection, navigation generation, drafts and view reconciliation |
| TUI renderer | Focus, selection, scrolling, visual components and bounded inspection |

The evaluator is live execution state. The session is durable conversation/configuration
state. A model change or compaction must not quietly replace the evaluator.

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

`session.view` provides an atomic snapshot and event cursor. UI hydration buffers events,
replays activity through the cursor, then applies later events without duplicating entries
or resurrecting delivered queue items. An unknown mutation outcome requires reconciliation.

## Boundaries to test

Use pure CLJC tests for projections; runtime/store tests for ownership and transactions;
RPC process tests for framing, cancellation and reverse host requests; native OpenTUI
checks for actual rendered behavior. Distributable executables are built at release time;
try the resulting candidate before publishing it.
