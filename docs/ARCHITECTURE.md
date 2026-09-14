# Architecture

## Boundary

Arrodes is a REPL-centred coding agent. Pi/OMP is a reference for interaction behaviour, not a requirement to reproduce its tool menu or implementation. The JVM owns execution; hosts observe it and submit commands.

```text
SDK                         RPC host ← OpenTUI host
 |                                  |
 +------------ runtime -------------+
                  |
          provider continuation
                  |
       session Clojure evaluation
          /       |        \
       coding   skills   MCP client → external MCP servers
                  |
        native values + retained results
                  |
        SQLite history + artifact files
```

Evaluation is a first-class session operation. It is not registered as a capability. `arrodes.provider-repl` alone encodes evaluation as the provider function named `repl`; arbitrary provider function names never dispatch to the registry. Provider-native call/result messages remain in durable conversation history for valid replay.

## Flat source layout

| Root | Responsibility |
| --- | --- |
| `src/clj/arrodes/` | JVM runtime, evaluator, capability functions, providers/auth, resource loading, MCP **client**, SQLite/artifact storage, platform effects |
| `src/cljc/arrodes/` | `session`: pure construction/history/context projection; `run`: agent decisions; `value`: portable values; `tui-model`: event-to-view projection |
| `src/cljs/arrodes/` | Bun/OpenTUI entry point, JSONL client, application controller, presentation and widgets |
| `hosts/rpc/arrodes/` | Optional command dispatcher, JSONL framing, reverse host requests, standalone RPC process |
| `hosts/cli/arrodes/` | Optional existing CLI, JLine input and terminal rendering |

There are no compatibility namespaces or duplicate module directories. The default classpath contains only the core roots and resources. `:host` adds RPC; `:run` adds RPC, CLI and JLine. Core interaction callbacks are injected; the runtime does not dynamically load a command host.

The portable session constructor consumes supplied IDs, timestamps and canonical cwd. The JVM store prepares these values. No filesystem implementation is simulated in ClojureScript. Shared namespaces have been compiled and executed in Node.

## Evaluation and functions

Each live session has one namespace, one evaluation lock and REPL history (`*1`, `*2`, `*3`, `*e`). Forms run in order. Definitions take effect immediately; a later exception does not undo earlier definitions or external effects. A generation identifier distinguishes a new environment from its predecessor.

Ordinary `def` and `defn` require no registration. `register-tool!` is optional instrumentation and discovery metadata for a function Var, not permission to use a function in Clojure. `registered-tools` returns the selected function catalog, including each callable symbol and argument schema. `:tools` selects registered functions available through their wrappers; it does not select provider-visible tools or sandbox arbitrary Clojure.

Registered functions share argument validation, hooks, permission checks, effect locks, cancellation and result retention. They return native Clojure values. Evaluation serialization is separate from effect locking: joined Clojure futures can compose independent calls without waiting on their parent's evaluation lock. Unjoined futures and arbitrary background threads are trusted user code, not supervised session operations; join work before returning.

Skills are instruction/support data, accessible through `skill`; prompts are rendered data accessible through `prompt`. The `mcp` function uses lazy, session-owned connections to external MCP servers. Remote tools do not inflate the provider tool schema. Resource activation uses attributed receipts; failure, reload and teardown restore prior registrations and close owned clients.

## Observation, not rendering

Durable `evaluation/started` and `evaluation/completed` events identify an evaluation. Nested registered functions produce `capability/started` and `capability/completed`. Each has a call ID, parent call ID, session ID and operation ID. Starts contain source or arguments; completions contain bounded content, details, error status and a retained result descriptor.

Stdout/stderr and shell progress are transient events; completion records preserve bounded final output. Observer callbacks retain their caller's dynamic bindings so rendering an event cannot be recaptured as program output. The core emits data, not ANSI or OpenTUI objects.

Extension renderers stay in the JVM host. The RPC host can attach bounded `:presentation` text or a renderer error to an event; OpenTUI displays that advice without replacing canonical completion/error status. Session-owned widgets and render/editor requests use the existing reverse-request channel. Renderers must be pure: replay can render the same recorded event again.

## Values are not checkpoints

`(result 42)` retrieves a native value by its session-local integer result ID. Small supported EDN is inline; larger bounded EDN is persisted as an artifact; arbitrary JVM objects remain live-only. `(artifact "uuid")` reads retained artifact content, with a bounded helper limit. Large previews do not require copying the entire value into model context.

Definitions survive ordinary continuation, model changes and compaction. Branch movement, reload, close and process restart reset the namespace. Supported durable values survive restart; live-only objects do not. The REPL supplies useful live references, not automatic JVM checkpointing.

