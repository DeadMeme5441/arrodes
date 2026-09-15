---
name: arrodes-change
description: Implement and verify a focused Arrodes behavior change.
---

Read the relevant contracts from [the documentation map](../../../docs/README.md), then
trace the current implementation and tests. Preserve the persistent REPL model, native
Clojure function results, operation ownership, and durable/transient boundaries.

Develop with `bun run dev`. Run the focused core or TUI tests that cover the change.
Use the TUI skill for interface work and the compatibility skill for persisted formats,
configuration, results, or RPC. Update the owning documentation and Unreleased notes when
behavior changes.

Before handoff, inspect the diff and report the changed behavior, tests run, and any
relevant verification left to the reviewer or release candidate.
