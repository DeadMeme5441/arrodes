# Sessions and results

A session is a durable conversation plus configuration, history branches, queued input,
operations, and retained results. Each live session has its own evaluator namespace
for composing coding functions and inspecting native values. A delegated agent is
another session in the root session's team, not a job or shared evaluator.

## Create, switch, and resume

Start Arrodes in a project:

```sh
arrodes
```

Normal startup and `/new` open an empty composer without creating a stored session.
Only Send creates the session, after validating that the message contains text or an
attachment. Model selection and typing do not create sessions or saved drafts. Recent
sessions on the welcome screen are explicit resume actions. Reconnecting retains the
current session or unsent composer text.

Use `/new` to open a new composer and `/sessions` or `F2` to switch. Resume a known session directly with:

```sh
arrodes --session SESSION_ID
```

The sessions screen shows the timestamp of the last recorded message in local time.
Renaming a session or changing its model does not change that timestamp. Sessions with
no recorded messages show “No messages yet.”

An unnamed session gets an immediate short title from its first message. An owned
background model call then produces a concise title without delaying the main
answer. Explicit creation names and manual renames always win, including a rename
made while generation is running. Title failures leave the local fallback intact;
closing Arrodes cancels pending title work. No later message automatically renames
the session. Names and title ownership are persisted in existing session metadata.
Title-model usage is recorded separately from the conversation's context usage.

The session keeps the working directory where it was created. Sessions for a project are stored in that project's external data directory; they are not stored in the repository.

Over RPC, the corresponding methods are `session.create`, `session.list`, `session.inspect`, and `session.view`. `session.view` returns a consistent session state, active history path, and event cursor for reconnecting clients.

## Run, steer, and queue

- `Enter` starts work while idle.
- `Enter` steers the current operation while it is running.
- `Ctrl+Q` adds a follow-up to the queue.
- `/pending` edits or drops input that has not been delivered.
- `Esc` dismisses suggestions/dialogs or returns from reading to the composer before
  requesting cancellation. With the composer already active, it requests cancellation.
- `/continue` asks the model to continue from current context.
- `/compact` reduces model context while preserving original history.

Cancellation is a request, not an undo operation. A command or file edit that already completed remains completed. Delivered queue items cannot be edited or resurrected.

Cancelling a foreground operation pauses automatic agent wake for that session;
accepted background function jobs and independently running child sessions continue.
An already-cancelled invocation cannot start another managed job or agent, send
new agent input, or resume an agent. These APIs check the invocation's cancellation
token even if its Clojure code catches a thread interruption. Cleanup controls
remain available; arbitrary local code is still not a sandboxed transaction.
Explicit `session.run`/`session.continue` or `agents/resume!` resumes its routing.
Use a separate agent tree stop to close delegation/wake admission before cancelling
descendant operations and session-owned jobs. Cancellation and shutdown wait for
actual worker exit before releasing owned live resources.

RPC integrations receive durable operation receipts from `session.run`, `session.continue`, and `session.compact`. Track them with `operation.inspect` or `operation.wait`; control them with the operation or session cancellation, steering, and follow-up methods.

## History and branches

`/history` shows the recorded history tree and can create a new branch from an entry. A branch selects another conversation path for future model context; all original entries remain stored.

**Branching is not filesystem undo.** It does not revert edits, shell commands, network calls, or any other external effect. Review or restore files with the appropriate version-control or filesystem tools.

Context compaction also does not delete the original history. It changes the active model context while preserving durable records for inspection and export.

Branch movement advances the session's routing context. Old-context child returns
and pending peer deliveries are superseded rather than injected into the new path;
off-context children remain inspectable and may continue independently. Compaction
does not change routing context.

Automatic compaction defaults to 85% of the selected model's context window and
uses the latest completed provider call's reported usage. Each completion replaces
the current context measurement; counts from previous calls are not added to it.
Usage includes uncached input, cache reads, cache writes, and output; a reported
total takes precedence. Reasoning and modality breakdowns are not added again.
Cumulative token spend is separate from the current context size.

