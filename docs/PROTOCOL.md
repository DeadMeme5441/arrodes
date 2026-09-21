# JSON Lines RPC protocol

`arrodes --rpc` runs the headless Arrodes host on standard input and output. Protocol version 1 uses UTF-8 JSON Lines: one complete JSON object per line. Standard output contains protocol records only; diagnostics are written to standard error.

```sh
arrodes --rpc
```

One connection owns initialization, reverse host requests, attached capabilities, and shutdown.

## Handshake and initialization

The process first emits a `hello` record containing the protocol version and connection identity. Send `initialize` before domain requests:

```json
{"type":"request","id":"init","method":"initialize","params":{"cwd":"/path/to/project","home":"/path/to/private/arrodes-home","data-dir":"/path/to/private/session-data"}}
```

`cwd` selects project identity and the default session working directory. `home` and `data-dir` are optional. Set `memory?` to `true` only for an explicitly ephemeral runtime. Credentials are not initialization parameters.

A normal response repeats the request ID and contains either `result` or `error`:

```json
{"type":"response","id":"init","result":{"version":"0.1.1","protocol":1,"connection-id":"..."}}
```

Errors contain a stable `code`, human-readable `message`, and structured `data`. A serialization failure reports `serialization-error` with `data.unknown-outcome? = true`; inspect state before retrying a mutation.

## Request shape

```json
{"type":"request","id":"create","method":"session.create","params":{"name":"Project work","config":{"provider":"codex-backend","model":"MODEL_ID","thinking":"high"}}}
```

Request IDs must be unique while a request remains active. `runtime.inspect` returns the protocol version and authoritative list of methods supported by the running executable.

### Public methods

| Group | Methods |
| --- | --- |
| Runtime and project | `runtime.inspect`, `project.info`, `project.trust` |
| Setup and auth | `setup.status`, `setup.run`, `auth.status`, `auth.login`, `auth.logout`, `model.list`, `model.refresh`, `model.select` |
| Settings and resources | `settings.get`, `settings.update`, `resource.list`, `skill.read`, `prompt.render`, `prompt.run` |
| Sessions | `session.list`, `session.create`, `session.inspect`, `session.state`, `session.view`, `session.entries`, `session.tree`, `session.configure`, `session.name`, `session.label`, `session.rewind`, `session.fork`, `session.clone`, `session.delete` |
| Work and queues | `session.run`, `session.continue`, `session.compact`, `session.steer`, `session.follow-up`, `session.cancel`, `session.queue`, `session.queue.update`, `session.queue.drop`, `session.reload`, `session.evaluate`, `session.invoke`, `session.command` |
| Operations | `operation.list`, `operation.inspect`, `operation.wait`, `operation.cancel`, `operation.steer`, `operation.follow-up` |
| Results and artifacts | `result.list`, `result.inspect`, `artifact.list`, `artifact.inspect`, `artifact.read`, `artifact.write` |
| Host capabilities | `capability.list`, `capability.set`, `capability.attach`, `capability.detach` |
| Transfer | `session.export`, `session.import`, `session.share` |
| Packages and events | `package.list`, `package.install`, `package.remove`, `package.update`, `event.replay` |

The dispatcher returned by `runtime.inspect` is authoritative for method availability and parameter validation.

## Setup, configuration, and project identity

`project.info` returns the project `id`, canonical real `root`, external state `directory`, and trust state. It does not create a repository dotfolder.

`setup.status` reports `ready?`, `configuration-ready?`, `trust-ready?`, effective `config`, provider availability, project/trust details, and home migration information. It never prompts, authenticates, or changes configuration. Reading public SDK model metadata may populate the model metadata cache.

`setup.run` completes missing setup through reverse `select`, `input`, and `render` host requests. Optional `provider`, `model`, `thinking`, or `config` values preserve explicit choices; `force?` reopens configuration. It authenticates or explicitly reuses credentials, performs actual model discovery for the selected provider, and saves global defaults. With `session-id`, it also applies the selected provider, model, and thinking level to that session.

A `host-cancel` reply cancels setup. Authentication or settings writes completed before cancellation remain complete; call `setup.status` before continuing.

Secret authentication inputs set `secret?` on the reverse request. Hosts must mask API keys and pasted OAuth codes or redirect URLs, exclude them from drafts/history, and clear editor storage when the dialog closes.

