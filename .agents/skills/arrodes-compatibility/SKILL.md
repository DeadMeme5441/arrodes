---
name: arrodes-compatibility
description: Evolve Arrodes session, configuration, result or RPC formats with explicit upgrade, rejection and recovery tests.
---

Read [compatibility](../../../docs/COMPATIBILITY.md) plus the owning session/configuration/
RPC contract. Identify the old and new representations and the supported reader/writer
combinations. Distinguish additive changes from migrations and intentional incompatibility.

Create a small synthetic prior-version fixture. Verify fresh state, upgrade, restart,
unsupported/ambiguous state and interrupted migration as relevant. Do not replay effects
to repair history. Preserve result references and truthful live-only availability.

For a migration, specify the transactional boundary, completion marker and recovery behavior.
For an RPC change, test canonical frames and reconnect behavior with an older supported
client shape. Keep original history when the contract requires it.

Run focused tests and the full local baseline. Update the owning docs and Unreleased notes;
add a decision record only for a consequential contract choice. State downgrade/restore
limits and remaining release upgrade checks in the handoff.