Automatic compaction runs only when another provider request needs context, not
after a completed final answer. An idle session does not incur a speculative
summary request. `/compact` remains an explicit immediate action.

No character-based estimate is used. New user messages and REPL output remain
unmeasured until the next provider completion. A very large addition can therefore
exceed the provider's limit before a new measurement arrives; it is not represented
as a fabricated exact count. A completion without usage leaves the count unknown.
After compaction, previous measurements are invalidated until another ordinary
completion reports usage. The compaction call's own usage measures the material
being summarized, not the reduced context. Original usage records stay in history.

If a provider explicitly rejects the input context before producing output,
Arrodes attempts one recovery compaction and retries that provider request once.
It preserves the latest complete user turn and all its settled REPL results;
completed evaluations, filesystem effects and user messages are not replayed.
Rate limits, output limits and generic payload-size rejections do not trigger this
path. Recovery also respects `:auto-compact? false`. If no safe earlier boundary
exists, summarization fails, or the retry still overflows, the operation stops with
an explanation and asks for smaller input or a new session. Cancellation prevents
the retry. No character estimate is reintroduced.

Compaction and branch summaries are internal continuation state. Their partial
text and reasoning are not published to the normal assistant stream, including
when summarization fails or is cancelled. Completed summaries remain durable and
available to subsequent model calls and history inspection.

The footer shows **Compacting context…** during summarization and returns to the
normal operation status afterward. The live phase is also present in `session.view`
so refreshing during compaction preserves that status.

### Cache continuity and measured usage

Ordinary requests keep a stable system prefix and append new context instead of
rewriting previous messages. Evaluator namespace/generation identifiers are not
inserted into that prefix. After reload/restart or branch navigation, the next
model run records one environment notice when prior context exists: definitions
and live objects are gone, while saved results and external effects remain.
This notice does not replay effects or pretend to restore a JVM checkpoint.

Normal requests use the session's cache scope; compaction, branch summaries and
title generation use separate scopes. Explicit cache settings remain respected.
Changing instructions, model/provider or compacting history can legitimately
invalidate provider caches; extension context/request hooks must remain deterministic
to preserve a stable prefix. Large native values stay available through retained
result/artifact inspection rather than requiring wholesale reinsertion in context.

`/usage` (also the clickable footer context indicator) separates the latest ordinary
request's context from measured cumulative usage on the active history path.
It displays uncached input, cache reads, cache writes, output and known estimated
USD spend, including compaction and branch summaries. Missing counters/prices remain
unknown or explicitly partial. Title requests and inactive branches are excluded.

## Inspecting activity and results

With conversation focus, use the arrow keys to select a row, `Enter` to inspect it, and `Space` to expand or collapse its activity. Inspector tabs are:

1. Summary
2. Output
3. Value
4. Code

The Value tab shows whether a result is inline, artifact-backed, live-only, saved, or unavailable. Large artifacts are paged rather than inserted wholesale into the conversation.

Advanced evaluation is available through `/eval`. For example:

```clojure
(def source (read {:path "src/example.clj"}))
(result 42)
```

`result` accepts the session-local integer ID shown on a completed evaluation or function call. An arbitrary Clojure definition or object is a live convenience, not a checkpoint.

### Working in the REPL

Start with `(help)` for a small group/count overview. Page one group with
`(help {:group "coding" :query "grep" :offset 0 :limit 8})` (maximum 20),
then inspect one native Clojure function contract with `(help 'grep)` or
`(help "agents/start!")`. Add `{:detailed? true}` to a named lookup only
when its full registered schema or native diagnostics are needed. The catalog
includes selected coding and extension functions, jobs, agents, and REPL
value/workspace/artifact helpers; unselected functions are not advertised.

`(help)` also lists available workflow trailheads without loading their recipes:

| Selector | Workflow |
| --- | --- |
| `(help {:workflow "background"})` | Start, observe, retrieve or cancel a function job |
| `(help {:workflow "delegation"})` | Launch, reconcile, message, wait and stop a child session |
| `(help {:workflow "failure"})` | Inspect partial execution and known receipts before another mutation |
| `(help {:workflow "results"})` | Inspect bindings, retained values and paged output |

