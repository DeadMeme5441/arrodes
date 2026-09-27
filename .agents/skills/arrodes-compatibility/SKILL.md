---
name: arrodes-compatibility
description: Change Arrodes session, configuration, result, or RPC formats safely.
---

Read [the current format contract](../../../docs/COMPATIBILITY.md) and the owning
contract. Keep public data contracts stable across application releases. Prefer
additive changes; when a schema change is required, validate supported historical
layouts and back up before transactional migration. Never reset incompatible data.

Test fresh initialization, current-format restart, supported upgrades with meaningful
retained history/results/artifacts, backup integrity, failure rollback and exclusive
ownership. Unsupported/newer/foreign/malformed stores, unsafe paths, corruption and
unrelated SQL/I/O failures must fail without deleting data. Interrupted legacy reset
markers must not resume destructive cleanup. Verify recovery without effect replay.
For RPC changes, test canonical frames and reconnect.
`python3 scripts/verify-rpc.py` is available for end-to-end protocol diagnostics.

Run the focused tests, update the owning docs and Unreleased notes, and document the
supported formats, upgrade/backup behavior and any unverified compatibility in the handoff.
