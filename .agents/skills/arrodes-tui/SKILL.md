---
name: arrodes-tui
description: Change and verify Arrodes terminal interactions and rendering.
---

Read [the TUI contract](../../../docs/TUI.md) and the affected controller, presentation,
view, and widget code. Keep provider/runtime work in the core or controller and rendering
decisions in the view or presentation layer.

Implement with `bun run dev`. Exercise the affected interaction in a real terminal and run
`bun run test:tui`. Test the states that matter to the change, such as running, error,
cancellation, long content, narrow width, focus, scrolling, or navigation. Preserve draft
ownership, selection, scroll anchors, queued input, and secret clearing where applicable.

Report the interaction tested and any relevant terminal or rendering limitation.