Recipes are inert guidance, not executable workflows. Evaluate their steps separately
and choose the branch matching the observed state. Only recipes whose native helpers
are installed are advertised.

Ordinary `def`/`defn` bindings appear in `(workspace)`, not the help catalog.
An evaluation returns the last expression only: use `let` and a final map to
bundle useful values, or `prn` for explicit output.

Keep batches small and return only what is useful:

```clojure
{:contract (help "read")
 :sample (read {:path "src/example.clj" :limit 20})}
```

Bind a large detailed value first, then select fields in later expressions instead
of printing the whole record. No intermediate forms are automatically displayed.

Search and listing functions return structured data only:

```clojure
(def source-files "Selected source paths." (find {:path "src" :pattern "**/*.clj"}))
(mapv #(read {:path (:path %)}) (:entries source-files))

(def error-sites "Literal error references." (grep {:path "src" :pattern "*e" :literal true}))
(group-by :path (:matches error-sites))
```

`find` and `ls` return maps containing `:entries`; `grep` returns `:matches`.
Paths are absolute and directly reusable. A search match includes its 1-based
`:line`, `:text`, `:text-truncated?`, and context line maps. All three functions
report `:complete?` and `:limit-reached?`. Search limits conservatively mark a
stopped traversal incomplete; they do not assert how many results were omitted.
`grep` also reports the number of skipped binary, invalid UTF-8 or oversized files.
Excluded generated/VCS directories are outside the search scope.

`read` returns a plain string for text. With `:detailed true`, it returns
`:text`, `:path`, `:offset`, `:lines`, `:next-offset`, and `:eof?`. String evaluation
results display as readable text while retaining the original native string.

Use docstrings on ordinary `def` and `defn` forms to describe useful state. The
binding name is its label; optional `^{:label "..."}` metadata adds a friendly
label. `(workspace)` lists live binding names, docs, types and bounded size
information without printing their values, realizing lazy sequences or dereferencing
atoms. It includes the current evaluator generation. Use `{:query "source"}` to
filter names, or `{:offset 0 :limit 50}` to page (maximum 100 bindings). This is
session-local inspection, not shared project memory or automatic snapshots.

`(result-info 42)` retrieves the retained descriptor without loading its value.
It exposes failure details and output artifact references. Failed evaluations report
the number of completed top-level forms and the failing form's index/phase; effects
before a failure remain in place. Reader, evaluation and printing failures are
distinguished. `*e` is still the latest live exception, while retained failure
details remain inspectable after another error or evaluator reset.
The failing form may itself have produced effects even when zero top-level forms
completed. Retained call details can contain effect receipts, but neither the completed
form count nor those receipts are a complete effect log. Inspect relevant external state
and reconcile known agent submission IDs before deliberately issuing another mutation.

Individual inspection responses expose a small `:next` map of **inert Clojure source
strings**. Copy the action you intend; inspecting a response never executes its hints.
`result-info` links to an available value, artifact pages, or failure reconciliation;
failed/unavailable results do not advertise a successful value read. Job and agent
inspection link their distinct identities without inventing a universal task handle.
Hints describe the inspection snapshot, not guaranteed future availability. Result
integers remain local to the inspecting session; do not transplant them to another
session. Roster/result lists remain compact rather than repeating navigation per row.

`(results {:limit 20})` lists references newest first. Pass its `:next-before-id`
as `:before-id` for the next page; listing does not load artifact values.
`(def page (artifact-page "id" {:limit 4096}))` reads retained content without
rerunning the original operation. While `(:cursor page)` is non-nil, continue with
`(def page (artifact-page "id" {:after (:cursor page) :limit 4096}))`.
Artifact cursors are reusable, session/artifact-scoped positions; they survive restart
with retained content and are nil at EOF. They do not consume data or grant access.
Choose either `:after` or explicit 1-based `:offset`, never both. Text offsets count
UTF-16 characters; binary offsets count bytes. File reads keep 1-based line offsets.
Shell output beyond its hard retention cap cannot be recovered; truncation flags and
return documentation identify that limit.

