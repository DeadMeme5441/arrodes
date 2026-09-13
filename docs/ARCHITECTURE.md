# Architecture

## Boundary

Arrodes is a coding-agent harness. The durable session model represents interaction history; the live runtime performs work against that history. A generalized document/workspace model is not part of this repository.

```text
Terminal / print / JSON / SDK / stdio RPC
                    |
          Session command surface
                    |
        Live session orchestration
          /         |          \
 Session store   Provider   Capability registry
      |          adapters       |
 SQLite + blobs          Coding functions + REPL
```

Interfaces do not implement their own branching, compaction, or agent loops. The common dispatcher is `arrodes.commands/dispatch!`; Clojure callers can also use `arrodes.runtime` directly.

## Modules

| Module | Responsibility |
| --- | --- |
| `modules/common` | Portable value/path/private-file utilities and bounded output capture |
| `modules/session` | Pure session projection, SQLite transactions, history, queues, operations, events, results, artifacts, and transfer |
| `modules/runtime` | Provider/auth adapters, capabilities/evaluator, resources/packages, run decisions, live orchestration, and commands |
| `modules/cli` | Transport framing, CLI selection, terminal input, and rendering |

## Durable values

Session IDs and entry IDs are UUID strings. Timestamps use epoch milliseconds. Entries form a parent-linked tree; the session's head selects its active path.

A session records configuration, status, metadata, and history. Entry kinds include messages, configuration changes, compactions, branch summaries, labels, evaluations, and custom data. Provider messages retain replay metadata needed by the selected provider.

The full tree is not the model context. Context projection follows the active path, applies compaction boundaries, and includes only the relevant message/custom-context entries. Original history remains available.

Store commands use explicit SQLite transactions. Related entries, queue acknowledgements, session projections, operation changes, and events commit together. Revision checks reject stale mutations. Events are published from successful commit results, not speculative pre-commit state.

## Ownership and recovery

A file-backed store acquires a private OS file lock before schema work, live-result expiry, or recovery. Another runtime using that store is rejected. The lock is released on failed opening or genuine close, not deleted while another owner could still reference it.

A live session owns its capability registry, evaluator namespace, resource activation, and foreground slot. At most one foreground operation runs in a session. Independent sessions can execute in the same runtime.

Cancellation is a request to stop, not evidence of termination. Shutdown waits for executor-owned work and blocking caller-thread work. If work does not stop by the deadline, shutdown reports an incomplete/closing state and retains its store, handles, and ownership. A later close can finish cleanup.

Recovery never reruns old external effects. Missing tool results receive explicit interruption/boundary records. Selecting a historical prefix does not undo filesystem changes; selecting or copying a prefix with pending tool calls adds provider-valid missing-result messages without executing them.

## Capabilities and evaluator

One registry holds descriptors and implementations. Provider tool calls and the registered Clojure wrappers enter the same validation, hook, execution-lock, cancellation, progress, and result path.

Execution can be parallel, sequential, or exclusive. Parallel batches are bounded and own their worker lifetime. Their results are persisted in assistant call order. The evaluator is exclusive and can invoke registered functions reentrantly.

The evaluator reads and evaluates forms in sequence in its session namespace. Definitions persist across ordinary continuation, model changes, and compaction. Branch movement, reload, release, and process restart reset them.

`register-tool!` explicitly exposes a function Var. It returns a small acknowledgement rather than a runtime handle. `result` retrieves a native live result or a supported durable reconstruction. Large output is exposed through bounded previews and artifacts.

Arbitrary JVM state is not checkpointed. Live-only values become unavailable when the registry closes. This is distinct from durable conversation history.

## Provider views and caching

A root provider manager owns shared authentication and base catalogs. Each session receives a local profile/catalog view derived from its own project's effective settings. Project endpoints must not bleed into another session's routing. Closing a view does not close shared authentication or global SDK connections.

Requests preserve provider replay data and stable history. Tool definitions are deterministic. Normal requests use a stable session cache scope, rather than a turn or operation ID. Cached and uncached input usage are separate; unknown telemetry is not converted to zero.

Explicit cache-control configuration still needs final integration verification; see the status document. The system does not add padding or warmup model calls to manufacture cache hits.

## Transfer and artifacts

The versioned export packet includes the original base configuration, entries, durable result descriptors, and artifact transfer data. Import validates structure/digests and creates new ownership/IDs while remapping references. Unavailable/live-only data remains explicitly unavailable.

JSONL transfer uses a versioned header containing EDN metadata and one EDN entry record per line, preserving Clojure data without lossy keyword coercion. It is an Arrodes format, not a promise of Pi session-file compatibility.

## Trust

Project trust controls loading executable project resources and settings. Saving trust does not silently reload active code or override explicit per-run trust flags. Extensions are attributed so normal activation failure/reload can withdraw their contributions.

This is trusted local execution, not OS isolation. Arbitrary Clojure, shell commands, and installed extensions have the process's permissions. No automatic session sharing occurs.
