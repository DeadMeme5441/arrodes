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

## Context policy and inspection

`/context` opens the full-terminal **Session context** choices. Ordinary sessions
remain **Linear history** unless explicitly enabled. To opt in:

1. Use `/providers` for normal authentication and `/models` for the exact main model
   (for example `codex-backend/gpt-6.1-sol`).
2. Open `/context`, choose **Summarizer model**, and enter an exact model ID.
   The default is `gpt-6-luna`; Enter saves, Escape keeps the previous setting.
   This selects the summarizer only, not the main model or context policy.
3. Choose **Summary tree** and confirm. The confirmation names the selected
   summarizer/provider and explains possible background calls and separate cost.
   There is no model substitution or second authentication flow.
4. Send the next message normally. Required historical preparation is shown as
   **Preparing history…** before the main request; generated summaries are not
   streamed into the assistant's reply.

The summary provider defaults to the session provider. `/context` edits policy and
exact summary model; choose a different summary provider or byte/attempt/deadline
settings through [configuration](CONFIGURATION.md#summary-tree-context-opt-in) or
[`session.configure`](PROTOCOL.md#session-context), not an undocumented TUI picker.

On an empty, unsent composer, these choices update configuration for the first
message without creating a stored session or making provider calls. Inspection
labels it **unsent composer (no stored session)**. On a saved session, choices save
only that conversation's settings for the next safe outer-turn boundary; they
do not rewrite original history, restart the evaluator or interrupt current native
REPL/tool replay. A running request may still use its earlier policy/budget.
Selecting **Linear history** disables summary work at that boundary (or quiesces it
when idle); it does not delete the original conversation.

Choose **Inspect context and summary usage** for a read-only snapshot. It displays
configured policy and summarizer, summary-work status and failures, stored node
count, active-operation versus stored-history-preview policy, readiness/fit,
rendered UTF-8 bytes/budget, and required versus covered original source entries
for that projection. An incomplete bounded preview shows its reason and, when
applicable, the untrimmed available frontier's required bytes, not a guaranteed
size for all pending history; it never claims missing history is summarized.
Separate persisted completed-node summary usage and cost show measured fields and
measured-node counts, with unknowns explicit. Displayed failures are runtime
diagnostics; optional unpersisted attempt spend is exposed by
[`session.context`](PROTOCOL.md#session-context), not this screen or durable node
accounting.
Close and reopen to refresh. Opening this screen, navigating history or reopening
a session never generates summaries; only explicit runtime work enables catch-up.
An explicit later run can retry required failed preparation without replaying effects.

The historical evidence view is attributed data, not new instructions or an
automatic ranking of memories. `/history` continues to browse canonical recorded
entries. The assistant can use ordinary `history/view`, `history/zoom`,
`history/read` and `history/date` through the existing `repl`; expert users can
inspect with `/eval` and `(history/view)`. See [Sessions](SESSIONS.md) for original
record paging, retrieval receipts and retained-result navigation.

Context screens follow ordinary draft/navigation ownership: dismissal restores
the unsent draft and transcript position; late inspection/configuration responses
do not reopen a dismissed screen or overwrite another conversation's state. Failed
saves remain visible without clearing drafts. Escape dismisses the context screen
before requesting foreground cancellation. Cancellation is a request, not proof
that a worker has exited; completed history/results remain inspectable, and
continuation retains settled native evidence rather than repeating effects.


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

## Usage inspection

`/usage` or clicking the footer context indicator opens the existing full-terminal
inspection surface. It separates the latest ordinary request's context from cumulative
provider usage on the active history path, including compaction and branch summaries.
Uncached input, cache-read, cache-write and output counts are independent counters;
provider totals are not added to their breakdowns. Missing counters and prices are
shown as unknown, and partial totals report unmeasured requests. Known USD spend is
an estimate, not an invoice; title calls, summary-tree model work and inactive
branches are excluded. `/context` reports summary-tree accounting independently.
The footer's latest reported ordinary main usage is not the projected historical
view's byte size; tree compaction does not manufacture a replacement usage count.

At 120 columns or wider the footer also shows latest cache-read/write counts.
The full `/usage` surface remains available at narrow widths. Escape restores the
conversation without clearing its unsent draft or changing transcript scroll.
