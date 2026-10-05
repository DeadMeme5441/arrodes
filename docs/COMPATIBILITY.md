# Current format contract

Durable contracts are versioned independently from application releases. Prefer
additive changes that preserve existing records and update producers/consumers
together. A required store change needs a supported, validated transactional
upgrade with a retained backup; unknown formats must fail without deleting data.
Downgrades never implicitly convert or reset a store.

## Persistence

The current SQLite schema is **6**, with Arrodes `application_id` `0x4152524f`.
Fresh stores initialize directly. Current stores reopen without conversion.
Supported older stores upgrade before normal runtime recovery:

- Schema 3: the complete eight-table layout used by Arrodes 0.1.5.
- Schema 4: that same base layout or the complete agent-table layout represented
  by the existing schema-4 fixtures. Unknown partial agent layouts are rejected.
- Schema 5: the complete session-backed agent layout preceding derived context nodes.

Before an upgrade, SQLite `VACUUM INTO` creates a consistent private backup,
including committed WAL content, beside the database:
`sessions.sqlite.schemaVERSION-UUID.backup`. The backup is synced and retained.
Schema changes commit in one transaction; failure rolls back rather than
recreating the store. Immutable artifact files are not rewritten or deleted.
History, configuration, results, jobs, queues and events retain their identities.
Recovery then marks unfinished work interrupted, without replaying effects.

Unsupported old/new versions, non-empty unversioned stores, malformed records,
foreign databases, corruption and unrelated SQL/I/O failures are rejected intact.
There is no destructive fresh-store fallback. A legacy `.reset` marker causes
`incomplete-legacy-reset`: inspect/recover the existing data before moving that
marker aside. Startup never resumes the earlier release's destructive cleanup.

The runtime retains exclusive database and artifact-root ownership. The private
`.arrodes-owner` marker binds artifacts to the database; mismatched/shared roots
and ambiguous unowned files fail rather than broadening access. Unsafe symlinks,
hard-linked database/sidecar files and path traversal are rejected. Root-owned
system symlink prefixes remain usable when their resolved paths pass ownership
checks. Credentials, settings and unrelated neighboring files are not migrated.

A backup contains sensitive session data and is not automatically removed.
Restoring it is an explicit offline recovery action after closing every runtime;
keep the matching artifact directory. Installing an older executable does not
restore that backup and does not guarantee the older program can read current data.

Root-level settings, keybindings, trust files and implicit global history
remain unsupported home layouts; startup rejects those layouts without
moving or deleting them. Current configuration belongs under
`HOME/config/`, and project histories default to
`HOME/projects/<project>/data/`.

Current job records require explicit cancellation classification and the recorded
character count for retained output. Missing required data is rejected rather than
reconstructed through an older-record fallback. Supported native results survive
restart; arbitrary live JVM state does not.

Schema 5 persists session-backed agent provenance, context-scoped peer deliveries
with their incorporating operations, submission receipts, pause/stop policy and operation completion notices. Restart
interrupts unfinished operations/jobs, preserves inspectable messages, and does not
autonomously resume a team or replay effects. Branch movement supersedes old-context
routes; compaction does not. Session-only fork/clone/import sever executable team
membership. A session export carries delivered content and locally retained references,
not active workers, pending team delivery or a recursive team transfer.

Schema 6 adds session-owned `context_nodes` for completed chronological derived
summaries. Canonical entries and retained result/artifact identities are unchanged.
Context policy is opt-in: absent or explicit `:linear` keeps ordinary behavior.
Derived nodes and advisory frontier IDs can survive local restart, but opening or
inspecting a store never starts paid inference. A valid local coarsened frontier
is reusable; invalid/missing/off-branch IDs are ignored. This cache is not a JVM
checkpoint or an independent canonical transcript. See
[session context](SESSIONS.md#opt-in-chronological-summary-tree).

## RPC and transfers

The current JSONL RPC protocol remains **1**. Additive `agent.*` methods and the
read-only `session.context` method do not change framing. Job inspection remains rich over RPC, while REPL status helpers
return compact maps by default and detailed maps on request. Job-output cursors are
job-scoped and do not consume another reader's output. Artifact pages also accept
session/artifact-scoped `after` cursors while preserving explicit 1-based offsets;
their `cursor` is null at EOF. Workflow recipes and inspection `next` hints are
read-time guidance, not a new durable execution model. Those helpers do not change
RPC framing or ordinary transfer shapes.

Ordinary session exports remain format **1**, carrying history, retained results
and artifacts, including delivered agent content. Export format **2** is emitted
only when typed history retrieval markers occur in retained entry/result/native
artifact data. Both versions are accepted; typed retrieval markers in version 1
are rejected rather than interpreted under the wrong contract. Version-2 typed
references are validated before import mutation.

Only explicit `:history/retrieval` and `:history/retrievals` markers receive typed
remapping; arbitrary user maps and historical prose are not reinterpreted as links.
Fork/clone/import remap session ownership and included context/source entry IDs,
including optional context-head and first/last/count spans. Nonportable operation
and call identities are removed from active references. Missing/external sources
become explicitly unavailable; original provenance remains inert under
`:source-reference`, never a live link into the source session. Result descriptors
use the existing session-local retention/remapping contract, not cross-session
native object handles. Original textual evidence remains preserved.

Derived tree nodes and advisory working-frontier IDs are omitted from transfers.
Transferred histories remain usable without that cache; later explicit enabled
work may rebuild it, but fork/clone/import do not launch summaries, jobs or teams.
Exports do not transfer job ownership, pending agent routes or executable functions;
completion-entry result references are remapped through the current retention
contract. Sessions remain independent roots rather than recursive team transfers.

## Verification

Verify fresh initialization, current-format reopen, each supported historical
layout and its retained WAL-inclusive backup. Meaningful history/results/jobs
and artifact bytes must survive the upgrade. Inject migration failure and verify
rollback, then reopen successfully without replaying effects. Unsupported/newer,
malformed and foreign stores must remain unchanged. Exercise exclusive-owner,
artifact ownership, hard-link, unsafe-path and incomplete legacy-reset boundaries.
Configuration, export and RPC changes retain their existing public contracts or
introduce explicit version handling; an application version alone is not a data format.