These return shapes replace the former formatted string vectors; there is no
legacy mode. Update existing REPL code to select `:entries` or `:matches`.
Historical retained values keep their original data. The SQLite format is unchanged.

RPC clients can use:

- `result.list` and `result.inspect` for retained descriptors;
- `artifact.list`, `artifact.inspect`, and paged `artifact.read` for durable content;
- `session.entries` for all entries or the active path;
- `event.replay` to reconstruct recorded activity after a durable cursor.

For inline native values, `result.inspect` includes bounded `value-edn` and `value-truncated?` fields. Prefer `value-edn` when keyword keys, ratios, symbols, or other Clojure types matter; JSON `value` is only a projection.

## Session-backed agents

From the persistent REPL, start a child with its own conversation and evaluator.
Treat the following lines as separate evaluations, rather than one large output dump:

```clojure
(def child (agents/start! {:name "Parser"
                           :task "Investigate parser boundaries"
                           :context "Inspect and report evidence."}))
(agents/inspect child)
(def followup (agents/send! child "Please check the fallback branch."))
(agents/delivery followup)
(agents/wait {:receipts [followup] :until :completed :timeout-ms 30000})
;; Use the returned :ready operation handle with agents/result.
(agents/result child) ; still the original launch operation, not the follow-up
(agents/list)
(agents/messages)
(agents/cancel! child)
(agents/resume! child)
(agents/stop! child)
```

`start!` accepts one options map with required `:task`, optional `:name`,
`:context`, `:config`, and known-before-submit `:submission-id`. The returned handle
has `:session-id`, initial `:operation-id`, and `:submission-id`. Names are
unique within the root team. The child inherits selected parent configuration
once and gets a fresh evaluator; it never receives the parent's live Vars or
atom values. Subsequent addressed work uses the same child session and its
live bindings. A function job launched from an agent is still owned by its
own session; the foreground parent operation does not own accepted jobs or
children.

`(agents/submission submission-id)` retrieves the original spawn/send receipt,
or `nil` when no accepted submission matches that caller-owned ID. Generate
and retain a UUID before an uncertain mutation; reconcile it with this helper
instead of reissuing a launch or message under a new identity.

`send!` accepts a handle, same-team session ID/name, `:parent` or `:all`;
broadcast captures current recipients and excludes the sender. Content can be
text or supported native EDN up to 256 KiB, never a live object. Explicit
`{:result/ref {:session-id sender-id :id positive-result-id}}` copies a durable
inline value with source provenance; live-only and artifact-backed references
cannot be sent this way. An optional third map accepts `:submission-id` and
`:wake?`. A message is durably accepted before a receipt is returned; its
recipient's model history records sender and kind (`:peer`, `:human` or
`:completion`) at a safe boundary, atomically with the delivery acknowledgement.

Model-facing inspection is compact by default:

- `list` returns `{:root-id ... :agents [...] :total n :next-offset ...}`. Default
  page size is 8, maximum 20; pass `:offset` for the next page. Rows contain identity,
  model/provider, status/operation ID, routing flags and pending count—not history,
  configuration, usage breakdowns or provider payloads.
- `inspect` returns one compact row. Both calls accept `{:detailed? true}` when full
  configuration and diagnostics are explicitly wanted.
- `result` returns a particular operation's status, at most 1,200 answer characters,
  and a bounded error (240 message characters), with explicit clipping flags.
  `(agents/result handle {:detailed? true})` returns the original native outcome;
  `(agents/result target operation-id opts)` selects an explicit operation.
- `messages` returns `{:messages [...] :next-before ...}` newest first, default 8
  and maximum 20. Small supported content stays native (including ratios/maps);
  large content is explicitly omitted with a short preview. At most four recipient
  IDs appear in each summary, with count/omission information for larger broadcasts.
  Pass `:before` to page, or `:detailed? true` for full records on that page.

Compact `inspect` and `result` responses include `:next` source strings for full
diagnostics and relevant operation/result reads or managed waits. Operation links pin
the observed session/operation pair: a later follow-up cannot silently change which
result that link selects. They do not load the result or acknowledge delivery.

