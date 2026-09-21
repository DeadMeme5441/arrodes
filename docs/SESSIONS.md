# Sessions and results

A session is a durable conversation plus configuration, history branches, queued input, operations, and retained results. Each live session also has an evaluator namespace for composing coding functions and inspecting native values.

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

RPC integrations receive durable operation receipts from `session.run`, `session.continue`, and `session.compact`. Track them with `operation.inspect` or `operation.wait`; control them with the operation or session cancellation, steering, and follow-up methods.

## History and branches

`/history` shows the recorded history tree and can create a new branch from an entry. A branch selects another conversation path for future model context; all original entries remain stored.

**Branching is not filesystem undo.** It does not revert edits, shell commands, network calls, or any other external effect. Review or restore files with the appropriate version-control or filesystem tools.

Context compaction also does not delete the original history. It changes the active model context while preserving durable records for inspection and export.

Automatic compaction defaults to 85% of the selected model's context window and
uses the latest completed provider call's reported usage. Each completion replaces
the current context measurement; counts from previous calls are not added to it.
Usage includes uncached input, cache reads, cache writes, and output; a reported
total takes precedence. Reasoning and modality breakdowns are not added again.
Cumulative token spend is separate from the current context size.

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

Discover the compact function catalog with `(registered-tools {:brief? true})`,
then inspect one contract with `(registered-tools "grep")`. Contracts include
inputs, native return shapes, limits, errors and examples where useful. Calling
`registered-tools` without arguments returns the full catalog.

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

`(results {:limit 20})` lists references newest first. Pass its `:next-before-id`
as `:before-id` for the next page; listing does not load artifact values.
`(artifact-page "id" {:offset 1 :limit 4096})` reads retained content without
rerunning the original operation. Shell output beyond its hard retention cap
cannot be recovered; truncation flags and return documentation identify that limit.

These return shapes replace the former formatted string vectors; there is no
legacy mode. Update existing REPL code to select `:entries` or `:matches`.
Historical retained values keep their original data. The SQLite format is unchanged.

RPC clients can use:

- `result.list` and `result.inspect` for retained descriptors;
- `artifact.list`, `artifact.inspect`, and paged `artifact.read` for durable content;
- `session.entries` for all entries or the active path;
- `event.replay` to reconstruct recorded activity after a durable cursor.

For inline native values, `result.inspect` includes bounded `value-edn` and `value-truncated?` fields. Prefer `value-edn` when keyword keys, ratios, symbols, or other Clojure types matter; JSON `value` is only a projection.

## Background jobs

Use ordinary Clojure functions in the session REPL:

```clojure
(def build
  (jobs/start! {:name "Run tests"}
    #(bash {:command "bun test"})))

(jobs/inspect build)
(jobs/list {:limit 20})
(jobs/output build {:offset 0 :limit 4096})
(jobs/wait build {:timeout-ms 1000})
(jobs/result build)
(jobs/cancel! build)
```

`start!` accepts a zero-argument function and optional `:name` (1–200 characters),
and returns `{:id ... :session-id ...}` immediately. Other functions accept that
handle or a job ID in the current session. `list` is newest-first, with a maximum
page size of 500; pass the last ID as `:before` for older jobs.

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
Use `inspect` for failures, availability and the retained result descriptor; supported
values survive restart, arbitrary JVM objects do not. `wait` returns the current
record after at most `:timeout-ms` (default 1000, range 0–300000). Waiting does not
cancel execution. Waiting for oneself or an ancestor is rejected.

Output merges printed stdout/stderr and registered shell progress into a bounded
capture. `output` uses **zero-based character offsets**, unlike the one-based artifact
API. It returns `:text`, `:next-offset`, `:more?`, `:eof?`, and `:truncated?`. Each job
retains at most 1,048,576 characters; text beyond that cap is discarded. Live output
is transient; settlement saves the capture as an immutable artifact. A crash can
lose the live capture, and an interrupted job reports it unavailable.

Lifecycle: queued → running → completed/failed; cancellation of queued work prevents
execution, while running work stays `:cancelling` until its worker and owned children
exit. Arbitrary Clojure code may ignore interruption, so cancellation can remain
pending. Children started inside a job are owned by that job; parents await children
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
F5 refresh, and Ctrl+K cancellation. The footer counts active background jobs. RPC clients use `job.list`,
`job.inspect`, `job.wait`, `job.cancel`, and `job.output`; start work through
`session.evaluate` using `jobs/start!`. `session.view` includes the newest job records,
and durable `job/changed` events reconcile status after reconnect.

This does not provide subagents, scheduled jobs, process-daemon supervision, automatic
retry, or JVM stack checkpointing.

## What survives restart

Durable:

- session identity, name, configuration, and active branch;
- conversation entries and compaction records;
- operation/job records and durable events;
- queue state;
- supported inline results and artifact-backed results;
- exported files you explicitly write.

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
