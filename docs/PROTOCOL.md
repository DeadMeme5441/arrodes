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

**Initialization can delete old session data.** An incompatible
**recognized Arrodes** file-backed store is automatically reset after
exclusive ownership: its SQLite files and owned retained artifacts are
deleted/recreated, with no migration or automatic backup. Export needed
old sessions with a compatible earlier build before calling `initialize`.
Foreign SQLite databases are rejected unchanged. Credentials, settings
and unrelated files are not part of the reset.

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
| Session agents | `agent.start`, `agent.list`, `agent.inspect`, `agent.send`, `agent.messages`, `agent.delivery`, `agent.result`, `agent.wait`, `agent.cancel`, `agent.stop`, `agent.resume`, `agent.submission`, `agent.value` |
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
lists update independently of the active conversation. These events and title
metadata are additive to protocol 1; agent receipt linkage uses SQLite schema 5.

## Session agents

Session agents are independent persistent REPL sessions in one root team, never
function jobs or additional provider tools. Every method is scoped by the calling
`session-id`; team-foreign IDs cannot inspect, message or control another team.
Responses are ordinary JSON projections of the native Clojure records. `agent.start`
accepts `{session-id, name, task, context?, config?, submission-id}` and returns
`{session-id, operation-id, submission-id}` for the accepted child. The child
inherits a snapshot of the parent's selected configuration and has its own
evaluator and initial input; its parent's live Vars/objects are not shared.
Submission identity is generated by the caller *before* a mutating call, not by
the RPC connection request ID.

For example, choose and retain the submission UUID before sending:

```json
{"type":"request","id":"spawn-request","method":"agent.start","params":{"session-id":"ROOT_SESSION_ID","name":"Parser","task":"Investigate parser boundaries","submission-id":"c932e382-a13b-46db-900e-7cd4a868463d"}}
```

If the response is lost, query `agent.submission` with the same root session and
UUID. A `null` response means no matching accepted submission was found; never
infer an outcome from a transport request ID or merely repeat the task text.

| Method | Parameters and result |
| --- | --- |
| `agent.list` | `{session-id}`; returns `{root-id, agents, cursor}` with a root-inclusive team roster. Rows carry `session-id`, `root-id`, `parent-session-id`, `name`, `depth`, `paused?`, `stopped?`, `off-context?`, `pending-count`, `session`, `operation` or null, `phase`, and measured-usage fields (`usage-total`, `usage-measured-calls`, `usage-unmeasured-calls`, `usage`). `usage-total` is the sum of known measurements or null when none exist; unmeasured calls remain explicit, not estimated. |
| `agent.inspect` | `{session-id, agent-id}`; returns one team row. `agent-id` is a target session ID. |
| `agent.send` | `{session-id, target, content, submission-id, wake?}`; `content` is text over RPC (up to 256 KiB); `target` is team session ID/name, `"parent"`, or `"all"`; returns a fixed-recipient receipt. Broadcast excludes the sender. RPC sends are human messages (`kind: human`) and default `wake?` to true; pass false to suppress eligible idle wake. |
| `agent.messages` | `{session-id, limit?, before?}`; returns `{messages: [...]}` newest first without consuming delivery. `limit` defaults to 50 (maximum 500); `before` is a positive integer global message sequence. Rows include sender, recipients, content/kind, and per-recipient delivery statuses. |
| `agent.result` | `{session-id, agent-id, operation-id}`; returns that child's native operation outcome projection, including completed, failed, cancelled or interrupted statuses. It does not block or select the child's latest operation. |
| `agent.wait` | `{session-id, handles? OR receipts?, until?, timeout-ms?}`; at most 20 selectors. Handles are `{session-id, operation-id}`; receipts are send receipts or message IDs. Receipt `until` is `"completed"` (default) or `"delivered"`. Returns `{reason, ready}` with at most 20 handles and `more-ready?` when necessary. Reasons also include `delivered` and `superseded`, alongside `completed`, `message`, `steering`, `timeout`, `nothing`; superseded recipient identities are returned separately. Deadline is at most 300000 ms. No acknowledgement or cancellation on timeout. |
| `agent.cancel` | `{session-id, agent-id, operation-id?}`; requests cancellation of the target operation, or the current target operation when omitted. An idle target is paused without cancelling its accepted jobs or children. |
| `agent.stop` | `{session-id, agent-id}`; closes target subtree admission/wake, cancels operations and session-owned jobs, awaits exits; returns `{status: stopped|stopping, active: [...]}`. |
| `agent.resume` | `{session-id, agent-id}`; unpauses target, schedules eligible pending input; explicitly resuming a root can reopen a stopped tree. |
| `agent.submission` | `{session-id, submission-id}`; returns saved caller-owned launch/send receipt or null to reconcile an unknown outcome. |
| `agent.value` | `{session-id, agent-id, result-id}` (`result-id` is a positive integer); reads only supported durable retained data and returns `{value, value-edn, value-truncated?}`. Live-only descriptors are rejected. |
| `agent.delivery` | `{session-id, receipt OR message-id, offset?, limit?, detailed?}`; returns `{id, from, kind, status, deliveries, total-recipients, next-offset}`. Default 8 recipients, maximum 20. Rows contain `session-id`, delivery `status`, `entry-id`, `operation-id`, `operation-status`; pending/superseded rows have no guessed operation. `detailed?` adds full content for that one message. Only a sender or recipient in the same team can inspect it. |