`(agents/delivery receipt)` resolves a send receipt or message ID to a recipient page:
delivery status, committed entry ID, incorporating operation ID and current operation
status. It does not consume the message. Options are `:offset`, `:limit` (8 by default,
maximum 20), and `:detailed? true` to read that one message's full native content.
Pending or superseded deliveries have no invented operation handle. Several messages
can join one operation; broadcast recipients can bind to different operations.
Each incorporated delivery row includes `:next :result` for that exact operation.
The page's `:next` offers full content, a managed wait when relevant, and the next page
when present; pending/superseded rows never acquire a guessed result link.

`wait` accepts `:handles` **or** `:receipts` (at most 20 selectors), plus `:timeout-ms`
(default/max 300000). Receipt waits use `:until :completed` by default, or
`:until :delivered` to obtain handles once input enters context. Returns include
`:reason` and up to 20 `:ready` operation handles; larger sets are marked
`:more-ready?`. Superseded deliveries return `:reason :superseded` and recipient
identities instead of waiting forever. Peer input and steering still interrupt a
wait, potentially before any handle is ready: return from the evaluation to process
that input rather than polling. Explicit self/cyclic operation waits are rejected.
Nothing is acknowledged by inspection or waiting, and timeout does not cancel work.

`cancel!` targets the specified operation or the target's current operation; `stop!`
stops a subtree and its session-owned jobs (optionally with `{:timeout-ms ...}`);
`resume!` unpauses pending eligible input. The RPC/TUI roster and diagnostic methods
remain rich projections for human inspection; the defaults above are the REPL API.

Peer delivery retains full supported content under the recipient's ownership and
places a bounded 1,200-character preview in model context. Live-only objects,
registry resources, and arbitrary closures are not portable.
`(agents/value target result-id)` reads supported durable EDN from a team
member's positive-integer result ID and rejects live-only descriptors; it does
not expose another session's live object. Messages have real sender provenance
and do not become privileged instructions. Every completed, failed, cancelled
or interrupted child operation produces one durable completion notice for its
launch parent. The parent sees a short status/answer preview and a locally
retained canonical assistant response (selected provider/model/usage/cost/finish
metadata); `agents/result` returns a compact summary unless full diagnostics are
explicitly requested. The parent's context does not copy the child's transcript or
opaque provider SDK records.
Use peer messages for blockers, interim findings and coordination. A child need not
send its final report separately: its final answer already arrives through the durable
completion route. Peer and completion records remain distinct; the harness does not
deduplicate their prose or suppress lifecycle outcomes.

Idle unpaused children can wake on direct messages. The unpaused root can wake for
child completion; ordinary REPL peer chatter does not wake it unless explicitly
marked `:wake? true`. Human messages sent through RPC/TUI default to waking an
unpaused recipient; specify `wake? false` to keep an idle recipient idle.
Cancelling a session pauses its automatic wake while independent work continues.
Restart pauses all team routing until explicit user run/continue or
`agents/resume!`; nothing replays an interrupted Clojure stack. Root stop
closes admission before stopping descendants, rather than permitting another
child to escape during cancellation.

Team routing, incorporating-operation links and submission receipts are schema-5 local state,
not an exportable running team. Fork/clone/import create independent roots and do
not launch children. Session export includes delivered content and retained local
references but not pending routes or executable ownership. Deleting a session with
linked descendants requires stopping/deleting the descendants first.

## Background jobs

Use ordinary Clojure functions in the session REPL:

```clojure
(def build
  (jobs/start! {:name "Run tests"}
    #(bash {:command "bun test"})))

(jobs/inspect build)                    ; compact status
(jobs/inspect build {:detailed? true})   ; provenance and diagnostics
(jobs/list {:limit 20})
(def page (jobs/output build {:limit 4096}))
(jobs/output build {:after (:cursor page)}) ; only text after that page
(jobs/output build {:tail? true :limit 4096}) ; latest retained characters
(jobs/wait build {:timeout-ms 1000})
(jobs/result build)
(jobs/cancel! build)
```

