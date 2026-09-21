# Current format contract

Arrodes supports its current contracts only. Do not add legacy readers, fallback
representations, automatic migrations, or downgrade adapters. A format change must
update its producers, consumers, documentation, and tests together.

## Persistence

The current SQLite schema is **3**. A fresh empty database is initialized directly
in schema 3 in one transaction. Existing databases must already have schema 3;
older, newer, or non-empty unversioned databases are rejected with
`unsupported-store-format` before any schema or journal changes.

There is no automatic conversion or deletion of incompatible data. Select a fresh
`--data-dir PATH` to begin a current-format history. Root-level settings, keybindings,
trust files, and implicit global history are unsupported home layouts; startup rejects
them without moving files. Current configuration belongs under `HOME/config/`, and
project histories default to `HOME/projects/<project>/data/`.

Current job records require explicit cancellation classification and the recorded
character count for retained output. Missing required data is rejected rather than
reconstructed through an older-record fallback. Supported native results survive
restart; arbitrary live JVM state does not.

## RPC and transfers

The current JSONL RPC protocol is 1. Job inspection remains rich over RPC, while
REPL status helpers return compact maps by default and detailed maps on request.
Output cursors are job-scoped and do not consume another reader's output.

Session export format 1 carries history, retained results and artifacts. It does not
transfer job ownership or executable functions. Forks/clones/imports never launch jobs;
completion-entry result references are remapped through the current retention contract.

## Verification

Verify fresh initialization, current-format reopen/restart, rejection of unsupported
versions and malformed records, and absence of effect replay. Rejection tests must
prove that existing data is left intact. No compatibility mode or migration fixture
should be added as a substitute for updating the current contract.
