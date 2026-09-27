# Current format contract

Arrodes supports its current contracts only. Do not add legacy readers,
fallback representations, migrations or downgrade adapters. A format change
updates its producers, consumers, documentation and tests together. An
incompatible **store format** triggers the destructive fresh-store reset below;
it is not read or converted.

## Persistence

The current SQLite schema is **5**. Fresh stores initialize schema 5 with
Arrodes `application_id` `0x4152524f`. Startup automatically resets a
**recognized Arrodes store** when schema validation reports
`unsupported-store-format`: an older/newer SQLite user version (including
schemas 3 and 4), non-empty unversioned history, missing schema-5 tables or columns, or
malformed agent routing/delivery state or operation associations. Marker
`0x4152524f` positively identifies Arrodes even for a newer schema.
Unmarked prior Arrodes stores are recognized only by their complete legacy
table/column signature; a foreign SQLite database with another marker or
no recognizable signature is rejected unchanged (`unrecognized-store`),
never mistaken for an older Arrodes store. The reset loses old sessions, history,
teams, jobs and retained results permanently. There is no legacy reader,
migration, conversion or implicit backup. Export needed history with a
compatible earlier build *before* starting this one.

The runtime holds the store lock throughout its lifetime and reset.
The artifact root's private `.arrodes-owner` marker binds it to the database;
a mismatched/shared root blocks reset. An unmarked prior root can be claimed
only when its hash-shaped files are all recorded by that database; ambiguous
extra files fail with `artifact-owner-unknown`, rather than being deleted.
The durable `db.reset` marker binds database/artifact paths and remains until
fresh schema initialization completes, so interrupted cleanup resumes safely
even if the old database is gone. Owned artifact cleanup and SQLite `-wal`/
`-shm` cleanup precede database deletion. Unknown neighbors, credentials,
settings and project resources remain intact. SQLite corruption, SQL or
permission errors, hard-linked database/sidecar files, untrusted symlinks
under writable user directories and path traversal fail without destructive
recovery. Root-owned system symlink prefixes may be used when their resolved
path passes ownership checks.

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

Verify fresh initialization/application ID, current-format restart, recognized
legacy signature, safe reset of incompatible Arrodes versions and malformed
routing/delivery status, and reset-marker recovery without effect replay.
Foreign SQLite, mismatched/shared or ambiguously unmarked artifact roots,
untrusted paths, hard-linked database/sidecar files, corruption and another
owner's lock must not be deleted. Tests must show credentials/settings and
unrelated neighbors survive. Malformed job records still fail their own
validation rather than wiping a compatible store. No compatibility reader
or migration fixture replaces this.
