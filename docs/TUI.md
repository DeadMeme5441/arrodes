# Terminal interface contract

The interface is implemented in ClojureScript with OpenTUI on Bun. `tui-app` owns
controller lifecycle and action dispatch, and `tui-model` projects durable and transient
events. `tui-view` is the mount/render composition root. Feature modules under
`src/cljs/arrodes/tui/` own screens, models, transcript, inspection, input, and chrome;
`controller/` owns catalog, sessions, submission, and attachments. `tui-present` formats
activity and `tui-widgets` owns theme-aware visual primitives.

The module dependency direction is explicit: shared context and transport helpers sit
at the bottom; feature modules call them directly. The shell injects navigation actions
where direct peer imports would create cycles. UI nodes and the controller are not
recreated when a theme changes. See [theme packs](THEMES.md).

## Design and interaction

Use a restrained dark surface, legible neutral text, one focus color, and semantic status
colors. Selection and errors have a non-color signal. Shared component styles belong in
the widget layer. Ordinary conversation remains readable and execution remains inspectable.

- The composer preserves drafts on failed requests, navigation, and reconnect.
- Completing startup preserves screens and input already opened or entered by the
  user. The sessions browser stays open through loading and displays loading,
  empty, and failure states; late responses do not reopen a dismissed screen.
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

## Jobs

`/jobs` uses the full-terminal browser to list session-owned background work.
Enter opens the existing full-width execution inspector directly. F5 refreshes; an
older-page entry supports paging. Output
uses the inspector's page controls; native values use the usual Value tab. Job events
update status independently of foreground work, and the idle footer counts active jobs.
Escape closes job inspection before foreground cancellation. Cancelling a job preserves
the composer draft. Completed jobs and retained results remain inspectable after reconnect.

Jobs appear inline with other execution artifacts; neither launch nor completion
opens a popup. The inspector supplies F5 refresh and Ctrl+K cancellation.
