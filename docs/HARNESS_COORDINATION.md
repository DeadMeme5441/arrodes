# REPL-first harness coordination

Status: **implementation design for the session-agent feature**. This records
selected policies and the code-grounded coordination model; the [product](PRODUCT.md),
[session](SESSIONS.md), [RPC](PROTOCOL.md), and [format](COMPATIBILITY.md) contracts
specify observable behavior. Section 1 and the probes in section 11 describe the
pre-implementation baseline, not present limitations or fresh verification.

## Design

Coordination is explicit without replacing the evaluator, provider loop, store, or
job service. Admission, input boundaries, settlement and evaluator replacement
are owned transitions. Addressed communication connects existing sessions; an
agent's canonical identity is its session ID.

Arrodes is a persistent Clojure REPL with a harness around it. Models compose ordinary
functions and native values. A child is another such session, not a function job, a
new provider tool, a copied JVM namespace, or another Arrodes process opening the store.

The interaction target is a capable interactive harness: steer, queue, cancel,
delegate, communicate, wait, inspect, compact, and recover. Omp's documented
child-session, peer-message, follow-up and result-delivery behavior informed
the design, but Arrodes retains its own REPL-first ownership model.

Reading paths:

- [Baseline code](#1-pre-implementation-baseline-and-motivating-gaps) and
  [invariants](#2-invariants-that-cannot-be-traded-away).
- [Data models](#3-the-data-model) and [22 interactions](#4-interaction-models).
- [Transitions](#5-coordination-transformations-and-commit-boundaries),
  [scheduling](#6-scheduling-and-managed-waiting), and
  [agent interfaces](#7-session-backed-agents-and-their-end-to-end-interfaces).
- [Lifecycle/recovery](#8-branching-cleanup-export-and-recovery),
  [implementation sequence](#9-implementation-sequence-and-file-ownership),
  [selected policies](#10-selected-product-policies), and
  [verification model](#11-verification-and-evidence).

## 1. Pre-implementation baseline and motivating gaps

| Concern | Implementation and consequence |
| --- | --- |
| Provider action | [`provider_repl.clj`](../src/clj/arrodes/provider_repl.clj), `request`/`evaluation`: the provider gets exactly `repl`; registered functions remain Clojure calls. |
| Persistent evaluation | [`repl.clj`](../src/clj/arrodes/repl.clj), `evaluate!`: ordered forms, persistent Vars and `*1`/`*2`/`*3`/`*e`; earlier forms survive later failure. |
| Registry ownership | [`capabilities.clj`](../src/clj/arrodes/capabilities.clj), `create!`/`evaluate!`: one namespace/generation, evaluation lock, effect locks, live values, and owned resources per registry. Effect locks are not shared between sessions. |
| Session handles | [`runtime.clj`](../src/clj/arrodes/runtime.clj), `make-handle`: fresh registry, resource activation, and provider view for each session; installs the `jobs` namespace alias. |
| Admission | `acquire-foreground!`/`operation-start!`: one foreground slot per session, durable operation receipt, live slot indexed by session and operation ID. |
| Model loop | `run-loop!`/`evaluate-calls!`: commit assistant response, execute its REPL calls in response order, commit tool results, deliver eligible input, continue or settle. |
| Pending user input | `queue-operation!`/`deliver-intents!` and [`run.cljc`](../src/cljc/arrodes/run.cljc), `select-intents`: steering enters after the REPL-call batch; follow-ups enter at start/turn boundaries. The queue itself is session-owned even when acceptance targets an operation. |
| Final input boundary | An empty turn-boundary drain closes `:accepting-input?` before post-turn compaction and `:after-run` hooks. Late operation steering/follow-up is rejected. |
| Settlement | Before coordination cutover, `settle-operation!` released admission under the session monitor, committed outcome, then published; `execute-operation!` separately signalled driver exit. |
| Cancellation race | Before cutover, terminal classification could miss an accepted cancellation; a controlled probe saw `:cancelling` followed by `:completed`. The selected transition orders cancellation and settlement. |
| Worker capacity | Before cutover, `open!` used a fixed 1–16-worker operation pool; a waiting parent could occupy a child worker slot. |
| Background functions | [`jobs.clj`](../src/clj/arrodes/jobs.clj): separate executor/cancellation/output, same registry and live bindings, bounded admission. A job owns its nested function jobs; the launching foreground turn does not own accepted jobs. |
| Job delivery | `deliver-job-results!` commits structured context and acknowledgement together before a provider step. `jobs/result` separately acknowledges explicit retrieval. Neither idle completion nor UI inspection starts a model call. |
| Persistence | [`store.clj`](../src/clj/arrodes/store.clj), `transact!`/`commit!`: one serialized SQLite connection, atomic entries/projection/queue/operation/events. Durable records and live workers are intentionally different. |
| Replacement/close | `with-session-reset!`/`close!`: block admission, cancel owned work, wait outside the session monitor, retain resources/store if cleanup is incomplete. |
| Observation | `session-view` supplied a snapshot/cursor; the earlier TUI applied ordinary events only to the active/hydrating session. |

The implementation addresses multi-session interactions without introducing another
independent owner of execution state.

## 2. Invariants that cannot be traded away

1. A loaded session has one evaluator generation. Turns, model changes, ordinary
   messages, and compaction do not recreate it.
2. One foreground operation owns a session at a time. REPL calls in one provider
   response remain ordered; concurrency inside Clojure remains ordinary composition.
3. Jobs may share that evaluator concurrently under the existing job contract.
   They are not serialized behind the foreground evaluation lock.
4. A failed form does not undo earlier definitions or external effects. Cancellation
   is a request, not rollback, and arbitrary JVM code can ignore interruption.
5. An operation outcome, released foreground admission, and actual worker exit are
   distinct facts. Resource teardown requires exit, not merely a terminal label.
6. Delivery into model context and its durable acknowledgement are atomic. Looking
   at an inspector or receiving a notification does not consume model delivery.
7. Native values are not replaced with JSON/string previews. Live JVM objects stay
   session-local; cross-session communication transfers supported data or explicit
   retained references, not shared atoms, closures, streams, or registry objects.
8. Branch selection is not filesystem undo. Old work must not silently enter a new
   branch's context. Fork/import never restart workers or recreate a live team.
9. A stale operation ID or evaluator generation cannot control newer execution.
10. Snapshot/replay remains authoritative after reconnect; transient text is not
    reconstructed by rerunning work.
11. No external call, user extension hook, model request, arbitrary evaluation, or
    cleanup wait executes while holding a coordination/database critical section.
12. The core implements coordination. RPC and TUI are adapters/observers, not a second
    scheduler. Headless and embedded use must have the same semantics.

## 3. The data model

### Existing durable identities

| Model | Meaning | Not the same as |
| --- | --- | --- |
| Session | Stable conversation/configuration identity, cwd, selected history head, metadata. Main and child agents use this identity. | Thread, assignment, model request, evaluator. |
| Entry | Immutable recorded history node with session and parent-entry identity. Active context is a projection of the selected path. | Pending delivery or transient progress. |
| Operation | One admitted run/continue/evaluate/compact attempt, its lifecycle and outcome. | The lifetime of its session or its background jobs. |
| Job | One session-owned background function execution, its origin and retained outcome. | A child model session. |
| Result/artifact | Native-value availability, bounded presentation, and retained content. Result integers are session-scoped. | An automatically portable reference to another session's live value. |
| Pending user input | A session-owned steering/follow-up item editable until delivery. | An immutable historical user message. |

Keep these models. Do not introduce an `Agent` ID, a universal `Task` table, a second
conversation transcript, or an assignment entity merely to wrap an existing operation.
A child configuration is ordinary session configuration plus explicit launch context;
a named role is data, not a new runtime class.

### Minimum additional durable facts for delegation

**Session provenance/addressing.** Extend sessions with parent/root session identity,
a team-local addressable name, and creation origin (operation, history head/context).
The root is an existing session, not a new team entity. Enforce address uniqueness
within that root transactionally. A display title may change independently of the
address. Queries for a root's children must not depend on scanning transcript text.
Use distinct delegation fields: existing session `:parent-id`/`:fork-entry` describe
history-copy lineage in `store/copy-path!`, not active agent membership. Fork/import
must deliberately clear executable delegation metadata rather than copy it through
the existing generic metadata-copy path.

**Peer message.** An immutable message ID, sender session, kind, bounded content or
retained-content reference, creation time, and optional reply correlation. The runtime
supplies the sender from invocation context; text cannot spoof it. Initial task context
and peer content are not promoted to privileged developer instructions.

**Delivery.** Message ID, recipient session, recipient context scope, order, and
pending/delivered/superseded state. Broadcast is one message plus a fixed recipient
set captured at acceptance; it is not a subscription for future children. Delivery
status means incorporation into recorded context, not proof that a model understood it.

**Submission identity.** A caller knows a stable submission ID before issuing a new
spawn/send mutation. Persist its association with the accepted session/operation or
message/delivery IDs so a lost response can be reconciled. It is not the connection's
RPC request ID, and equal prompt text is not an identity. Repeated use of that same
identity cannot launch another execution; there is no automatic retry/resend loop.

**Context scope.** Add a durable context epoch for addressed routing. Branch movement
changes it; ordinary entries, compaction, configuration changes, and evaluator reload
do not. A child's return route captures the parent's epoch at launch. This distinguishes
rewinding to the same/empty head from continuing the original context. It is separate
from evaluator generation and from session revision, which changes much more often.

**Wake policy.** Explicit data determines whether an accepted message can start an
idle session. Record cancellation/pause state so a delayed completion cannot silently
undo a user's stop. Restart does not re-enable autonomous execution from pending data.

Keep the existing user queue and job-outcome records as their authoritative sources.
They can be projected into a common boundary-delivery plan without first copying all
of them into a new universal inbox. User queue edits remain legal before delivery;
peer messages remain immutable. This avoids changing native job/result semantics just
to make storage look uniform.

### Live state and its authority

A small session coordination value owns admission mode, active operation ID, input
admission, execution phase, and evaluator-generation identity. Use explicit transitions
rather than independently writable flags spread across callers. Conceptually:

```clojure
{:admission :open                 ; or :resetting / :closing
 :active-operation operation-id  ; nil while idle
 :input-admission :open          ; closed for non-steerable/settling operations
 :phase :evaluating
 :generation evaluator-generation}
```

This is not a persisted JVM checkpoint. Worker handles, cancellation tokens, evaluator
objects, resource handles, and wait registrations stay live. Operation status is durable;
the current phase can remain a live projection as it is today. Persisted session status
is an output projection, not a second independent admission decision.

Retain an operation-worker index until actual exit, even after foreground admission
is released. The existing `:done`/`:finished` distinction serves a real purpose; do not
remove it to reduce the number of promises. Cancellation tokens are worker-visible
signals written by the owner, not another source of lifecycle truth.

Evaluator construction also needs explicit ownership. Today `registry` calls
`make-handle` while holding both session and handle locks; activation can execute
extension code. `close-handle!` similarly closes resources under those locks. Replace
that with a single owned loading/closing attempt: reserve its identity under the gate,
activate or close outside it, then install/retire only if that attempt still owns the
session generation. Concurrent callers await the same attempt outside the gate.
Track construction/cleanup until actual exit, including calls from embedded clients
without a foreground operation. A failed or superseded activation must clean up its
own resources, not discard them or overwrite a newer generation.

## 4. Interaction models

`Preserve` labels established contracts and `Add` the delegation behavior specified
by this implementation design. Section 10 records the selected policy decisions.

| ID | Interaction | Required observable behavior |
| --- | --- | --- |
| I01 | Start from the composer — Preserve | Typing/model selection does not create a stored session. A valid Send creates/adopts the session and starts an operation. A failed/unknown receipt does not discard the draft or blindly resend. |
| I02 | Compose in the REPL — Preserve | `def`, `defn`, native collections, `result`, and registered functions compose normally. Calls in a provider response run in order. A later turn uses the same bindings and generation. |
| I03 | Fail partway through evaluation — Preserve | Earlier forms/effects remain. Retain the failing phase/form index and output. The next model step can inspect `*e`/the retained descriptor. Do not rerun the whole source to recover. |
| I04 | Steer while a provider/tool is running — Preserve | Accept only against the current open run/continue operation. Finish the outstanding provider/tool protocol boundary; append steering after the ordered REPL-call batch, not by preempting arbitrary code. |
| I05 | Queue/edit/drop a follow-up — Preserve | Follow-up waits for start/turn boundary, not ordinary tool boundaries. Edits/drops win only before atomic delivery; delivered items cannot be edited or resurrected. |
| I06 | Input races with finalization — Preserve + Add | Operation-targeted input after closure is rejected. Session-addressed peer input may still be stored for a later operation. Arrival and idle-wakeup decisions cannot lose a message between the last drain and settlement. |
| I07 | Cancel foreground work — Preserve | Persist cancellation request, signal the owned worker, show cancelling until execution cooperates. Retain effects/results already produced. Stale cancellation does not affect a newer operation. Accepted jobs survive. |
| I08 | Start/read/wait/cancel a function job — Preserve | Run a closure over session values with fresh invocation/cancellation context. Foreground remains usable; native results/output retain current contracts. Nested function jobs remain owned by their parent job. |
| I09 | Job completes during/after a turn — Preserve | Append outcome context once at the next provider-step boundary on the applicable path. `jobs/result` acknowledges explicit retrieval; inspection does not. Idle jobs notify UI but do not wake a model. |
| I10 | Change model or compact — Preserve | Keep evaluator generation, definitions, live objects, jobs, and pending eligible input. Context-overflow recovery retries only the provider after safe compaction; it does not replay settled REPL effects. Post-turn compaction does not reopen input. |
| I11 | Spawn a child — Add | Return native session/operation handles after acceptance. Child has fresh conversation/bindings, explicit task/context, selected inherited configuration, and its own resource activation. Parent continues immediately. No copying the parent namespace or implicit transcript inheritance. |
| I12 | Parent/peer sends a message — Add | Resolve addresses within the root, persist message/delivery, return an acceptance receipt. Running recipients see it at a safe boundary; idle handling follows explicit policy. Self-generated broadcasts exclude the sender unless explicitly addressed. |
| I13 | Wait for work or a message — Add | A managed wait wakes for selected completion, incoming peer message, user steering, cancellation, or deadline. It does not starve child workers or consume UI/model delivery. Wake returns to the current Clojure caller; it does not serialize/restart its stack. |
| I14 | Child settles — Add | Atomically record the operation outcome and a uniquely correlated parent completion delivery. Include failures/cancellation/interruption, not just successes. Parent gets bounded context and an inspectable full result, not the child's entire transcript. |
| I15 | Follow up with the same child — Add | Address the same session; if idle and permitted, start a new operation using its live evaluator. Result handles identify a specific operation. Failed/completed operations do not erase the child session or silently replace its namespace. |
| I16 | View/switch/steer a child — Add | Parent draft, scroll and selection survive navigation. Child transcript hydrates from its own snapshot/cursor. Background roster updates continue. Human messages and peer messages retain different provenance. |
| I17 | Reload or replace a local evaluator — Preserve + Add | Block new admissions/jobs, quiesce local owners, close only after actual exit, then create a new generation. Timeout keeps the old resources/store owned. A parent's reload does not inherently reset independent child evaluators. |
| I18 | Branch/fork/import/export/delete — Preserve + Add | Branch movement invalidates old automatic routes; compaction does not. Fork/import creates independent session identities without workers/live peers. Export is inspectable data, never executable continuation. Deletion cannot leave apparently valid live routes or dangling retained references. |
| I19 | Shutdown, crash, or reconnect — Preserve + Add | Shutdown cancels owned runtime work and waits; incomplete cleanup remains explicit. Restart marks unfinished operations/jobs interrupted without replay. Pending peer records remain inspectable, but restart does not autonomously resume agents. TUI reconnect may restart the owned core and therefore lose bindings. |
| I20 | Child requires host interaction — Add | Attribute request to session/operation, route to the actual host, and preserve secret handling. A missing host fails honestly; an RPC host that never replies times out. Never auto-approve because it is a subagent. |
| I21 | Concurrent writers — Add | Shared cwd is shared mutable filesystem state. Session effect locks do not protect other sessions, and raw Clojure can bypass wrappers. Use explicit file ownership; never imply that read-only profiles or Git worktrees are OS sandboxes. |
| I22 | Exhaust capacity or fail admission — Preserve + Add | Reject before launching effects. A rejected spawn must not leave an unreported executing child. An admitted operation must be schedulable even if its parent is waiting. No automatic retry of an uncertain spawn/send. |

### Timeline A: steering and follow-up are not the same boundary

```text
provider response: repl A, repl B
  -> evaluate A (definitions persist)
       user steering S accepted; follow-up F accepted
  -> evaluate B (can use A's definitions)
  -> commit tool results; append S and acknowledge it
  -> next provider request includes S, not F
  -> assistant response with no REPL calls
  -> append F and acknowledge it; continue the same operation
  -> final response; atomically close input if no eligible input remains
  -> post-turn compaction/after-run; settle; release/exit signals
```

Do not split provider tool-call/result pairs by injecting peer text into an unresolved
batch. Long arbitrary code is not asynchronously steerable. A managed wait can return
because steering arrived, but the remaining Clojure forms still determine when that
provider boundary is reached.

### Timeline B: peer arrival at the final boundary

There are two legal orders under the recipient coordination boundary:

- Arrival first: store the delivery; the final-boundary decision sees eligible input
  and continues, committing its context/acknowledgement before the next model call.
- Input closure first: store a session-addressed pending delivery without reopening
  that operation. On settlement, recheck pending input plus wake policy and admit at
  most one subsequent operation. A message arriving after settlement performs the
  same check.

Both the arrival path and settlement path recheck authoritative state. No thread
waits on a one-shot notification without checking the underlying predicate. Human
operation-targeted steering retains its current rejection semantics; do not silently
turn a stale steering request into a new prompt.

### Timeline C: cancellation and successful completion race

Cancellation and settlement are ordered by the same owner. If cancellation is
accepted before terminal settlement, classify the outcome as cancelled
even if the worker later returns a value. If completion commits first, a later cancel
observes that terminal operation and does not touch a newer one. Earlier external
effects remain in either case.

The baseline probe demonstrated why this precedence matters: execution was paused
after its last cancellation check, but before settlement; the accepted `:cancelling`
receipt was followed by `:completed`. A regression should cover both orderings.

A cancellation token is updated after the durable request succeeds; interruption is
an execution signal, not proof of exit. Worker cleanup and foreground admission remain
separately observable. A persistence failure cannot publish a successful terminal event
or release resources based on an uncommitted outcome.

### Timeline D: native values versus inter-session data

```clojure
;; Existing behavior: a job may close over this session's live values.
(def index (atom {}))
(def build (jobs/start! #(swap! index assoc :answer 42)))

;; Session-agent API: a child gets an independent evaluator, not the atom above.
(def child (agents/start! {:name "Parser"
                           :task "Investigate parser boundaries"
                           :context "Inspect only; report evidence."}))
```

A child handle contains at least `:session-id` and its initial `:operation-id`.
`agents/result` selects that operation, not whichever answer is latest when read.
It returns a compact native status/answer projection by default; `:detailed? true`
explicitly exposes the original native outcome. Lists/messages are paged (8 by default,
20 maximum); neither configuration nor raw provider payloads enter ordinary inspection.
It does not parse JSON/EDN out of arbitrary assistant prose or infer a result from the
child's last evaluation. Native reports can be explicitly sent as supported immutable
data; their durable representation must round-trip keywords, ratios and collections.

`agents/delivery` maps each send receipt to its recipient deliveries and incorporating
operations. The binding commits with the input entry, survives restart, and is not
guessed from an agent's latest operation. `agents/wait {:receipts [receipt]}` follows
that binding without a roster polling loop. Broadcast and same-operation batching
remain explicit. `help` is the single bounded discovery entry point for coding
functions, jobs, agents, and native REPL inspection helpers.

An explicit cross-session value read must use a session-qualified reference and the
existing retained-value contract. It must reject live-only objects rather than hand
out another session's atom/closure/resource. Materialize delivered report content under
the recipient's retention ownership so inspection/export does not depend forever on
the child remaining undeleted. Preserve original provenance separately from local IDs.

## 5. Coordination transformations and commit boundaries

Separate commands (requests), inputs (information), durable history (facts), worker
reports (observations), and notifications (views of committed changes).
Peer text is never a lifecycle command. These named transitions describe
implementation responsibilities, not a second scheduler or general effects DSL.

| Decision/transform | Inputs | Outputs | Effects outside the decision |
| --- | --- | --- | --- |
| Admit operation | Session coordination, requested kind, runtime lifecycle/capacity | Accept/reject, operation record, next admission state | Submit worker; rejection/submission failure is observable. |
| Prepare provider context | Active history, effective config, resource context, retained descriptors | Canonical request with only `repl` | Resource/hook/provider work stays explicit; hooks are not assumed pure. |
| Select delivery | Boundary, pending user input/jobs/peer deliveries, context scope | Ordered entries, source-specific acknowledgements, next input state | None in pure selection. |
| Accept input | Target scope, provenance, payload, current state | Queue/mailbox mutation and receipt, wake intent | Signal registered waiters and attempt policy-authorized admission. |
| Observe provider response | Validated response, operation identity | Assistant entry and ordered evaluation requests | Evaluate Clojure once through existing machinery. |
| Observe evaluation | Native outcome and retained descriptor | Tool-result entry and phase change | Retention remains explicit; no serializing arbitrary objects into coordination state. |
| Settle operation | Current operation, worker outcome, cancellation request, pending input | Terminal record, route completion, release/wake plan | Notify observers, signal waiters; worker exit is reported separately. |
| Replace evaluator | Idle foreground, generation, job/resource ownership | Replacement gate and quiescence/close plan | Cancel, await actual exit, close, activate replacement outside gate. |
| Recover process | Durable nonterminal records and pending deliveries | Interrupted outcomes, retained pending data, no live workers | No automatic replay or model wake. |
| Project observation | Durable records plus coherent live state and event cursor | Session/agent view | RPC encoding/TUI rendering, not new lifecycle decisions. |

A useful internal shape is a small transition result:

```clojure
{:next-coordination next-state
 :commit            store-command
 :after-commit      effect-descriptions}
```

Do not turn it into an extensible effect DSL. Keep named ordinary functions and a
small closed set of lifecycle actions. Pure functions receive IDs/time as data instead
of generating them. Do not run effects inside retryable `swap!` functions.

### Applying a transition

1. Under the owning session boundary, read the needed coordination and durable state.
2. Decide; validate operation/generation/context and any expected revision.
3. Apply one explicit store transaction for related durable changes.
4. Install the small live coordination/index changes while the boundary is still held.
5. Release locks, then run external actions. Outcomes come back with their original
   identities and pass through the same owner.

Database commit and live memory are not one transaction. Reserve admission under the
gate; roll it back if persistence fails; do not launch a worker before acceptance
commits. After a committed admission, submission failure is a failed/interrupted
operation, not a pretended rejection with no record. A process crash in that interval
is recovered as interrupted without executing the request later.

If terminal persistence fails while the worker exits, keep the outcome uncertainty
explicit and gate further model admission until durable history/operation state is
reconciled. Do not manufacture a successful terminal receipt or retry the execution.
Result/artifact retention may have already written content: current job settlement
uses separate retention and terminal-record transactions. That is not evidence that
arbitrary native values can be included in a generic database transaction. For new
cross-session outcomes, ensure retained content exists, then commit its ownership
references, terminal record and completion delivery together; unreferenced staged
content is not a reason to repeat the effect.

Do not hold sender and recipient session monitors together for ordinary message send.
Validate durable routing and insert the envelope/deliveries in a store transaction;
signal each recipient after releasing the store/sender boundary. Recipient delivery
and model admission remain owned by that recipient. Root-wide stop/close first prevents
new launches, snapshots owned IDs, then cancels/waits outside coordination locks.

### Publication is an explicit part of the refactor

Today runtime callbacks can run while a caller still holds the session monitor.
Simply moving `emit-events!` after `locking` can reorder committed events and cause
cross-session deadlocks to be replaced by missing-event bugs.

Use one ordered durable publisher per runtime, driven by the existing event journal
and commit-sequence cursors, not a second durable event log. Complete the owning live
transition before publishing its events; do not invoke subscribers under store/session
locks. Preserve per-session order and increasing published durable sequence across the
team. Gaps from unrelated/deleted records are not assumed to be missing integers.

A terminal event concerns its operation; a snapshot read by a delayed observer may
already show a newer operation. It must never show the settled operation still owning
foreground admission. Update concurrency assertions to test this invariant rather
than requiring every delayed observer to see an idle session.

Wake/admission correctness must not depend on a TUI subscriber or successful callback.
Transient streams remain separately bounded, operation-tagged observations. A slow or
broken observer cannot decide whether a message is delivered or an operation settles.

## 6. Scheduling and managed waiting

The pre-implementation fixed operation pool could deadlock synchronous parent/child
waiting: a one-worker probe left a child unexecuted while its parent waited.
Bounded admission with a worker for each admitted operation avoids accepting
dependent work behind occupied workers.
An admitted operation gets a worker within the configured hard limit. At capacity,
reject before launch, rather than returning a handle for unschedulable work.
Function-job capacity is separate and unaffected.

Waiting operations still count toward the live-resource cap; that cap is not a permit
held over all provider/tool execution. If separate provider concurrency limits are
needed, hold those only around an actual provider call, never across REPL waits.
Keep function-job capacity separate. No thread-stack checkpointing or general DAG
scheduler is needed. Java virtual threads are not a prerequisite; adopting them would
require a separate audit of Java 21 monitor pinning and native/extension calls.

The operation admission setting is `:operation-limit` (default 32, range 1–128);
the former `:operation-threads` pool setting is not a compatibility alias.

`agents/wait`:

- observes selected operation handles, receipt-derived operations, or incoming activity;
- atomically registers/rechecks its predicate to avoid completion-before-registration
  lost wakeups;
- returns a reason and ready IDs, not an acknowledgement that consumes message content;
- wakes for incoming peer input, user steering, cancellation, or a bounded deadline;
- keeps the caller's REPL/evaluation ownership and returns normally into its Clojure
  stack; it cannot force arbitrary remaining forms to yield;
- rejects waiting on oneself and detectable cycles of explicit operation waits;
  arbitrary conversational deadlocks are not claimed to be solved;
- releases registrations on all exit paths and never cancels work merely because a
  wait timed out.

Completion/message content is incorporated at the subsequent safe model boundary.
Explicit inspection may intentionally reread it; automatic incorporation occurs once
per delivery. Preserve `jobs/wait` and `jobs/result` semantics rather than retrofitting
them to this new wait-any behavior.

## 7. Session-backed agents and their end-to-end interfaces

### Runtime and REPL

`arrodes.agents` installs beside `jobs` in the evaluator registry. It obtains
session/operation identity from invocation context and delegates to runtime
callbacks. It neither owns a model executor nor creates a second provider loop
or a circular `runtime`/`agents` dependency.

The facade supports `start!`, `send!`, `inspect`, `list`, `wait`, `result`,
`cancel!`, `stop!`, `resume!`, `messages`, `submission` and durable `value`.
Inspection and history reads are paged and non-consuming. `submission`
looks up a caller-owned accepted launch/send receipt without resending.
Host adapters use the same service. Tool guidance describes these functions
while the provider schema remains the single `repl` action. See
[sessions](SESSIONS.md) for REPL arities and [RPC](PROTOCOL.md) for JSON parameters.

Spawn validates name/root/depth/capacity/config/context, reserves admission, and records
child session, provenance, initial input and operation acceptance coherently. Provider
credentials follow existing provider views, not copies embedded in launch records.
Inherited configuration is a selected snapshot, not a live link to later parent model
changes. Resources/extensions activate for the child's cwd/trust through the existing
handle path. MCP clients are currently session-owned and lazy; do not silently share
live clients or host-attached capabilities as if they were ordinary configuration.

Spawning inside a function job records that provenance but does not turn the model
child into an owned function-job closure. Session/team lifecycle governs it explicitly.
Runtime settings bound each team to `:agent-limit` members (default 32,
range 1–128) and `:agent-max-depth` delegation levels (default 4,
range 1–16). Enforce both at admission, not merely through prompt text;
waiting parents also count against the independent `:operation-limit`
(default 32, range 1–128).
Read-only roles remain guidance/tool selection in this trusted JVM, not a sandbox.

A normal final assistant response settles the child's operation. There is no need for
a mandatory hidden `yield` provider tool or automatic missing-yield retry loop. Every
subsequent child operation produces its own correlated outcome; peer messages and
completion notices are different kinds, so completion does not recursively generate
acknowledgement conversations by itself.

### Retention and scope

A completion notice contains a short status/final-answer preview, source
session/operation and canonical status. The recipient retains the full
assistant response with selected provider/model/usage/cost/finish metadata
under its own result ownership; the full opaque operation record remains
inspectable on the child for diagnostics. Source IDs are provenance, never
bare recipient result IDs. No entire child transcript or raw SDK map is
inserted into the parent's model context.

A live child keeps its evaluator between assignments. Do not silently park it on a TTL
and discard the feature that distinguishes Arrodes. Explicit reload/close/restart is a
generation change and is visible. Bound admitted child population/resources and reject
new launches when necessary rather than invisibly evicting a reusable REPL.

### RPC, TUI and host interaction

Add domain methods for child launch/list/inspect, addressed send, message inspection,
and operation-targeted result/wait/cancel using existing request/response framing.
Return durable receipts tied to the caller's known submission identity; after timeout
or serialization failure, inspect that identity rather than resending. A transport
request cancellation does not retract an already accepted send/spawn. Preserve all
existing `session.*`, `operation.*`, `job.*`, `result.*`, and `artifact.*` contracts
during the coordination-only stage.

Extend the parent/team snapshot with lineage, current operation, message counts and
retained outcome summaries from one coherent store read/cursor. Emit root-scoped
summary events for child changes so a parent view does not infer its roster solely
from whatever child stream events happened to arrive while it was open. Detailed
child transcripts still use their own `session.view` and replay cursors.

The TUI should maintain a team roster plus one focused conversation, not eagerly mount
and hydrate every child's full transcript. Add `/agents`, keyboard navigation to a
child and back, current activity, failure/cancelling/waiting states, message inspection,
and explicit stop-operation/stop-tree controls. Never open a popup on completion.
Preserve per-session drafts, scroll anchors, selections, hydration tokens and navigation
generations. Late events from another operation must not overwrite the focused view.

Keep context size per focused session; do not sum child context windows into
the parent's context meter. The team roster separately totals known measured
token usage and counts unmeasured calls, without fabricating missing data.
Reconnect restores retained records, not lost partial streams. Human input,
peer messages and automatic outcomes remain distinguishable in the transcript.

Reverse host requests need child session/operation attribution and correct ownership
when focus changes. Queue competing prompts deliberately; navigation alone does not
answer or cancel a child's request. Show who is asking. No automatic approval for
headless subagents, and no secret inputs in drafts/history/logs.

There are concrete existing gaps to address, not just new labels: `auth.login` in
`commands.clj` can select a session provider manager but omits `session-id` from its
reverse input request. Raw runtime calls without a callback fail `host-unavailable`;
an RPC client that does not answer reaches `host-timeout`. The TUI does not implement
every capability kind accepted by the RPC host. Preserve explicit unsupported-kind
failure unless adding that handler is deliberately in scope; do not claim every
host-attached function automatically works in a child TUI.

## 8. Branching, cleanup, export and recovery

| Action | Local evaluator/jobs | Child sessions and peer routing |
| --- | --- | --- |
| Normal turn/model change/compaction | Preserve current generation and job ownership. | Preserve identity, routes and child evaluators. |
| Parent reload | Quiesce local jobs and replace only its generation. | Independent child evaluators remain; durable pending routing remains valid. |
| Branch movement | Existing idle/reset requirements and job cleanup still apply. | Advance recipient context epoch; supersede old pending automatic deliveries and disallow old return routes from waking the new context. Off-context children stay inspectable; never silently adopt them into the new branch. |
| Fork/clone/import | New identity and fresh evaluator on use; no jobs recreated. | New independent root; no copied live membership/mailbox or autonomous launches. Historical provenance is data only. |
| Session export | Preserve current history/result/artifact semantics. | Include delivered message content needed to interpret that history, with remapped local references; no running team or pending routing reconstruction. A recursive team-transfer format is not required for this refactor. |
| Delete standalone session | Current quiescence rules. | Reject a parent deletion with linked descendants unless explicitly deleting its tree. Stop affected work before deletion; preserve already copied recipient content and remove/supersede dead routes transactionally. |
| Stop a tree | Close launch/wake admission for that scope first. | Snapshot descendants, cancel operations and their session-owned jobs, await actual exits; no child can escape by spawning during the stop. Remain stopping if cleanup is incomplete. |
| Runtime close | Cancel and await all operations/jobs/resources as today. | All child sessions are in the same runtime ownership scope; no second core process to forget. |
| Abrupt restart | Mark unfinished execution interrupted; do not rerun functions. | Preserve pending messages and recorded outcomes, reconcile missing completion notices by source-operation identity, and require explicit resumption. No replay of Clojure stacks, commands, spawn, or send. |

Normal operation cancellation and root-tree stop are separate actions. A stop that
suppresses autonomous wake must remain effective against already pending and late
messages until explicitly resumed. Automatic delivery on a different context epoch
is disallowed even if the old head is also an ancestor of the new selection.

Completion outcome plus its delivery insertion must be one store transaction, with a
uniqueness constraint on source operation/recipient/delivery kind. Restart can reconcile
records without emitting a second completion. This is exactly-once durable insertion,
not exactly-once model reasoning or external effects.

## 9. Implementation sequence and file ownership

These stages explain the design and its verification boundaries. The current
observable session-agent contract is in sections 7–10 and the owning documents;
stage exit criteria are not claims of fresh test results.

### A. Make existing coordination explicit

- Add a small pure [`arrodes.coordination`](../src/cljc/arrodes/) module for admission,
  input-boundary, cancellation/settlement, and replacement decisions. Keep provider
  projections/compaction decisions in `run.cljc`.
- In `runtime.clj`, consolidate the owner of `:foreground`, reset gates, input admission,
  phase, and operation-worker indexing. Keep public entrypoints and durable formats.
- Replace scattered flag changes in `acquire-foreground!`, `deliver-intents!`,
  `settle-operation!`, `execute-operation!`, `queue-operation!`, `cancel-operation!`,
  `with-session-reset!`, and `close!` with those transitions and explicit effects.
- Decide cancellation precedence inside the terminal transition, not just at a check
  before acquiring its monitor. Add a failing-before/passing-after boundary regression.
- Refactor `registry`, `make-handle`, and `close-handle!` into reserve/work/finalize
  lifecycle steps. Keep one evaluator initialization attempt per session and never
  execute extension activation, reverse host requests or resource cleanup under
  session/global handle locks. Race initialization with reset/shutdown explicitly;
  cleanup failure retains ownership and prevents replacement from hiding old resources.
- Preserve `repl/evaluate!`, capability wrappers, hook semantics and native retention;
  do not move arbitrary hook execution into the pure coordinator.
- Keep job worker/child ownership in `jobs.clj`; adapt its session-boundary callbacks,
  not its closure semantics. Define lock ordering so coordinator paths never wait for
  a job-manager lock while a job holds it waiting for coordination.
- Introduce ordered out-of-lock durable publication as a separately verified change,
  including all runtime/job/store event producers. Keep snapshot/replay coherent.

Exit: I01–I10 retain their public feature contracts, the demonstrated cancellation
race has the declared precedence, and terminal observation, stale control, and
cleanup-under-lock scenarios have explicit regression coverage.

### B. Fix schedulability without changing the REPL model

- Replace the fixed queued operation executor with bounded admission and a worker
  available to each admitted operation; update runtime settings and callers.
- Preserve synchronous embedded APIs, asynchronous RPC receipts, cancellation tokens,
  dynamic invocation bindings, phase attribution, and shutdown ownership.
- Add a narrow wait-registration primitive for lifecycle/input predicates. Do not put
  workers or promises into SQLite or build a general continuation interpreter.

Exit: the smallest configured capacity rejects impossible admission clearly; accepted
parent/child operations make progress during managed waits, cancellation wakes waits,
and cap rejection launches no effects. All workers remain bounded and owned.

### C. Add durable addressed delivery

- Extend `store.clj` with session routing/context fields and peer message/delivery
  records, constraints, paging, atomic context acknowledgement, and operation-completion
  routing. Reuse artifact retention and existing transactions.
- Extend boundary selection with source-tagged delivery plans. Preserve user steering
  precedence, follow-up boundaries, job acknowledgement, and provider message validity.
- Update `run.cljc`, `provider_repl.clj`, session/context projections and transfer logic
  so provenance is durable while provider requests contain only supported fields.
- Implement wake decisions on arrival and settlement, with cancellation/context guards;
  do not rely on frontend events to trigger model work.

Exit: I06/I12/I14/I18/I19 hold across crashes, branch changes, duplicate notifications,
late arrivals and pagination. Pending records never imply an already-seen model input.

### D. Expose session-backed delegation through ordinary functions

- Add the `agents.clj` REPL facade and runtime callbacks; extend `make-handle` and
  model-facing discovery/instructions. Keep `provider_repl/request` at one action.
- Implement transactional spawn acceptance, scoped names/lineage, configuration
  snapshots, limits, operation-scoped results, explicit sends and reusable sessions.
- Implement managed waiting and operation/tree control on top of A–C; not a second
  agent executor or a wrapper around `jobs/start!` holding the parent registry.
- Update resources/host capability ownership and result transfer where necessary.

Exit: I11–I15/I20–I22 work through actual REPL calls with synthetic providers; native
values and child bindings survive ordinary follow-ups; failure and stop paths are real.

### E. Complete RPC and terminal interaction

- [`hosts/rpc/arrodes/commands.clj`](../hosts/rpc/arrodes/commands.clj): domain methods,
  canonical validation, durable receipts, inspection and result projection.
- [`hosts/rpc/arrodes/rpc.clj`](../hosts/rpc/arrodes/rpc.clj): connection/host-request
  ownership and framing only, not lifecycle policy.
- [`tui_rpc.cljs`](../src/cljs/arrodes/tui_rpc.cljs) and
  [`tui/controller/client.cljs`](../src/cljs/arrodes/tui/controller/client.cljs):
  mutation/unknown-outcome classification, known submission identity, target-aware
  requests and host correlation. Connection cancellation is not operation cancellation.
- [`tui_app.cljs`](../src/cljs/arrodes/tui_app.cljs) and
  [`tui/controller/sessions.cljs`](../src/cljs/arrodes/tui/controller/sessions.cljs):
  root roster routing, focused-session hydration and navigation-safe reconciliation.
- [`tui/controller/submission.cljs`](../src/cljs/arrodes/tui/controller/submission.cljs):
  selected target, receipt reconciliation and draft preservation.
- [`tui_model.cljc`](../src/cljc/arrodes/tui_model.cljc), presentation/inspection/chrome
  and a small agent browser: durable/transient projection, provenance, keyboard
  controls, per-session context and aggregate measured usage.
- [`tui/commands.cljs`](../src/cljs/arrodes/tui/commands.cljs),
  [`tui/screens.cljs`](../src/cljs/arrodes/tui/screens.cljs), input handling and
  [`tui_view.cljs`](../src/cljs/arrodes/tui_view.cljs): command discovery, roster
  mounting, dismiss-first Escape behavior and attributed reverse-host prompts.
- Update the owning product/session/architecture/RPC/TUI/configuration/compatibility
  docs, machine-readable scope and Unreleased notes for implemented behavior.

Exit: I16/I20 operate in the actual TUI at wide/narrow sizes, with multiple active
children, pending human prompts, unknown mutation outcomes, reconnect and cancellation.

### Format cutover

Session routing, submissions and incorporating-operation links were introduced in **SQLite schema 5**.
The original destructive cutover described by this design has been replaced by
the [preservation-first format contract](COMPATIBILITY.md). Supported schema-3/4/5
layouts receive a consistent retained backup and transactional upgrade to schema 6; unsupported,
newer, malformed and foreign stores fail intact. Artifact ownership and exclusive
locks remain required; interrupted legacy reset markers no longer resume deletion.
RPC JSONL framing remains protocol 1. Ordinary session exports remain format 1;
typed history-retrieval references require format 2.
Export copies delivered content under local retention ownership and severs
executable team routes; it is not a recursive executable team transfer.

## 10. Selected product policies

| Choice | Selected behavior |
| --- | --- |
| Idle child follow-up | A directly addressed message can wake an idle, unpaused child. Other peer messages follow the message's explicit wake policy; completion alone does not create acknowledgement loops. |
| Idle main session | An eligible child completion may wake its unpaused root parent. Ordinary REPL peer chatter does not wake the root unless `:wake? true` was requested; explicit human RPC/TUI messages default to wake unless opted out. |
| Foreground cancellation | Pause the cancelled session's automatic wake routing, but preserve accepted function jobs and independent child sessions. An explicit next run/continue or `agent.resume` unpauses it; a separate tree stop cancels descendants and their jobs. |
| Branch movement | Advance the context epoch and supersede old returns rather than cancel independent child execution. Old-context children stay inspectable; their old messages cannot enter the new context. |
| Restart | Pause routing for every session; pending messages/outcomes remain inspectable, but nothing runs automatically until explicitly resumed. |
| Filesystem | Shared cwd/checkouts with explicit writer ownership. No worktree manager, OS sandbox, automatic merge or filesystem undo is supplied. |
| REPL lifetime | Keep child evaluators between assignments, without an idle TTL. Explicit reload/close/restart loses live-only bindings; capacity rejects admission instead of silently evicting a child. |

Stop-tree closes durable admission and wake routing before cancelling owned operations
and function jobs; it waits for actual exits and reports incomplete cleanup honestly.
Each child operation, including interruption, produces at most one durable completion
route to its launch parent. Agent routing is distinct from the session-history
`:parent-id`/`:fork-entry` provenance fields.

## 11. Verification and evidence

### Historical pre-implementation investigation (not feature verification)

- A throwaway synthetic-provider program ran two session model loops concurrently,
  executed REPL tool calls, retained distinct bindings, and returned durable completed
  operation receipts. No paid/provider network calls were used.
- A throwaway boundary probe blocked the first of two REPL calls, queued steering and
  follow-up, and observed request roles `user, assistant, tool, tool, user` at the
  next step. Steering appeared there; follow-up appeared only after the next ordinary
  assistant response. A blocked `:after-run` hook observed late input rejected as
  `operation-not-active`. Bindings/generation survived configuration changes.
- The scheduler probe used the real `runtime/start!` and `runtime/wait!` with two
  ordinary sessions and a registered coordination function. With one worker, the
  parent's wait observed `{:status :running :child-provider-calls 0}`; with two,
  `{:status :completed :child-provider-calls 1}`. Both settled after capacity became
  available. These probes exercised ordinary baseline sessions, not subagents.
- A controlled cancellation probe paused the baseline operation immediately before
  settlement. Cancellation returned `:cancelling` and settlement committed
  `:completed`; this motivated the selected terminal precedence. It is not a
  passing test of the replacement.
- `python3 scripts/verify-rpc.py` passed: 21 commands, 38 durable events, host roundtrip,
  cancellation request-ID ownership, JSONL-only stdout, stderr diagnostics, registered
  REPL function invocation, native-value projection and namespace reset.
- Earlier in the same investigation, `python3 scripts/verify-jobs.py` passed actual
  RPC start/wait/cancel, retained output/value, abrupt JVM crash, restart interruption
  without effect replay, session hydration and job scope.

These temporary probes left no source/test scaffolding and predate agent APIs,
mailboxes, scheduler changes and the team browser; they are **not** a verification
claim for the session-agent feature.

### Implementation verification

- `bun run test` passed on macOS arm64: 209 core tests, 1,156 assertions, and the full
  TUI suite, including agent controller, native browser, keyboard, and transcript cases.
- The context-efficiency model-loop smoke measured 294 printed characters for the
  help overview, 1,388 for an agent help page, 474 for one wait contract, 678 for a
  two-session compact roster, and 156 for its compact result. Provider bootstrap
  instructions measured 1,717 characters. These are character counts, not token estimates.
- That smoke ran parent and child through the real runtime/REPL loop with an offline
  synthetic provider, reused child state in a follow-up, obtained the new operation
  from its receipt, and reopened storage with the association intact and no replay.
- A real JSONL process with an isolated local provider exercised child launch,
  submission reconciliation, portable native results, addressed human input,
  automatic child follow-up, measured usage, tree stop, and fresh-process recovery
  without model replay.
- A live Codex OAuth terminal drive used `gpt-6-astra` for the main session and
  `gpt-6-luna` for both Inspector and Reviewer. They inspected a disposable Python
  project, exchanged a peer message, and the main session corrected the weighted-mean
  calculation. All four fixture tests passed; a managed function job ran the tests.
- Inspector's later continuation incremented its existing atom from 41 to 42 without
  redefining it in that continuation. After cancelling a subsequent live REPL sleep,
  an actual `/eval @smoke-counter` still returned `42`.
- The actual TUI was exercised at 120×40 and 58×28: full-screen roster and composition,
  independent parent/child drafts, visible model identities, explicit selected target,
  cancellation, resume, tree stop, and real xterm F7/F8 input. A rejected message
  preserved its draft. Agent completion headings did not render raw SDK metadata.
- Two reproduced regressions failed before correction and passed afterward:
  capacity released in another team now wakes accepted input, and a nested function
  job stopping its own team returns `:stopping` without waiting on its job ancestors.
  At that implementation stage, store tests also covered stopped-message
  preservation and the then-current destructive reset boundaries. That reset policy
  is superseded by the preservation-first compatibility contract above.
- A cancellation-admission regression reproduced four escaped effects before its
  fix. Even when Clojure catches the thread interruption, a cancelled invocation
  now cannot create another job/agent, queue peer input, or undo its pause with resume.
  Previously accepted background work retains its independent lifetime.
- Disposable projects, databases, artifacts, and terminal-capture dependencies were
  removed. No live credentials or machine-specific paths were added to source.

The live drive is an exercised integration result, not a promise of model determinism
or an OS sandbox. New release executables and other operating systems were not exercised
as part of this feature drive.

### Verification criteria

- [`runtime_test.clj`](../test/arrodes/runtime_test.clj): ordered evaluation/composed
  effects, stale-operation control, evaluator persistence and restart without replay.
- [`runtime_lifecycle_test.clj`](../test/arrodes/runtime_lifecycle_test.clj): blocked
  cleanup, reset admission, closed post-turn input, wait/release ordering, coherent
  terminal snapshots and cancelling-state inspection.
- [`jobs_test.clj`](../test/arrodes/jobs_test.clj): independent contexts/native values,
  actual cancellation, parent function-job ownership, cleanup, branch-safe delivery,
  fork reference remapping, restart and capacity rejection.
- [`context_recovery_test.clj`](../test/arrodes/context_recovery_test.clj): provider-only
  recovery after settled REPL effects, bounded overflow recovery and cancellation.
- [`ui_api_test.clj`](../test/arrodes/ui_api_test.clj),
  [`tui_model_test.clj`](../test/arrodes/tui_model_test.clj) and
  [`tui_app_test.cljs`](../test/arrodes/tui_app_test.cljs): atomic view/replay anchors,
  delivered queue item versus late receipt, operation hydration, cross-session draft
  ownership and late navigation responses.

Implementation verification should use deterministic barriers to exercise both orders
of arrival/settlement, cancellation/completion, and admission/reset; not timing sleeps
that merely hope to hit a race. Add behavioral tests for new mailbox scope, completion
uniqueness, result ownership, wait wakeups/cycles, tree-stop versus spawn, fresh child
bindings, reusable evaluator identity, and malformed/foreign handles. Test snapshots
against durable records and actual worker effects, not copied mock replies or source text.

Run real RPC processes for framing, uncertain outcomes, reconnect, and crash recovery.
Run real model-loop scenarios with an offline synthetic provider for parent/child
progress and message visibility. Exercise the actual terminal for roster navigation,
per-session drafts, host prompts, cancellation, narrow layout and late events. A unit
test passing is not proof that a runnable child, readable result, or usable UI exists.