`start!` accepts a zero-argument function and optional `:name` (1–200 characters),
and returns `{:id ... :session-id ...}` immediately. Other functions accept that
handle, compact status map, or a job ID in the current session. `inspect`, `list`,
`wait`, and `cancel!` return compact native maps: ID, name, status, elapsed duration,
result ID/availability when present, and a short failure reason (240 characters,
with `:truncated? true` when clipped). Provenance, timestamps, full diagnostics, and
result descriptors stay available through `inspect`/`list`/`wait` with
`{:detailed? true}`. The value returned by `jobs/result` is unchanged.

Individual `jobs/inspect` responses also contain `:next`: output, retained result
inspection, and either successful `:value-and-ack` or active-work `:wait`/`:cancel!`
source strings. These use the real session-owned handle, not a guessed binding name.
Inspection and generic `(result id)` reads do not acknowledge the job outcome;
`jobs/result` explicitly does. Listing and waiting do not repeat these hints.

`list` is newest-first, with a maximum page size of 500; pass the last ID as `:before`
for older jobs. RPC job records and the UI continue to receive full metadata.

Jobs have their own worker, cancellation token and captured output. They do not
hold the foreground evaluation lock. The default runtime limit is 32 admitted jobs
(configurable through runtime `:settings {:job-limit n}`, bounded to 1–128); capacity rejection does not run
the supplied function. The function shares its session's live values and registered
functions: normal Clojure concurrency rules apply to atoms and Vars. Registered
calls retain their effect locks. Join any unmanaged futures before a job returns.

A function return completes a job; an exception fails it. `bash` and `powershell`
return nonzero `:exit-code` values normally, so check the exit code or throw in your
function when that should fail the job. A job is not a transaction: earlier file,
network, or shell effects survive later failure and cancellation.

`result` never blocks and returns only a successfully completed job's native value.
Use `inspect` for failures and availability, and detailed inspection for the retained
result descriptor; supported
values survive restart, arbitrary JVM objects do not. `wait` returns the current
record after at most `:timeout-ms` (default 1000, range 0–300000). Waiting does not
cancel execution. Waiting for oneself or an ancestor is rejected.

Output merges printed stdout/stderr and registered shell progress into a bounded
capture. `output` uses **zero-based character offsets**, unlike the one-based artifact
API. It returns `:text`, `:offset`, `:next-offset`, `:cursor`, `:more?`, `:eof?`, and
`:truncated?`. Pass `{:after (:cursor page)}` to read newly available text. Cursors are
explicit, reusable maps tied to the job; separate readers never consume one another's
output. An empty page while running keeps the same cursor and has `:eof? false`.
`:more? false` means caught up to the currently captured output, not completion;
`:eof? true` means the job is terminal and retained output is drained. Continue using
`jobs/output` after settlement; an exposed artifact ID does not require switching APIs
or translating offsets.
Cursors continue to work across settlement/restart when output was retained. Choose
only one of `:offset`, `:after`, or `:tail? true`; mismatched/out-of-range cursors are
rejected. `:tail? true` reads the last `:limit` characters of the **retained** capture,
not discarded output beyond its cap. Retained captures require their exact recorded
character count; missing metadata is rejected, and byte counts are not substituted.

Each job
retains at most 1,048,576 characters; text beyond that cap is discarded. Live output
is transient; settlement saves the capture as an immutable artifact. A crash can
lose the live capture, and an interrupted job reports it unavailable.

Lifecycle: queued → running → completed/failed; cancellation of queued work prevents
execution, while running work stays `:cancelling` until its worker and owned children
exit. Arbitrary Clojure code may ignore interruption, so cancellation can remain
pending. Requested cancellation is classified as `cancelled`, including in the retained
result descriptor. Detailed inspection preserves the original exception under
`:error :cause`; compact status does not describe cancellation as a failure. An
unrequested exception (even an interruption exception) still fails the job.
Children started inside a job are owned by that job; parents await children
before settling and cancel them on failure/cancellation. These are functions, not
subagents.

