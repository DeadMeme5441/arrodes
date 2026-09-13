# Verified stabilization checkpoint

The current fixed stage is complete. **Full agreed Pi parity is not complete.** Implementation checkboxes record implemented code; the separate verification tasks record remaining acceptance checks. Final delivery tasks refer to the remaining overall project, not an uncommitted stabilization stage.

## Verification evidence

- Full core suite: **31 tests, 144 assertions, zero failures/errors**. No paid provider calls in the suite.
- New regressions: reversible built-in capability overrides, exact layer removal and ownership, failed extension activation/reload restoration, duplicate registration races, non-recursive REPL registration, alias/result helpers, explicit cache opt-out/settings, and actual local HTTP/SSE routing through two isolated provider views.
- Actual stdio RPC process: **16 commands**, cancellation/reused-ID checks, **15 durable events**, host capability roundtrip, strict JSONL stdout, stderr diagnostics, evaluator/tool registration, namespace reset, exit 0.
- Actual JLine terminal: startup, persistent evaluator forms and quoting, EDN settings, reload/reset, effort selection, help, declined unlisted sharing consent, quit and EOF passed. The redirected-input driver also passed after stabilization.
- Launcher: external invocation directory, spaces, relative `--cwd` and `--home`, explicit `--new --export`, all passed through both source and packaged paths.
- JVM package: `clojure -Srepro -T:build uber` succeeded. Actual JAR help/version and runtime/session export passed. Version **0.1.0**; local artifact `target/arrodes.jar`.
- Privacy/Git guard: **47 text files** passed; personal repository identity and commit authors passed. Destination repository was confirmed **private**. No new platform work was added during closeout; the configured CI matrix is not claimed to have passed.

## Previous live proof, unchanged

Authenticated ChatGPT discovery returned the exact `gpt-5.6-luna` model with `high` effort and a 272,000-token context window. A clean live coding workflow used **8 requests**, all on that route, with no tool errors: it repaired a Clojure program, executed it successfully, retained a REPL value, and returned **43** on continuation. Independent execution returned **42**.

The clean short workflow reported **zero cached input tokens**. An earlier diagnostic run reported real reuse, including **5,632 cached input tokens**, but also exposed a since-fixed scheduler bug; it is not a clean efficiency benchmark. A meaningful longer-session cache measurement remains open. No padding or warmup calls are used.

## Provider audit and remaining work

The pinned Pi baseline has **40 provider IDs**. **15 exact IDs are currently accounted for**: `amazon-bedrock`, `anthropic`, `cerebras`, `deepseek`, `github-copilot`, `google`, `google-vertex`, `groq`, `huggingface`, `mistral`, `openai`, `openai-codex`, `openrouter`, `together`, and `xai`. This is a configuration-surface audit, not a live test of all providers.

**25 exact provider mappings remain open**: `ant-ling`, `azure-openai-responses`, `baseten`, `cloudflare-ai-gateway`, `cloudflare-workers-ai`, `fireworks`, `kimi-coding`, `minimax`, `minimax-cn`, `moonshotai`, `moonshotai-cn`, `nvidia`, `opencode`, `opencode-go`, `qwen-token-plan`, `qwen-token-plan-cn`, `qwen-token-plan-individual`, `radius`, `vercel-ai-gateway`, `xiaomi`, `xiaomi-token-plan-ams`, `xiaomi-token-plan-cn`, `xiaomi-token-plan-sgp`, `zai`, and `zai-coding-cn`. Near-aliases are not counted as exact support; some gaps may be mappings over existing transports rather than new transports.

Remaining acceptance includes the comprehensive Pi feature matrix, retry/overflow recovery scenarios, actual print/JSON runs, remaining skills/prompts/themes and Git/Maven package sources, large-result/artifact portability boundaries, and broader terminal controls. Print/JSON code is implemented but its actual-mode verification remains unchecked below. Only the selected ChatGPT OAuth route has been live-verified. PowerShell and the cross-platform CI matrix are not claimed runtime-verified on other operating systems.

## Committed code stages

- `b5629cf`: durable runtime, core regressions, and repository documentation.
- `611d31e`: CLI entry point, verified stdio RPC, and portable RPC driver.
- `4cb8f07`: initial committed-status checkpoint.
- `56a195d`: reversible capability layers, safe REPL registration, explicit cache controls, and routing regressions.
- `cae573b`: verified terminal, launch-directory fixes, launcher regressions, and packaged JVM entry point.

All stages use `DeadMeme5441 <deadmeme5441@gmail.com>`. The repository remains private. This checklist and the session task ledger were reconciled at closeout; completed implementation is not being mistaken for completed parity verification.

## Reconciled checklist

### Repository

- [x] Create clean arrodes mono repository identity
- [x] Record approved scope and baseline contracts

### Durable sessions

- [x] Implement versioned durable session storage model
- [x] Implement session history branching and configuration
- [x] Implement session import export and lifecycle
- [x] Enforce exclusive ownership before session recovery
- [x] Preserve valid references when copying branches
- [x] Repair pending calls on selected history prefixes

### Execution

- [x] Implement provider registry authentication and streaming
- [x] Implement supervised agent continuation and recovery
- [x] Implement queues cancellation retries and compaction
- [x] Implement stable cache efficient request construction
- [x] Isolate provider configuration for each session
- [x] Await all foreground work during shutdown
- [x] Serialize session reload with foreground admission
- [x] Compact ordinary conversations before context overflow
- [x] Honor explicit provider cache configuration controls
- [ ] Complete missing supported Pi provider mappings

### Capabilities

- [x] Implement shared capability registry and invocation
- [x] Implement coding tools and bounded result handling
- [x] Implement persistent session evaluator and result access
- [x] Implement reversible capability replacement for extensions

### Resources

- [x] Implement settings trust and resource discovery
- [x] Implement Clojure extensions hooks and package lifecycle
- [x] Fix safe local package source naming
- [x] Protect existing directories during package installation
- [x] Prevent recursive package staging within sources

### Interfaces

- [x] Implement embedding API and stdio RPC
- [x] Implement print and JSON execution modes
- [x] Implement interactive terminal controller and customization
- [x] Convey diagnostic bindings into RPC workers
- [x] Preserve request ownership across cancellation reuse
- [x] Describe session shares as unlisted disclosures
- [x] Fix JLine completer compilation and terminal startup
- [x] Preserve launch directory semantics in source wrappers

### Verification

- [ ] Verify complete supported Pi feature matrix
- [x] Exercise coding workflow and durable restart recovery
- [x] Verify repository identity and private data isolation
- [x] Confirm repository remains private before delivery
- [x] Verify model catalog using ChatGPT OAuth
- [x] Verify live runs using Luna high effort
- [ ] Measure cached tokens during Luna continuation
- [x] Verify provider coverage against pinned Pi baseline
- [x] Verify isolated project provider routing
- [ ] Exercise retry and context overflow recovery
- [ ] Exercise print and JSON command modes
- [ ] Verify skills prompts themes and package sources
- [ ] Verify large results and portable artifact roundtrips
- [x] Verify evaluator aliases and registration replacement
- [x] Build and exercise packaged JVM executable
- [x] Run final regression and privacy checks

### Delivery

- [ ] Remove temporary verification files and processes
- [ ] Commit implementation with the approved personal identity
- [ ] Push verified main to the private repository
- [x] Document architecture usage and verification in repository
- [x] Create staged commits before final handoff

### Stage checkpoint

- [x] Reconcile todos with observed implementation state
- [x] Report current state after verified stage
- [x] Close verified stage without additional platform work
