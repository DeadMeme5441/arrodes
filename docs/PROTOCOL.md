# Stdio protocol

Arrodes uses protocol version 1 over UTF-8 JSON Lines. One connection owns initialization and shutdown. Standard output contains protocol records; diagnostics belong on standard error.

Start the headless process:

```sh
clojure -Srepro -M:host
```

It first emits a `hello` containing the protocol version and connection identity. Send `initialize` before domain commands:

```json
{"type":"request","id":"init","method":"initialize","params":{"cwd":"/path/to/project","home":"/path/to/private/arrodes-home","data-dir":"/path/to/private/arrodes-data"}}
```

Use `memory?` for an explicitly ephemeral runtime. Relative application paths should be supplied deliberately; credentials are not protocol configuration data.

## Requests and results

```json
{"type":"request","id":"create","method":"session.create","params":{"name":"Project work","config":{"provider":"codex-backend","model":"gpt-5.6-luna","thinking":"high"}}}
```

A response has the same request ID and either `result` or `error`:

```json
{"type":"response","id":"create","result":{"id":"SESSION_ID"}}
```

The example omits other snapshot fields. Use the returned session ID in later requests. Errors contain a stable code, message, and structured data.

`runtime.inspect` advertises the command list. The dispatcher is the authority for available methods and parameter checks; do not duplicate agent behavior in a controller.

## Asynchronous work

`session.run`, `session.continue`, and `session.compact` return durable operation receipts. Use `operation.inspect`, `operation.wait`, and the operation/session cancellation or queue commands to control work.

```json
{"type":"request","id":"run","method":"session.run","params":{"session-id":"SESSION_ID","prompt":"Explain the project."}}
```

Runtime events arrive independently as `event` records. Durable events have a global sequence; `event.replay` reads them after a cursor. Transient provider/progress output is not a second durable history.

## Atomic view and replay

`session.view` accepts `session-id` and returns `{"state": SESSION_STATE, "entries": ACTIVE_PATH, "cursor": SEQUENCE}`. These are one consistent observation under the session lock. `entry/committed` contains the full assigned entry and is committed atomically with that entry; legacy assistant-message events are not another insertion.

`state.operation` is an authoritative operation descriptor or `null`; `operation-id` and `phase` are not substitutes for its status. Terminal publication happens after foreground admission is released. A controller must not resurrect running/cancelling work from stale phase hints or recreate delivered queue items from late acceptance responses.

A reconnecting controller buffers live events while loading the view. Replay historical events through the snapshot cursor to reconstruct call activity, without replacing the snapshot's entries or queue with older mutations. Apply buffered events newer than the snapshot after this reconstruction. Correlate by sequence/call ID and retain only activity belonging to the active path or current operation. Do not replay external effects.

`session.queue.update` accepts `session-id`, `queue-id`, and `content`; it returns `{"item": UPDATED_ITEM}`. It preserves queue identity, order, timestamp, and delivery options. `session.queue.drop` accepts those IDs and returns `{"removed": REMOVED_ITEM}`. Both reject an item that has already been delivered or removed.

`session.fork` accepts an optional `entry-id` and `position` (`at` or `before`). It creates another conversation branch; it does not revert filesystem effects.

Request-level cancellation is different from cancelling an agent operation:

```json
{"type":"cancel","id":"request-to-cancel"}
```

It does not undo already accepted effects. A running request ID remains reserved until its worker actually finishes. Use unique IDs; do not reuse an ID merely because a cancellation acknowledgement arrived.

## Evaluator and capabilities

`session.evaluate` evaluates source as a first-class session operation. `session.invoke` invokes an instrumented Clojure function. Native values are not blindly serialized into JSON: inspect bounded output and the result descriptor, or evaluate `(result 42)` with the returned integer result ID.

`result.inspect` returns the retained descriptor. Inline results also include `value-edn` (a redacted, bounded native representation) and `value-truncated?`. Prefer this representation when displaying Clojure types: JSON `value` is a projection and can collapse distinctions such as keyword and string keys. Artifact-backed results use the descriptor's artifact ID and `artifact.read` paging; live-only values must not be presented as persisted checkpoints.

Nonfinite floating-point values use a JSON-safe projection such as `{"type":"number","encoding":"edn","value":"##NaN"}` (also `##Inf` and `##-Inf`). Their native value remains available through evaluation and `value-edn`. If response encoding itself fails, the request receives `serialization-error` with `data.unknown-outcome? = true`; reconcile state rather than resending accepted effects.

Durable evaluation events are `evaluation/started` and `evaluation/completed`; nested functions emit `capability/started` and `capability/completed`. Their data includes `call-id` and `parent-call-id`. The event envelope includes `session-id`, `operation-id`, and sequence. Starts carry source or arguments. Completions carry content, details, `error?`, and a retained `result`. Stdout/stderr and shell progress arrive through transient `tool-progress` events with the same call correlation; use final completion output for replay.

The same observation data serves direct user evaluations and agent evaluations. Controllers must not parse source or ANSI output to discover calls, infer parentage, or decide whether an operation completed. These records are UI-independent data, not an MCP protocol.

A controller can attach host capabilities. Invocation produces a reverse request:

```json
{"type":"host-request","id":"HOST_REQUEST_ID","request":{"kind":"capability","name":"host_echo","arguments":{"value":"hello"},"session-id":"SESSION_ID"}}
```

Reply with the same host request ID:

```json
{"type":"host-response","id":"HOST_REQUEST_ID","result":"hello"}
```

UI requests use the same reverse-request mechanism. Host waits and queues are bounded and cancellable. Capability attachment is connection-owned; a connection cannot detach another owner's capability.

Portable presentation requests also support `widget`, `set-widget`, `render`, and `editor`. Widget IDs are scoped by `request.session-id`; `remove?` withdraws the widget. Native renderer functions remain inside the JVM RPC host and never cross JSON. Events and replay results may include an advisory `presentation` object containing `content` or `error`; canonical event status remains authoritative.

## Shutdown

```json
{"type":"request","id":"shutdown","method":"shutdown","params":{}}
```

Shutdown and EOF cancel host waits, settle connection work, detach owned capabilities, and close the runtime. Inspect cleanup results: an incomplete report is not successful termination of all work.

## Transfer

`session.export` supports EDN, JSONL, and HTML. `session.import` accepts the versioned Arrodes representation. Export metadata retains base configuration and declared result/artifact data; imports create new identities and validate references. These are not Pi-compatible wire or storage formats.

`session.share` explicitly uploads an unlisted GitHub gist. It is not access-controlled private storage. Controllers must obtain appropriate user consent before invoking it.