`settings.get` returns the effective redacted settings map. `settings.update` accepts `changes` and `scope` (`global` or `project`, default `project`); a JSON `null` removes a key. Project changes require trust. `project.trust` accepts boolean `trusted?` and applies on the next session resource load.

## Explicit model selection

`model.select` accepts `provider`, `model`, `thinking`, and `scope` (`session` or
`default`). Session scope requires `session-id`. Default scope saves global defaults
and also configures `session-id` when supplied; without a session it supports initial
setup. Selection validates provider availability, the exact model, and supported
reasoning in both affected catalogs before writing. It returns `{scope, config}` plus
`session` whenever a session was configured. Settings and session storage are separate:
if settings were saved but session configuration fails, the error explicitly reports
`default-saved?` and asks the client to refresh before retrying.

`model.list` accepts an optional `provider` to restrict the returned catalog.
The TUI loads that provider's cached catalog first, then discovers models through
`model.refresh` when connected. Requests retain session/navigation ownership.

Authentication is separate: `auth.login` does not select a model. A TUI can cancel
an in-progress login using the protocol `cancel` envelope with the login request ID.

## Asynchronous work

`session.run`, `session.continue`, and `session.compact` return durable operation receipts:

```json
{"type":"request","id":"run","method":"session.run","params":{"session-id":"SESSION_ID","prompt":"Explain the project."}}
```

Use `operation.inspect` or `operation.wait` to observe completion. `operation.wait` accepts `timeout-ms` from 1 through 300000. Steering, follow-up, and cancellation are available by operation ID or session ID.

Runtime events arrive independently:

```json
{"type":"event","event":{"type":"entry/committed","seq":42,"session-id":"..."}}
```

Durable events have a global sequence. `event.replay` accepts `after`, optional `session-id`, and `limit` from 1 through 500; it returns events and the resulting cursor. Transient streaming/progress output is not a second durable history.

Transient `operation/phase` events carry `data.phase` and the owning operation ID;
`compacting` indicates internal summarization, with no summary text in the reply
stream. Clients ignore phases for superseded or settled operations and use the
`session.view` snapshot's `state.phase` when hydrating. Durable `session/named`
events carry `data.name` and `data.source` (`auto` or `user`) so headers and session
lists can update independently of the active conversation. These events and title
metadata are additive; the SQLite schema and existing history formats are unchanged.

## Atomic view and reconnect

`session.view` accepts `session-id` and returns:

```json
{"state":{},"entries":[],"cursor":42}
```

The session state, active history path, and cursor are one consistent observation under the session lock. `state.operation` is the authoritative current operation descriptor or `null`.

A reconnecting controller should:

1. buffer live events while loading `session.view`;
2. replay recorded activity through the snapshot cursor, without replacing the snapshot's entries or queue;
3. keep the snapshot's entries and queue authoritative; and
4. apply buffered events newer than the snapshot.

Never replay external effects or infer accepted queue items from late responses. `entry/committed` carries the assigned entry and is committed atomically with it.

`session.queue.update` requires `session-id`, `queue-id`, and `content`; `session.queue.drop` requires the two IDs. Both reject an item already delivered or removed.

`session.fork` accepts optional `entry-id` and `position` (`at` or `before`). It creates another conversation path; it does not revert filesystem effects.

## Request cancellation

Cancel one in-flight request with:

```json
{"type":"cancel","id":"request-to-cancel"}
```

This is distinct from cancelling an agent operation. It does not undo accepted effects, and the request ID remains reserved until its worker finishes. A cancellation acknowledgement is not permission to reuse the ID or resend a mutation.

## Evaluation, capabilities, and results

`session.evaluate` accepts Clojure `source` as a first-class session operation. `session.invoke` accepts a registered function `name` and argument object. Both return bounded presentation plus a retained result descriptor rather than blindly serializing every native value.

`result.inspect` returns that descriptor. Inline results also include `value-edn` and `value-truncated?`; JSON `value` is a projection and may not preserve distinctions such as keyword versus string keys. Artifact-backed results expose an artifact ID for paged `artifact.read`. Live-only values must be shown as unavailable after evaluator reset, not as durable checkpoints.