The foreground turn does not own accepted background jobs. Ending or cancelling it
leaves them running. Switching sessions, changing models, and compaction preserve
jobs. Reload, branch movement, deletion, and runtime shutdown cancel affected jobs
and wait for actual exit before closing their resources. If cleanup times out, the
runtime retains the evaluator/store and reports incomplete cleanup; retry after the
work exits. Restart marks unfinished jobs `:interrupted` and never replays them.

Completed outcomes are delivered once into model context at the next provider-step
boundary on the originating history path. Reading `jobs/result` acknowledges that
outcome. Idle sessions notify the UI without automatically starting a model call;
use `/continue` or send another message when desired. Branch movement prevents old
outcomes from being injected into the newly selected context. Job records remain
inspectable within the originating session; forks/imports do not recreate jobs.

`/jobs` uses the existing full-terminal browser and opens the execution inspector
directly. Jobs also appear as live execution rows in the conversation; they never
open a popup automatically. The inspector provides Output/Value/Code tabs, paging,
F5 refresh, End/Latest to read the retained output tail, and Ctrl+K cancellation.
Refreshing preserves the selected output page or tail view. The footer counts active background jobs. RPC clients use `job.list`,
`job.inspect`, `job.wait`, `job.cancel`, and `job.output`; start work through
`session.evaluate` using `jobs/start!`. `session.view` includes the newest job records,
and durable `job/changed` events reconcile status after reconnect.

This does not provide scheduled jobs, process-daemon supervision, automatic
retry, or JVM stack checkpointing. A `jobs/start!` child is a function job,
not an `agents/start!` session agent.

## What survives restart

Durable:

- session identity, name, configuration, and active branch;
- conversation entries and compaction records;
- operation/job records and durable events;
- queue state;
- supported inline results and artifact-backed results;
- team membership, message/delivery statuses, submission receipts, paused routing,
  and inspectable child operation outcomes (schema 5);
- exported files you explicitly write.

These records survive a normal current-format restart and supported schema upgrades.
Before upgrading a supported schema-3/4 layout, startup retains a consistent private
SQLite backup and commits schema changes transactionally. Artifact content remains
unchanged. Unsupported/newer, malformed and foreign stores are rejected intact,
never reset. Credentials/settings and unrelated files remain untouched. See the
[format contract](COMPATIBILITY.md) for supported layouts and backup recovery.

Live only:

- arbitrary evaluator definitions and bindings;
- unsupported JVM objects;
- unjoined futures and background threads;
- transient stdout, stderr, and progress updates beyond their bounded completion record.

Definitions survive ordinary messages, steering, model changes, and compaction while the evaluator stays alive. `/reload`, branch movement, `/reconnect`, process exit, and restart reset the evaluator. A live-only result then appears unavailable rather than pretending it was persisted.

## Refresh, reload, and reconnect

Use the least destructive recovery action:

- `/refresh` rereads authoritative session state. It keeps the current evaluator and never resends a mutation.
- `/reload` resets the evaluator and reloads resources. Durable history remains; live definitions are discarded.
- `/reconnect` restarts the owned core and reconnects the TUI. It also loses live definitions and never repeats an interrupted mutation.

If the UI reports an unknown outcome, use `/refresh` and inspect history or the current operation before retrying. A response timeout does not prove that the core rejected the request.

## Export and sharing

`/copy` copies the visible conversation. `/export` writes a local HTML file and does not upload it.

The RPC `session.export` method supports `html`, `jsonl`, and `edn`; `session.import` accepts the versioned Arrodes representation and creates new identities for imported data.

`session.share` is separate and explicit. It invokes GitHub CLI to create an **unlisted GitHub gist** containing an HTML export. Anyone with the resulting URL can read it; an unlisted gist is not private, access-controlled storage. Nothing is shared automatically.

## Store ownership

Each file-backed data directory has one live runtime owner. Multiple sessions in that runtime can operate, and different projects can run concurrently because their stores differ. A second Arrodes process opening the same project/store receives `store-in-use`. Close the existing process; do not delete the database or its ownership files.
