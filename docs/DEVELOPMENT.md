# Development and verification

## Prerequisites

Use Java 21+ and Clojure CLI. Dependencies are pinned in `deps.edn`, including the provider SDK Git revision. Do not use local-root dependencies pointing at another checkout.

```sh
clojure -Srepro -P
clojure -Srepro -M:test
```

The test runner discovers `*_test.clj` under `test/arrodes`. Tests use temporary directories and explicit completion fixtures; ordinary test runs must not make paid provider calls.

## Module checks

Meaningful regressions cover observable boundaries: atomic queue delivery, branch/configuration projection, valid tool pairing, no replay, ownership contention, truthful shutdown, source-order parallel results, namespace lifetime, trust/rollback, package preservation, and cache-prefix stability.

Do not replace those checks with source-text or mock-forwarding assertions. A capability test must check what its consumer actually receives, not only that a callback was invoked.

## Live checks

Live verification is explicit and separate from the test suite. The current selected test route is:

- Authentication: ChatGPT OAuth.
- Provider: `:codex-backend`.
- Model: `gpt-5.6-luna`.
- Thinking effort: `:high`.

Discover models using `arrodes.provider/refresh!`, then use the exact returned model ID. Do not silently select a fallback. Never print tokens, raw credential files, or token exchange/refresh bodies.

Use a temporary project with a known failing program. Check the failure independently, let the agent repair it through registered capabilities, then run the program independently. Verify evaluator state across evaluations and continuation, followed by explicit loss of live state after restart.

Record requested model/effort, actual tool errors, provider-reported input/cached-input/output tokens, and explicit cache hit/miss/unknown. Do not pad prompts or issue warmup calls merely to force hits. A short successful session can legitimately have no cached input.

## Interfaces

The SDK and stdio RPC must use the same session commands. Validate actual process framing, response correlation, host requests, cancellation, EOF/shutdown, and stderr separation. Exercise the terminal itself; compilation or a plain fallback is not proof that interactive keybindings work.

The CLI help/version paths must not open a runtime or perform provider/network work.

## Packaging

```sh
clojure -Srepro -T:build uber
java -jar target/arrodes.jar --help
java -jar target/arrodes.jar --version
```

`bin/arrodes` uses the built JAR when present and otherwise invokes the source classpath. A PowerShell launcher is provided for Windows. The JAR is intended to need only Java at runtime; it must be exercised before release.

## Privacy and Git

```sh
python3 scripts/check-private.py --check-git
```

The repository check rejects embedded machine-user paths, common credential formats, and accidental private/Git files. The Git check also verifies the repository-local personal identity and origin. It is a guard, not a substitute for inspecting staged content.

Use repository-local identity and explicit staging paths. Do not copy global Git configuration, hooks, credentials, editor caches, runtime data, or reference checkouts. Never commit raw live transcripts or authentication material as verification evidence.

Commit coherent verified stages. Keep the remote private. Update [STATUS.md](STATUS.md) as checks pass; incomplete checks must remain explicit.
