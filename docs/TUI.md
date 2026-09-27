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

## Session agents

`/agents` or `F4` opens a full-terminal browser using the same screen shell as
`/sessions`, with the root and its children, current operation, pending messages,
cancelling, failed and off-context states. Opening from a child selects
that child's row. Up/Down moves the selected **control target** (`›`), while
`[current]` separately marks the transcript currently in focus. The header
shows `Agents · target <name>`; shortcuts and toolbar actions affect the
selected target, not implicitly the focused conversation. Typing filters,
`Tab` focuses the list, `Shift+Tab` returns to search, and `Enter` opens
the selected agent. A visible two-row toolbar provides New, Message,
Messages, Result, Cancel, Stop tree, Resume and Parent even at narrow
widths. `Alt+Left`, `/parent`, or the header **Parent** button returns
from a child. Navigation preserves its parent draft, scroll and selection.
Only the focused conversation hydrates its `session.view` transcript; the
roster never hydrates every child history.
The footer shows active/total agents separately from active function jobs and
a parent breadcrumb identifies the focused child. Context size remains
per-session, not a sum of child context windows. The roster shows measured
tokens per member and totals only known measurements across the team, marking
unmeasured calls; no measurement is represented as an exact zero. UI observation
(opening the browser or inspecting messages/results) does not initiate a
model request. The core may separately wake an unpaused root for an eligible
child completion.

The browser reconciles a roster snapshot/cursor with buffered
`agent/changed` and `agent/message` events. A timeout or disconnected mutation
is reconciled with its stable `submission-id` rather than automatically replayed.
Sending an addressed message uses its own full-terminal compose screen rather
than replacing the unsent conversation draft. In `/agents`, `Ctrl+N` launches
a named child with a task, `F7` composes a message, `F8` inspects messages,
`Ctrl+O` opens the latest native outcome, `Ctrl+K` cancels an operation,
`Ctrl+X` stops the subtree, `Ctrl+R` resumes, and `F5` refreshes. `Ctrl+M`
and `Ctrl+I` are not agent shortcuts: PTYs encode them as Enter and Tab.
The visible toolbar supplies mouse-accessible equivalents. Message composition,
outcomes and the mailbox use full-terminal screens, not floating panels. `Escape`
dismisses the current screen before ordinary foreground cancellation. Empty,
loading, error and narrow-terminal states retain keyboard access and readable
status. Delivered transcript entries distinguish HUMAN MESSAGE, AGENT MESSAGE
and AGENT RESULT. Host prompts identify the originating session and operation
when present; unsupported host kinds fail explicitly.

## Jobs

`/jobs` uses the full-terminal browser to list session-owned background work.
Enter opens the existing full-width execution inspector directly. F5 refreshes; an
older-page entry supports paging. Output
uses the inspector's page controls; native values use the usual Value tab. Job events
update status independently of foreground work, and the idle footer counts active jobs.
Escape closes job inspection before foreground cancellation. Cancelling a job preserves
the composer draft. Completed jobs and retained results remain inspectable after reconnect.

Jobs appear inline with other execution artifacts; neither launch nor completion
opens a popup. The inspector supplies F5 refresh, End/Latest for the retained output tail, and
Ctrl+K cancellation. Refresh preserves the current page or tail view.
