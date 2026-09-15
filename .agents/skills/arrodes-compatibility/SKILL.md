---
name: arrodes-compatibility
description: Change Arrodes session, configuration, result, or RPC formats safely.
---

Read [compatibility](../../../docs/COMPATIBILITY.md) and the owning contract. Identify
the old and new representations and which reader/writer combinations remain supported.

Use a small synthetic prior-version fixture. Test the relevant fresh, upgrade, restart,
rejection, and interrupted-recovery paths. Preserve original history and result references;
never replay effects merely to repair retained state. For RPC work, test canonical frames
and reconnect behavior. `python3 scripts/verify-rpc.py` is available when an end-to-end RPC
diagnostic is useful.

Run the focused tests, update the owning docs and Unreleased notes, and record downgrade
or restore limits in the handoff.
