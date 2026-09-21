---
name: arrodes-compatibility
description: Change Arrodes session, configuration, result, or RPC formats safely.
---

Read [the current format contract](../../../docs/COMPATIBILITY.md) and the owning
contract. Only the current representation is supported: update its producers and
consumers together, without legacy readers, fallback shapes, or migrations.

Test fresh initialization, current-format restart, rejection of incompatible stores
and malformed records, and interrupted recovery without replaying effects. Rejection
must leave existing data intact. For RPC changes, test canonical frames and reconnect.
`python3 scripts/verify-rpc.py` is available for end-to-end protocol diagnostics.

Run the focused tests, update the owning docs and Unreleased notes, and document the
current required format and explicit rejection behavior in the handoff.
