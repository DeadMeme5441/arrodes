# Changelog

## Unreleased

- Resume automatic following when scrolling back to the bottom during a streamed reply, including when new text arrives before the next frame.

- Add session-owned background Clojure jobs with independent cancellation/output, owned child cleanup, retained native results, paged logs, and once-only completion delivery at model boundaries. Restart records interrupted work without replaying effects.
- Render jobs inline with existing execution artifacts, use the full-terminal `/jobs` browser and existing inspector, and add `job.*` RPC controls; preserve jobs across turns and cancel/await them before evaluator teardown.
- Upgrade SQLite stores from schema 1 to 2 transactionally. Existing history/results are preserved; older executables reject upgraded stores, so downgrading requires restoring a pre-upgrade data-directory backup. Job ownership is not copied by fork/import.

## 0.1.4

- Show compaction progress separately from assistant replies and recover once from explicit context-limit rejections without replaying completed REPL effects.
- Name new conversations from the first message, then refine the title through an owned background model call; preserve manual names and keep title usage separate from conversation context.
- Stabilize the transcript viewport during streaming, remove competing post-paint follow corrections, and add blank rows below the session title and above the composer.
- Fix the first `/sessions` invocation during startup being dismissed by empty-chat initialization; preserve early input and show session-list loading, empty and error states.
- Improve REPL function discovery with return contracts and focused help; return structured matches/listings with reusable paths and completeness; expose workspace docstrings, retained failure details and paged results/artifacts. Search/list and skill/prompt catalog shapes replace the former vectors without a legacy mode; existing stored results are unchanged.
- Preserve MCP schemas and structured content in discovery/results, attach server/tool provenance, and identify uncertain remote-call outcomes. Render native string evaluations as readable text.
- Use the latest completed provider call's reported usage for session context and automatic compaction. Include cached and cache-write input, honor totals without double-counting breakdowns, remove character-based estimates, and invalidate stale measurements after compaction. Existing session records and provider replay state remain compatible and unchanged.
- Keep internal compaction and branch-summary streams out of assistant replies, including partial summaries from failed or cancelled calls.

## 0.1.3

- Add data-only theme packs with semantic colors/text treatments, live preview/cancel, and a saved UI preference; include the silver/gold Arrodes theme and Dracula.
- Refactor the TUI into feature modules for screens, model controls, input, transcript, inspection, chrome, and controller responsibilities.
- Add theme-colored turn dividers, outlined execution artifacts, syntax-colored code boundaries, and bordered tables while keeping prose open and full width.
- Start the assistant turn before its first reasoning/tool activity so activity and final prose remain under the same speaker.
- Show each session's last recorded message timestamp in local time, independently of configuration changes.

## 0.1.2

- Simplify the header to the session name and distinguish user turns, assistant prose, code, and execution with spacing and restrained surfaces.
- Make reasoning and execution headings clickable to expand or collapse their blocks.
- Resume following when wheel scrolling reaches the transcript bottom and only show Jump to latest while content remains below.
- Move compact model/provider, project/branch, and reported context metadata below the composer; expire routine confirmations without an alert toolbar.
- Allow left/right arrows to cross model-browser columns at search and effort boundaries.
- Put effort and apply controls directly in the model listing screen, with responsive placement and consistent arrow/Tab navigation.
- Open an empty composer on launch and /new; create a session only on Send, and resume saved sessions only by explicit selection.
- Apply a default model to the current session too, including reasoning; validate both scopes before saving.
- Load models for the selected provider with cached results, provider-specific discovery, and inline connection/error status.
- Add a welcome screen with recent sessions, a bottom-anchored composer, and separated model/project metadata.
- Show searchable slash commands expanding upward above the composer and restore typing immediately after mouse reading or clicking the editor.
- Use full-screen navigation and inspection views instead of floating popups, combine reasoning and model application controls, and consolidate provider command aliases.
- Add a provider-management browser, searchable model selection and explicit conversation/default settings.
- Refine the terminal conversation with a yellow/grey-on-black theme and expandable source/output.
- Add a maintained documentation map, agent workflows, and local candidate verification commands.
- Simplify CI to one Linux core/TUI test job per PR. Build each release target once, stage a draft, and publish the same artifacts.

## 0.1.1

Initial public release for macOS and glibc Linux on arm64 and x64, with a persistent
Clojure evaluator, durable sessions, terminal/RPC interfaces, supported authentication,
and checksum-verified installation.
