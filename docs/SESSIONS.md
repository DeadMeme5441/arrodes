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

RPC clients can use:

- `result.list` and `result.inspect` for retained descriptors;
- `artifact.list`, `artifact.inspect`, and paged `artifact.read` for durable content;
- `session.entries` for all entries or the active path;
- `event.replay` to reconstruct recorded activity after a durable cursor.

For inline native values, `result.inspect` includes bounded `value-edn` and `value-truncated?` fields. Prefer `value-edn` when keyword keys, ratios, symbols, or other Clojure types matter; JSON `value` is only a projection.

## What survives restart

Durable:

- session identity, name, configuration, and active branch;
- conversation entries and compaction records;
- operation records and durable events;
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
