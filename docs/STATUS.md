# Runtime and terminal verification

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

The repository-hardening checkpoint integrates the published `clojure-llm-sdk` **0.6.0** release and addresses the 35 end-to-end review findings. It also verifies that failed MCP cleanup propagates through resource teardown without releasing runtime store ownership. Six `gpt-5.6-sol` implementation workers followed Ponytail full-mode rules; `ponytail-review` guided worker and final integration simplification.

## Current verification evidence

| Check | Observed result |
| --- | --- |
| Offline behavioral suite | **92 tests, 403 assertions; zero failures/errors** |
| Provider/authentication boundaries | No credentials inherited by custom unauthenticated endpoints; streaming errors fail; refresh/logout/login interleavings, alias ownership, token retention, callback cleanup, discovery headers and catalog replacement pass |
| Durable data | Native reference collisions, imported pending calls, excluded labels, empty compaction ranges, set-contained secrets and deep retained values pass |
| Bounded artifact reads | A one-byte page from a **128 MiB** file-backed artifact succeeds with a **48 MiB** JVM heap; same-size corruption is rejected in that heap |
| Process ownership | Readiness-gated reparented workers are stopped; resistant MCP servers terminate across reconnect/close; a failed MCP cleanup retains the store lock until successful retry |
| TUI controller | Real isolated JVM startup/reconnect, delayed model/reload responses across navigation, delivered queues with late receipts and acknowledged saved drafts pass |
| Native renderer | Popup layout/scrolling, inspector selection ownership, session widgets, render/editor requests and cancelled active/return overlays pass |
| Actual PTY launcher | Reaches Idle; Ctrl+P opens the command palette; explicit evaluation returns **42**; Ctrl+D exits **0** and closes the owned core |
| Source and packaged RPC | Both pass **21 commands, 38 durable events**, including native nonfinite results, serialization-error reconciliation, extension presentation, reverse calls and cancellation/correlation |
| Legacy CLI and launcher | Source/packaged rename, quoting, EDN settings, reload and quit pass; external-directory launcher with spaces and relative cwd/home passes |
| Packaging | Core, RPC, CLI and production ClojureScript builds pass; the core JAR evaluates and runs a native shell without RPC, CLI or JLine on its classpath |
| Privacy guard | **75 text files** checked; no embedded machine paths or credentials |
| Static analysis | **30 production files, zero errors**; 56 stable-lock-accessor warnings remain unsuppressed |

The current UI checks use the actual OpenTUI renderer and a real JVM with a deterministic provider. Earlier interface verification also covered attachments, branch navigation without filesystem restoration, text selection, artifact Next/Prev inspection, and wide/narrow layouts. Live provider verification for this SDK upgrade is recorded separately below.

## Live OAuth proof with SDK 0.6.0

Both requested models were discovered through ChatGPT OAuth and run explicitly with `:provider :codex-backend`, `:thinking :high`, and fallback disabled. The workflows ran concurrently in separate sessions/projects within one runtime, sharing the SDK's in-process managed-auth coordination.

| Model | Provider responses | Recorded evaluations | Execution errors | Uncached input | Cached input | Output |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `gpt-5.6-sol` | 12 | 11 | **0** | 21,590 | 29,696 | 722 |
| `gpt-5.6-luna` | 12 | 11 | **0** | 21,599 | 30,720 | 805 |

Every assistant response recorded the selected model. Each agent read the installed Ponytail skill, used **read, edit, bash and an external MCP math server**, repaired its isolated program, executed it, and retained `verified` with answer **42**. An independent process printed **42** for each repaired program. A live follow-up used the existing REPL binding rather than redefining it.

The evaluation counts include the direct retained-value check. After restart, definitions were absent as specified and the durable result still returned **42**. Shutdown reported `:closed`, completed foreground work, terminated executors and no cleanup errors. Usage is provider-reported aggregate data from real work and continuation, not a cache benchmark or padded warmup.

These live checks exercised the runtime/SDK path; native TUI and PTY checks above separately exercise the host. Credentials and raw live transcripts are not committed.

## Earlier TUI live checkpoint

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
- Windows/PowerShell, Windows Job Objects, and the Linux native-launch ABI were not exercised locally. Their implementations and platform-specific checks are included; the cross-platform CI matrix has not been observed running for this change.
- Broad interaction verification used OpenTUI's actual captured renderer; the fresh PTY check specifically covered launch, palette, evaluation and clean exit, not every keyboard/terminal combination.
- Draft/navigation state is preserved within the running interface, not advertised as crash-persistent editor state.
- Arbitrary JVM values, definitions, unjoined futures and external effects are not durable checkpoints or an OS sandbox. Branching does not restore files.
- Live provider verification covers the two explicitly requested ChatGPT OAuth models, not every provider, model, account or region. Git/Maven package breadth is not exhaustively live-tested.

File-backed artifact paging uses bounded capture but intentionally verifies the full SHA-256 on every page, so it remains O(file size) in I/O. Development skills are installed locally, not vendored as application dependencies. The existing native platform and non-checkpoint/non-sandbox limits remain unchanged.