Nonfinite numbers use a JSON-safe projection such as:

```json
{"type":"number","encoding":"edn","value":"##NaN"}
```

Durable evaluation events are `evaluation/started` and `evaluation/completed`; nested registered functions emit `capability/started` and `capability/completed`. Their call IDs, parent call IDs, session ID, and operation ID provide correlation. Starts carry source or arguments; completions carry content, details, error status, and result descriptor. Transient `tool-progress` records carry the same call correlation.

### Attached host capabilities

`capability.attach` registers a connection-owned function. Invocation produces a reverse request:

```json
{"type":"host-request","id":"HOST_REQUEST_ID","request":{"kind":"capability","name":"host_echo","arguments":{"value":"hello"},"session-id":"SESSION_ID"}}
```

Reply with the same ID:

```json
{"type":"host-response","id":"HOST_REQUEST_ID","result":"hello"}
```

Errors use `error` instead of `result`. A connection may detach only capabilities it owns. Reverse waits and queues are bounded and cancellable.

UI reverse requests also use this channel. Portable kinds include `select`, `input`, `notify`, `widget`, `set-widget`, `render`, and `editor`. Widget IDs are scoped by `session-id`; `remove?` withdraws a widget. Events may include advisory `presentation` content from a core renderer, but canonical event status remains authoritative.

## Transfer and sharing

`session.export` supports `jsonl`, `edn`, and `html`; pass `path` to write a private local file or omit it to receive `content`. `session.import` accepts either `path` or `content`, not both, and creates new identities for imported data.

`session.share` explicitly invokes GitHub CLI to upload an HTML export as an **unlisted GitHub gist**. It is not private storage. Obtain user consent before calling it; anyone with the URL can read the result.

## Shutdown

Request an orderly shutdown:

```json
{"type":"request","id":"shutdown","method":"shutdown","params":{}}
```

Shutdown and end-of-file cancel reverse waits, settle connection work, detach connection-owned capabilities, and close the runtime. Inspect the returned cleanup report. An incomplete report means some owned work did not terminate and must not be presented as a clean shutdown.

`session.list` includes `last-message-at` (epoch milliseconds, or null when no message
exists), derived from recorded message entries rather than the session update time.

## Background jobs (additive protocol-1 methods)

Start trusted Clojure functions through `session.evaluate`, for example
`(jobs/start! {:name "Build"} #(bash {:command "bun test"}))`. The retained evaluation
value is a session-scoped job handle. Every job method requires `session-id`.

| Method | Parameters and result |
| --- | --- |
| `job.list` | Optional `limit` (1–500, default 100), `before` job ID; returns `{jobs: [...], active-jobs: [...], next-before: ...}`; jobs are newest first |
| `job.inspect` | `job-id`; returns status, origin, error, result descriptor and output artifact reference |
| `job.wait` | `job-id`, optional `timeout-ms` (1–300000, default 1000); returns current record without cancelling on timeout |
| `job.cancel` | `job-id`; requests cancellation of the job and its owned children |
| `job.output` | `job-id`, optional `limit` (1–32768, default 4096), and one of zero-based `offset`, `after` cursor, or `tail?: true`; returns a text page and reusable `{job-id, offset}` cursor |

Use `result.inspect` with a completed job's `result-id` for bounded native EDN, or
`jobs/result` in the REPL for its native value. `session.view.state.jobs` contains the
newest 100 records plus every active job. `job/changed` carries `{job: record}` durably; `job/output` carries
`{job-id, content}` transiently. Completed output is artifact-backed. Job cancellation
can return `cancelling`; terminal state means execution/owned-child cleanup has settled.
Cross-session controls fail with `job-not-found`. No request automatically replays work.

Job output cursors do not acknowledge or consume output. Reusing the same cursor
returns the same retained range; separate readers are independent. A cursor from a
different job or beyond the capture fails with `invalid-output-cursor`. `tail?` is
bounded by `limit` and reports capture truncation; it cannot recover discarded text.
New records include optional `output-characters` for exact tail offsets. Cancellation
records use `error.code = "cancelled"`, with original interruption details under
`error.cause` when present. Completed/failed records and all RPC metadata remain rich;
compact defaults apply to the REPL status helpers, with `detailed?` for full records.
