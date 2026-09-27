# Current format contract

Durable contracts are versioned independently from application releases. Prefer
additive changes that preserve existing records and update producers/consumers
together. A required store change needs a supported, validated transactional
upgrade with a retained backup; unknown formats must fail without deleting data.
Downgrades never implicitly convert or reset a store.

## Persistence

The current SQLite schema is **5**, with Arrodes `application_id` `0x4152524f`.
Fresh stores initialize directly. Current stores reopen without conversion.
Supported older stores upgrade before normal runtime recovery:

- Schema 3: the complete eight-table layout used by Arrodes 0.1.5.
- Schema 4: that same base layout or the complete agent-table layout represented
  by the existing schema-4 fixtures. Unknown partial agent layouts are rejected.

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

## RPC and transfers

The current JSONL RPC protocol remains **1**. The additive `agent.*` methods do not
change framing. Job inspection remains rich over RPC, while REPL status helpers
return compact maps by default and detailed maps on request. Job-output cursors are
job-scoped and do not consume another reader's output. Artifact pages also accept
session/artifact-scoped `after` cursors while preserving explicit 1-based offsets;
their `cursor` is null at EOF. Workflow recipes and inspection `next` hints are
read-time guidance, not a new durable execution model. Schema 5, RPC framing 1 and
export format 1 are unchanged by these additions.

Session export format remains **1**; it carries history, retained results and
artifacts, including content of delivered agent messages. It does not transfer job
ownership, pending agent routes or executable functions. Forks/clones/imports never
launch jobs or reconstitute teams; completion-entry result references are remapped
through the current retention contract.

## Verification

Verify fresh initialization, current-format reopen, each supported historical
layout and its retained WAL-inclusive backup. Meaningful history/results/jobs
and artifact bytes must survive the upgrade. Inject migration failure and verify
rollback, then reopen successfully without replaying effects. Unsupported/newer,
malformed and foreign stores must remain unchanged. Exercise exclusive-owner,
artifact ownership, hard-link, unsafe-path and incomplete legacy-reset boundaries.
Configuration, export and RPC changes retain their existing public contracts or
introduce explicit version handling; an application version alone is not a data format.
