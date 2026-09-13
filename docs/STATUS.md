# Verification and integration status

This is an active build, not a completed release. Checked boxes record completed implementation or proof tasks; unchecked items remain part of the agreed scope.

## Evidence recorded so far

- Core regression suite: **23 tests, 105 assertions, zero failures/errors** in the last completed run. Rerun after later changes before committing or release.
- CLI help/version: passed; version is `0.1.0`.
- Authenticated ChatGPT model discovery: passed. `gpt-5.6-luna` advertises `high` effort and a 272,000-token context window.
- Clean live SDK coding workflow: **8 requests**, all `gpt-5.6-luna` / `high`, no tool errors. The agent repaired a failing Clojure program, ran it successfully, and reused a persistent REPL value in a later prompt. Independent execution confirmed output `42`; continuation returned `43`.
- The clean short workflow reported **zero cached input tokens**. An earlier diagnostic run demonstrated real cache reuse, including 5,632 cached input tokens on follow-up requests, but also exposed a scheduler bug and is not presented as a clean efficiency benchmark. Meaningful longer-session measurement remains open; no padding/warmup calls are used.
- Actual-process stdio RPC: passed initialization, 16 commands plus cancellation/reuse probes, host capability exchange, ownership checks, JSONL stdout, stderr diagnostics, evaluator output capture, registered REPL/tool invocation, reload/reset, and clean shutdown.
- Privacy/source check: passed before later edits; final staged-content and identity checks remain required.

## Immediate known issues

1. Interactive terminal startup fails while compiling the JLine `Completer` reification in `arrodes.terminal`. The terminal must be launched and exercised after repair.
2. Explicit cache controls must be preserved instead of overwritten by the default cache scope policy.
3. Deliberate extension capability replacement/restoration needs completion and a regression.
4. Provider parity, project-specific routing, remaining resource/package sources, output/result boundaries, print/JSON modes, and the JAR still need their listed checks.

No claim is made that every Pi provider has been live-tested. Live requests use only the explicitly selected ChatGPT OAuth route. No raw credentials, machine paths, or live session data are stored in this status file.

## Commit policy

Verified code is committed in coherent stages with the personal repository identity. The repository remains private. Status is updated with each verification stage; an intermediate commit is not a completed-release announcement.

## Checklist

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

- [ ] Implement provider registry authentication and streaming (in progress)
- [x] Implement supervised agent continuation and recovery
- [ ] Implement queues cancellation retries and compaction
- [x] Implement stable cache efficient request construction
- [ ] Isolate provider configuration for each session
- [x] Await all foreground work during shutdown
- [x] Serialize session reload with foreground admission
- [x] Compact ordinary conversations before context overflow
- [ ] Honor explicit provider cache configuration controls

### Capabilities

- [x] Implement shared capability registry and invocation
- [ ] Implement coding tools and bounded result handling
- [ ] Implement persistent session evaluator and result access
- [ ] Implement reversible capability replacement for extensions

### Resources

- [ ] Implement settings trust and resource discovery
- [ ] Implement Clojure extensions hooks and package lifecycle
- [x] Fix safe local package source naming
- [x] Protect existing directories during package installation
- [x] Prevent recursive package staging within sources

### Interfaces

- [x] Implement embedding API and stdio RPC
- [ ] Implement print and JSON execution modes
- [ ] Implement interactive terminal controller and customization
- [x] Convey diagnostic bindings into RPC workers
- [x] Preserve request ownership across cancellation reuse
- [ ] Describe session shares as unlisted disclosures
- [ ] Fix JLine completer compilation and terminal startup
- [ ] Preserve launch directory semantics in source wrappers

### Verification

- [ ] Verify complete supported Pi feature matrix
- [ ] Exercise coding workflow and durable restart recovery
- [ ] Verify repository identity and private data isolation
- [ ] Confirm repository remains private before delivery
- [x] Verify model catalog using ChatGPT OAuth
- [x] Verify live runs using Luna high effort
- [ ] Measure cached tokens during Luna continuation
- [ ] Verify provider coverage against pinned Pi baseline
- [ ] Verify isolated project provider routing
- [ ] Exercise retry and context overflow recovery
- [ ] Exercise print and JSON command modes
- [ ] Verify skills prompts themes and package sources
- [ ] Verify large results and portable artifact roundtrips
- [ ] Verify evaluator aliases and registration replacement
- [ ] Build and exercise packaged JVM executable
- [ ] Run final regression and privacy checks

### Delivery

- [ ] Remove temporary verification files and processes
- [ ] Commit implementation with the approved personal identity
- [ ] Push verified main to the private repository
- [ ] Document architecture usage and verification in repository
- [ ] Create staged commits before final handoff

