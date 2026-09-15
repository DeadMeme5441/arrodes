---
name: arrodes-tui
description: Change Arrodes terminal layouts and interactions, then verify controller behavior and actual native OpenTUI rendering.
---

Read [the TUI contract](../../../docs/TUI.md) and the relevant source components.
Use the user's current visual references and shared widget styles. Keep provider/runtime
work in the core/controller and rendering decisions in the view/presentation layer.

Define the affected states before implementation: idle/running, success/error/cancel,
focused/unfocused, long content, narrow viewport and navigation away/back as applicable.
Preserve draft ownership, selection, scroll anchors, queued-input reconciliation and secret
editor clearing. Use existing renderer and controller test helpers.

Run `bun scripts/test-tui.ts`. Inspect actual rendered frames; a green reducer test is not
visual verification. Reproduce with realistic synthetic content and test keyboard paths.
When the capture option exists, retain native cell captures in ignored build output; do
not check incidental captures into source. For packaged acceptance, use
`python3 scripts/dev.py preview` and test outside the checkout with separate application state.

Report the tested terminal sizes, interactions and any visual/manual gaps. Keep metadata
and version information honest; fixture models are not live service observations.
