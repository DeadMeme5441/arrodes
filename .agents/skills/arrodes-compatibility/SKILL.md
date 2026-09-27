---
name: arrodes-compatibility
description: Change Arrodes session, configuration, result, or RPC formats safely.
---

Read [the current format contract](../../../docs/COMPATIBILITY.md) and the owning
contract. Only the current representation is supported: update its producers and
consumers together, without legacy readers, fallback shapes, or migrations.

Test fresh initialization, current-format restart, automatic recreation of incompatible
owned stores, exclusive-owner exclusion, and preservation of settings and unrelated
files. Unsafe paths, corruption, and unrelated SQL/I/O failures must not trigger deletion.
Keep malformed-record errors distinct from incompatible-format reset, and verify
interrupted recovery without replaying effects. For RPC changes, test canonical frames
and reconnect.
`python3 scripts/verify-rpc.py` is available for end-to-end protocol diagnostics.

Run the focused tests, update the owning docs and Unreleased notes, and document the
current required format and explicit destructive reset behavior in the handoff.
