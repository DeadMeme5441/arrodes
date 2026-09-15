# Terminal interface contract

The interface is implemented in ClojureScript with OpenTUI on Bun. `tui-app` owns
controller state, `tui-model` projects durable/transient events, `tui-view` renders,
`tui-present` formats activity, and `tui-widgets` owns shared visual primitives.

## Design and interaction

Use a restrained dark surface, legible neutral text, one focus/accent color, and semantic
status colors. Selection and errors must have a non-color signal. Shared component styles
belong in the widget layer. Keep ordinary conversation readable and execution inspectable.

- The composer preserves drafts on failed requests, navigation and reconnect.
- Enter sends while idle and steers while running; follow-ups are explicitly queued.
- Escape dismisses the current interaction before requesting operation cancellation.
- Focus is visible; every primary interaction has a keyboard path.
- Scrolling away from the bottom must not be undone by new activity.
- Selection stays visible in long menus and after terminal resize.
- Native values and evaluation source remain available through inspection.
- Failures display canonical status even when extension rendering fails.
- Secrets never enter visible unmasked output, draft state or prompt history.

## Changes and evidence

For a visual change, capture the actual OpenTUI rendering with representative content:
empty, running, completed, failed, cancelled, long output, long menus and narrow terminals.
Use fixtures for repeatability. Captures illustrate layout; they do not establish live
provider behavior. Test controller transitions independently and together with rendering.

The [TUI skill](../.agents/skills/arrodes-tui/SKILL.md) gives the implementation workflow.
