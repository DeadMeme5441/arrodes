# Terminal interface contract

The interface is implemented in ClojureScript with OpenTUI on Bun. `tui-app` owns
controller state, `tui-model` projects durable and transient events, `tui-view` renders,
`tui-present` formats activity, and `tui-widgets` owns shared visual primitives.

## Design and interaction

Use a restrained dark surface, legible neutral text, one focus color, and semantic status
colors. Selection and errors have a non-color signal. Shared component styles belong in
the widget layer. Ordinary conversation remains readable and execution remains inspectable.

- The composer preserves drafts on failed requests, navigation, and reconnect.
- Enter sends while idle and steers while running; follow-ups are explicitly queued.
- Escape dismisses the current interaction before requesting operation cancellation.
- Focus is visible; every primary interaction has a keyboard path. Mouse reading does
  not capture ordinary typing; F6 explicitly enables keyboard pane navigation.
- Slash suggestions expand upward above the bottom-anchored composer and search names, aliases, and
  descriptions. Welcome, short conversation, and full conversation share one editor.
- Scrolling away from the bottom is not undone by new activity. Scrolling back to the
  bottom resumes following automatically and removes Jump to latest; the action appears
  only when content actually remains below the viewport.
- Selection stays visible in long menus and after terminal resize.
- Native values and evaluation source remain available through inspection.
- Failures display canonical status even when extension rendering fails.
- Secrets never enter visible unmasked output, draft state, or prompt history.

The current visual system is documented in [TUI design](TUI_DESIGN.md). The
[TUI skill](../.agents/skills/arrodes-tui/SKILL.md) describes the implementation workflow.
