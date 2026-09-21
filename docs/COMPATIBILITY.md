# Compatibility and evolution

Persistent sessions, configuration and RPC consumers outlive an executable version.
Changes to them require an explicit compatibility decision in the feature scope.

## Before changing a format

Identify the current format/version and all readers/writers. State whether the change is
additive, requires migration, or intentionally rejects an older format. Capture a small
representative fixture from the preceding supported schema with synthetic data. Never
use a developer's real session history as a committed fixture.

## Evidence for format changes

Cover the cases relevant to the format being changed. Ordinary features do not need a
migration checklist.

- A fresh store/configuration works.
- The preceding supported representation opens or migrates as documented.
- Ambiguous/unsupported state produces an actionable error without destroying data.
- Migration failure preserves a usable prior state or supports documented recovery.
- Restart does not replay external effects.
- Retained values preserve identity/availability through branch, import and migration.
- RPC additive fields do not break existing clients; incompatible envelopes need an
  explicit protocol decision and tests.

Arrodes currently uses SQLite schema version 2. Opening a schema-1 store creates the
jobs table transactionally and preserves its existing sessions, entries, and result IDs.
Older executables (including v0.1.4) reject schema 2. Before upgrading, close Arrodes
and back up the project data directory (database plus artifacts); restore that whole
backup to return to an older executable. Do not edit `user_version` to downgrade.

RPC protocol 1 gains additive `job.*` methods and `session.view.state.jobs`. The session
export format remains version 1: exports contain history/results/artifacts, including
job completion entries already delivered to context, but do not transfer job ownership
or executable functions. Forks/clones/imports never launch jobs or copy live job handles;
completion-entry result references are remapped through the existing retention contract.
 There is no automatic migration of the
legacy shared store into a project's store. Do not describe that safeguard as a completed
upgrade experience; changes to it need a dedicated migration design and fixtures.

## Recovery and release notes

State whether the previous executable can reopen the data after upgrade. If a format
change prevents downgrade, document the backup/restore path before publication. Record
meaningful contract decisions in [decision records](decisions/README.md). Release notes
must describe migration and compatibility effects, not just code changes.
