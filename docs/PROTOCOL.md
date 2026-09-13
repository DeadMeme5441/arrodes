# Stdio protocol

Arrodes uses protocol version 1 over UTF-8 JSON Lines. One connection owns initialization and shutdown. Standard output contains protocol records; diagnostics belong on standard error.

Start the headless process:

```sh
clojure -Srepro -M:run --headless
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

Request-level cancellation is different from cancelling an agent operation:

```json
{"type":"cancel","id":"request-to-cancel"}
```

It does not undo already accepted effects. A running request ID remains reserved until its worker actually finishes. Use unique IDs; do not reuse an ID merely because a cancellation acknowledgement arrived.

## Evaluator and capabilities

`session.evaluate` evaluates source through the shared capability system. `session.invoke` invokes a named capability. Native values are not blindly serialized into JSON: inspect the bounded result and durable descriptor, or use the evaluator's `result` helper.

A controller can attach host capabilities. Invocation produces a reverse request:

```json
{"type":"host-request","id":"HOST_REQUEST_ID","request":{"kind":"capability","name":"host_echo","arguments":{"value":"hello"},"session-id":"SESSION_ID"}}
```

Reply with the same host request ID:

```json
{"type":"host-response","id":"HOST_REQUEST_ID","result":"hello"}
```

UI requests use the same reverse-request mechanism. Host waits and queues are bounded and cancellable. Capability attachment is connection-owned; a connection cannot detach another owner's capability.

## Shutdown

```json
{"type":"request","id":"shutdown","method":"shutdown","params":{}}
```

Shutdown and EOF cancel host waits, settle connection work, detach owned capabilities, and close the runtime. Inspect cleanup results: an incomplete report is not successful termination of all work.

## Transfer

`session.export` supports EDN, JSONL, and HTML. `session.import` accepts the versioned Arrodes representation. Export metadata retains base configuration and declared result/artifact data; imports create new identities and validate references. These are not Pi-compatible wire or storage formats.

`session.share` explicitly uploads an unlisted GitHub gist. It is not access-controlled private storage. Controllers must obtain appropriate user consent before invoking it.