Result descriptors remain structured in history. Provider-readable reference expressions are generated only when constructing requests, after fork/import remapping. This prevents a copied transcript from instructing the model to retrieve a stale numeric ID.

Copying follows canonical result/artifact/entry descriptor locations, never arbitrary native maps. Labels whose targets are outside a selected branch are omitted without changing its provider-visible context. Imports close unresolved tool-call boundaries with explicit unavailable results, not replayed effects. Inline eligibility includes the enclosing history/event depth; deeper supported EDN uses the existing artifact representation.

## Durability and lifecycle

Entries form a parent-linked history tree. Active-path and compaction projection select model context without deleting original history. Store commands commit entries, queues, session projections, operations and events in explicit SQLite transactions. Published durable events come from committed records.

`session.view` reads the session projection, authoritative `:operation` descriptor (or nil), active entries, and event cursor under the session lock. Foreground admission is released before terminal publication, while the worker remains tracked for shutdown until it exits. Every new canonical entry emits `entry/committed` in its transaction. The TUI reconstructs observed activity through the cursor, keeps the atomic snapshot authoritative, then applies later buffered events; abandoned-branch activity is excluded.

A file-backed store has one live runtime owner, enforced by an OS file lock before recovery or expiry. Each session admits at most one foreground operation; independent sessions can run concurrently.

Cancellation is a request, not proof of termination. Shutdown waits for owned operation work and blocking callers. If they do not stop by the deadline, the runtime reports an incomplete closing state and retains ownership. Recovery repairs unresolved provider call/result boundaries without replaying external effects.

`owned-process` creates POSIX process groups before exec and assigns suspended Windows processes to kill-on-close Job Objects before resuming them. Shell and stdio MCP cleanup share this boundary, including escalation and scope-exit confirmation. Resource teardown retains failed cleanup callbacks for retry; an incomplete session cleanup does not release the runtime's store ownership. Deliberately daemonized hostile code remains outside the trusted-local contract.

Provider managers preserve per-session routing/settings while sharing appropriate authentication. SDK 0.6.0 owns built-in completion and managed Codex OAuth; custom endpoints use request-local SDK profiles, codecs, framing, and accumulation without modifying the SDK's global registry. Credential refresh commits only against its originating version. Normal requests retain a stable session cache scope and provider replay data. Unknown usage remains unknown; no padding or warmup requests manufacture cache hits.

Project trust controls executable resource loading, not OS isolation. Clojure, shell functions and trusted extensions run with the process's permissions. Sharing is explicit and belongs to optional hosts.

## ClojureScript + OpenTUI

`scripts/tui.ts` statically imports `@opentui/core`, then loads the compiled ClojureScript entry point. This ESM bootstrap is required because OpenTUI uses top-level await while the compiler emits CommonJS. `tui.edn` uses the ClojureScript Node target; Bun executes the result. No React/Solid layer or parallel TypeScript application model is involved.

- **Transport:** `tui-rpc` owns one child process, UTF-8 JSONL framing, correlation, request deadlines, reverse host requests, and confirmed shutdown. An expired mutation is an unknown outcome, not permission to resend. Forced termination targets the owned process tree and waits for actual process closure.
- **Controller:** `tui-app` handles session hydration/replay, prompts, queues, inspections, file attachments and navigation through existing commands. Provider/authentication work, MCP connections, evaluation, and persistence stay on the JVM.
- **Projection:** `tui-model` is pure `.cljc`. Canonical entries anchor transcript rows; observed calls use call IDs and parent IDs. It groups only consecutive completed read siblings. It neither parses Clojure source to invent work nor infers function success from an outer evaluation.
- **View:** `tui-view`, `tui-present`, and `tui-widgets` use OpenTUI directly. Keyed rows and a persistent composer avoid rebuilding the transcript for each keystroke. Function output expands in place; the inspector is contextual rather than a permanently exposed REPL pane.
- **Values:** expand retained descriptors and artifact pages on demand. Inline native values have a redacted EDN representation because JSON cannot preserve keyword/string key distinctions or arbitrary JVM objects. Live-only values remain explicitly unavailable after evaluator reset.

Enter sends when idle and steers when running; follow-ups are distinct queue items with stable identities. Editing or dropping a delivered item fails rather than resurrecting it. Explicit evaluation is available through the command palette. Session switching preserves drafts, attachments, expansion, and scroll state; branching changes history, not the filesystem.

The inspector is side-by-side at 112 columns and wider; narrower terminals use a dedicated inspector view. Native text selection takes priority over stop/clear shortcuts. Output arriving while reading does not intentionally pull the viewport back to the end.

The UI and actual JVM backend were exercised together, including a live Codex OAuth / Luna / high workflow. See [verification evidence and limits](STATUS.md). [OpenTUI](https://github.com/anomalyco/opentui) is a runtime dependency, not copied application code.
