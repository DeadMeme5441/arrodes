# Conversation-first OpenTUI host

The ClojureScript/OpenTUI interface is implemented on top of the headless REPL-first core. Conversation and composer are primary; evaluation is an inspectable execution mechanism, not the default screen. Pi/OMP remains an interaction-behaviour reference, not a functionality checklist.

## Implemented

- Flat roots: JVM core in `src/clj/arrodes`, portable logic and view projection in `src/cljc/arrodes`, OpenTUI host in `src/cljs/arrodes`, optional RPC and legacy CLI under `hosts`.
- Keyed transcript rows, streaming assistant text, compact observed function activity, consecutive-read grouping, inline recorded edits, and visible failures. No inferred working-tree view or automatic folding at completion.
- Persistent composer, idle send/running steer, separate follow-up queue, atomic pending edit/drop, prompt history, and file/image context attachments.
- Contextual Summary/Output/Value/Code inspector, native EDN representation, retained artifact paging and live/saved/unavailable lifetime indicators. Wide split layout and dedicated narrow inspector.
- Sessions, searchable commands, history inspection and explicit branching without filesystem restoration. Session navigation preserves drafts, attachments, expansion and reading position; native text selection takes priority over stop/clear shortcuts.
- Atomic `session.view` and `entry/committed` events, cursor-based hydration, active-path filtering, unknown-outcome reporting, reconnect and confirmed owned-process shutdown.
- Bun launcher as `bin/arrodes`; the old CLI is explicit as `bin/arrodes-cli`. The JVM still owns OAuth, providers, MCP clients, evaluation and persistence.

Arrodes remains an MCP **client**, not a server. No subagent system, workspace model, operating-system sandbox or durable JVM checkpointing was added.

## Current verification evidence

| Check | Observed result |
| --- | --- |
| Offline behavioral suite | **49 tests, 220 assertions; zero failures/errors** |
| Actual renderer with real JVM backend | Keyboard/mouse send, steer, follow-up, queue identity-preserving edit/drop, real read/edit/shell work, replay, draft retention, cancellation and reconnect |
| Reverse host interaction | Real trusted JVM extension requests input/confirmation; exact response, conservative default decline, cancellation without invented output and composer preservation |
| Navigation regressions | Keyboard sessions/history/branch, unchanged files after branch, scroll and draft restoration, native text selection, text attachment add/remove and visible evaluation failure |
| Popup keyboard scrolling | `bun run test:tui` passes native layout/paint checks at **120×40**, **78×24** and **78×16**: both arrow directions, all menu adapters, filtering, wrapped rows and a fresh short popup. Selection scrolling waits for measured bounds; rows are reused and the scroll extent includes overflowing descriptions. |
| Native values | Keyword/string key distinction survives the inspector's EDN representation; real large retained EDN supports Value-tab Next/Prev paging and exact return to original content |
| Responsive rendering | Actual OpenTUI captures at **120×40** and **78×24**, visually inspected; narrow inspector takes the available width rather than squeezing the transcript |
| Startup recovery | Invalid explicit session is reported; choosing a new valid session restores a usable connection |
| Startup/reconnect command ordering | Real JVM regression: commands submitted during startup/reconnect execute once after readiness; initialization UI replies do not deadlock; `store-in-use` remains visible after process exit; releasing the owner allows explicit recovery without replay or stale error banners. The rebuilt PTY launcher also reaches Idle against an isolated copy of saved sessions. |
| Process failure boundaries | Fragmented UTF-8, expired mutation marked unknown, later correlated response, malformed stream, missing executable, restart admission and confirmed SIGKILL of a child ignoring SIGTERM |
| Actual PTY launcher | `bin/arrodes` reaches Idle, F3 opens the palette, explicit Clojure input evaluates to `42`, Ctrl+D exits **0** and tears down the terminal/core |
| Source and packaged RPC | Both pass **16 commands, 32 durable events**, host roundtrip, cancellation/request-ID ownership, JSONL-only stdout, stderr diagnostics, registration and namespace reset |
| Legacy terminal and launcher | Redirected quoting/EDN/reload/quit smoke and external-directory launcher smoke with spaces and relative cwd/home pass |
| Build | Final ClojureScript frontend and headless core/RPC/CLI artifacts build; TUI bootstrap help runs through Bun/OpenTUI |
| Privacy guard | **70 text files** checked; no embedded machine paths or credentials |

The renderer probes use real OpenTUI input/layout/rendering and a real JVM runtime with a deterministic provider for offline effects. They are not a live-provider substitute. Temporary projects, servers and probe sources are not product components.

## Live proof through the TUI

- Authentication: **Codex OAuth**.
- Provider: **`:codex-backend`**.
- Model: **`gpt-5.6-luna`**, verified in every assistant response.
- Reasoning: **`:high`**; no fallback enabled.
- **11 live provider responses; 10 evaluations, zero evaluation errors.**
- Observed function kinds: **MCP, skill, read, edit and bash**.

The prompt was entered through the actual OpenTUI composer. The agent discovered functions, read the installed skill, called an external MCP add function, inspected and repaired `(+ 20 20)` to `(+ 20 22)`, executed the program and retained `verified` as `{:answer 42}`. A later direct evaluation recovered `42`; independent execution of the repaired program also printed `42`.

No credentials or raw live transcript are committed. This was not a cache-efficiency benchmark; no cache-hit claim is made.

## Explicit limits

- The exercised native platform is macOS arm64 with Bun **1.3.14**, OpenTUI **0.5.11**, ClojureScript **1.11.132**, and Java **21**.
- Windows/PowerShell and other terminal/OS combinations were not exercised. CI includes frozen dependency installation, frontend compilation and bootstrap help, but the cross-platform matrix has not been observed running for this change.
- Broad interaction verification used OpenTUI's actual captured renderer; the fresh PTY check specifically covered launch, palette, evaluation and clean exit, not every keyboard/terminal combination.
- Draft/navigation state is preserved within the running interface, not advertised as crash-persistent editor state.
- Arbitrary JVM values, definitions, unjoined futures and external effects are not durable checkpoints or an OS sandbox. Branching does not restore files.
- Historical provider/package breadth is not comprehensively verified by this UI work. Only the explicitly requested live OAuth/model route was exercised.

The earlier core checkpoint established session ownership/recovery, reversible extension registration, provider-view isolation and MCP stdio/HTTP client behaviour. Existing regressions remain in the suite; this checkpoint adds the actual terminal interface rather than another architecture scaffold.