`agent.delivery` also includes read-time `next` guidance: inert Clojure source strings
for details, relevant managed waiting and paging. Incorporated recipient rows include
`next.result` pinned to their session/operation pair. These are advisory REPL actions,
not RPC requests or an additional delivery state; clients must not execute them
automatically. Inspecting them neither acknowledges nor consumes messages.

`agent/changed` and `agent/message` are durable root-session-scoped summary events;
`data.root-id` identifies the team and `data.session-id` the affected member.
Roster hydration takes `agent.list`'s snapshot plus cursor, buffers events during
loading, and replays after the cursor rather than guessing accepted mutations from
late responses. Detailed transcripts still use each child's own `session.view`.
Unknown start/send outcomes reconcile with `agent.submission` using the same
submission ID; never blindly retry with a new identity.

Direct messages can wake idle, unpaused children; an eligible completion can wake
the root parent. An ordinary REPL peer message does not wake an idle root unless
`wake?` is explicitly true; an explicit RPC human message defaults to waking its
unpaused recipient. Paused/stopped routes never wake automatically.
Foreground cancellation pauses that session's autonomous wake but leaves existing
jobs and children running; explicit run/continue/resume re-enables it. Restart
pauses all teams without replaying operations or input. Branch movement supersedes
old-context routes, while old children remain inspectable. Peer delivery is committed
with its provider-context entry at a safe boundary, not consumed by UI inspection.
Delivery also records its incorporating operation in that same transaction. Several
messages can share an operation, and broadcast recipients can map to different ones.
Cancellation after delivery changes the referenced operation's status, not the
message's already-committed delivery. The association survives current-format restart.
Rich RPC roster/outcome records are intentional UI projections; REPL agent inspection
defaults are bounded and require explicit `:detailed? true` for those records.

Native REPL sends may carry supported bounded EDN; an explicit
`{:result/ref {:session-id sender-id :id positive-result-id}}` copies only
durable inline values into recipient-owned retention, rejecting artifact-backed
and live-only references. Provider context uses a bounded 1,200-character
preview; inspection and export preserve the full supported content. Submission
receipts and incorporating-operation links are part of schema-5 store state.

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

`artifact.read` accepts `{session-id, artifact-id, limit?, offset? OR after?}`.
Pages include `artifact`, `content`, `offset`, `next-offset`, `truncated?`, and `cursor`.
Pass the returned `cursor` unchanged as `after` for continuation; it is null at EOF.
Cursors contain `{session-id, artifact-id, offset}`, are reusable across readers and
current-format restart, and do not grant access. Malformed, wrong-scope or
beyond-content cursors and mixed `after`/`offset` requests are rejected.
Explicit offsets remain 1-based UTF-16 character positions for text or byte positions
for binary content; binary pages also include `encoding: "base64"`.

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

Child-originated host requests identify the child's `session-id`, not whichever
conversation the terminal currently displays. An `operation-id` is included when
the initiating action has one (for example, a model turn); transport-level
`auth.login` can carry a session ID without an operation ID. The host must decide
explicitly and never auto-approve a request simply because it came from a child.

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
Job records require `output-characters` for exact tail offsets. Cancellation
records use `error.code = "cancelled"`, with original interruption details under
`error.cause` when present. Completed/failed records and all RPC metadata remain rich;
compact defaults apply to the REPL status helpers, with `detailed?` for full records.
