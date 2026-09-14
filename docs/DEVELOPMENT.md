# Development and verification

## Prerequisites

Use Java 21+, Clojure CLI, and Bun 1.3.14+ for the TUI. JVM dependencies are pinned in `deps.edn`, including the provider SDK Git revision; OpenTUI 0.5.11 and its native dependencies are pinned by `package.json` and `bun.lock`. Do not use local-root dependencies pointing at another checkout.

```sh
clojure -Srepro -P
bun install --frozen-lockfile
bun run build
clojure -Srepro -M:test
```

The test runner discovers `*_test.clj` under `test/arrodes`. Tests use temporary directories and explicit completion fixtures; ordinary test runs must not make paid provider calls.

## Core checks

Meaningful regressions cover atomic queue delivery, branch/configuration projection, valid provider call/result pairing, no replay, ownership contention, truthful shutdown, joined concurrent function composition, namespace lifetime, nested event correlation, result remapping, trust/rollback, package preservation, and cache-prefix stability.

Do not replace those checks with source-text or mock-forwarding assertions. A capability test must check what its consumer actually receives, not only that a callback was invoked.

Portable code lives in `src/cljc/arrodes`; it must not require JVM effect namespaces. The `:cljs` alias runs the ClojureScript compiler. Shared model regressions cover canonical event insertion, active-path replay and observed activity projection. Actual renderer verification is separate from JVM/Node-only model checks.

## Live checks

Live verification is explicit and separate from the test suite. The current selected test route is:

- Authentication: ChatGPT OAuth.
- Provider: `:codex-backend`.
- Model: `gpt-5.6-luna`.
- Thinking effort: `:high`.

Discover models using `arrodes.provider/refresh!`, then use the exact returned model ID. Do not silently select a fallback. Never print tokens, raw credential files, or token exchange/refresh bodies.

Use a temporary project with a known faulty program. Let the agent inspect and repair it through REPL composition, then execute the corrected program independently. Verify retained evaluator state, followed by explicit loss of live state after restart. MCP verification uses Arrodes as the **client** of temporary external stdio/HTTP fixture servers, not a new product server.

Record requested model/effort, actual tool errors, provider-reported input/cached-input/output tokens, and explicit cache hit/miss/unknown. Do not pad prompts or issue warmup calls merely to force hits. A short successful session can legitimately have no cached input.

## Interfaces

The SDK calls the core directly. OpenTUI uses the optional stdio RPC host; the explicit legacy CLI dispatches the same session operations. Validate actual process framing, response correlation, host requests, cancellation, EOF/shutdown, and stderr separation. The frontend must consume these operations and events rather than duplicate orchestration.

Help/version paths must not open a runtime or perform provider/network work.

Run the actual-process drivers from the repository:

```sh
bun scripts/tui.ts --help
bun run test:tui
bin/arrodes --cwd /path/to/temporary/project
python3 scripts/verify-rpc.py
python3 scripts/verify-terminal.py
python3 scripts/verify-launcher.py
```

The legacy terminal driver uses redirected input; it does not replace an actual PTY check. The legacy launcher driver exercises `bin/arrodes-cli` before and after JAR creation. For OpenTUI, exercise keyboard/mouse input against the actual renderer and JVM backend: send/steer/follow-up, queue edit/drop, replay, session drafts and reading position, history/branch, selection, attachments, wide/narrow inspection, artifact pages, cancellation, reconnect and terminal restoration. A deterministic provider is suitable for offline effects; it is not a live-provider proof.

`test:tui` first exercises real JVM startup/reconnect, commands issued before readiness, initialization UI replies, store-ownership failures and recovery without replay. It then runs the native popup regression with seeded menu data: arrow-key visibility, wrapped descriptions, resizing and filtering. No model requests are made. The runner removes its temporary compiler output and isolated runtime data.

During startup or reconnect, TUI commands wait for the shared connection attempt before reading session state or sending RPC. Initialization UI replies bypass that wait. Failed startup preserves the original cause; successful reconnect clears stale connection errors. `/reconnect` explicitly starts a new connection, never replays a rejected mutation, and waits for the previous owned JVM to close. If the cause is `store-in-use`, close the other runtime; do not delete the database or its ownership file.

## Packaging

```sh
clojure -Srepro -T:build uber # headless core library
clojure -Srepro -T:build rpc  # RPC executable, no JLine
clojure -Srepro -T:build cli  # existing optional terminal executable
clojure -Srepro -M:tui-build # ClojureScript frontend; run with Bun bootstrap
java -jar target/arrodes-rpc.jar --help
java -jar target/arrodes-cli.jar --help
```

`bin/arrodes` runs `scripts/tui.ts`, rebuilding stale ClojureScript output and launching the source RPC host. The generated `target/tui/main.cjs` needs the bootstrap's OpenTUI ESM bridge; do not invoke it directly with Node. To use a packaged backend, set `ARRODES_TUI_RPC_COMMAND` to a JSON argv array such as `["java","-jar","/path/to/arrodes-rpc.jar"]`.

`bin/arrodes-cli` uses `target/arrodes-cli.jar` when present, otherwise the explicit `:run` classpath. The default core JAR is not a CLI executable. Launchers preserve the original launch directory for relative `--cwd` and `--home`. PowerShell entry points are included; Windows execution and native terminal behaviour require separate verification.

CI installs frozen Bun dependencies, compiles the frontend, exercises bootstrap help and runs the RPC lifecycle/native popup regressions in addition to JVM/legacy checks. It does not claim a full interactive PTY audit.

## Privacy and Git

```sh
python3 scripts/check-private.py --check-git
```

The repository check rejects embedded machine-user paths, common credential formats, and accidental private/Git files. The Git check also verifies the repository-local personal identity and origin. It is a guard, not a substitute for inspecting staged content.

Use repository-local identity and explicit staging paths. Do not copy global Git configuration, hooks, credentials, editor caches, runtime data, or reference checkouts. Never commit raw live transcripts or authentication material as verification evidence.

Commit coherent verified stages. Keep the remote private. Update [STATUS.md](STATUS.md) as checks pass; incomplete checks must remain explicit.
