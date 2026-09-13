# Arrodes

A JVM/Clojure coding-agent harness built around durable sessions and a persistent Clojure evaluator.

**Status: verified stabilization checkpoint; full Pi parity remains open.** The durable core, live ChatGPT coding workflow, stdio RPC, terminal command paths, and packaged JVM launcher have passed their recorded checks. See [verification status](docs/STATUS.md) for exact coverage and outstanding work.

The repository is intentionally private.

## What Arrodes owns

- Append-only, branchable conversation history, session configuration, queues, operations, results, and retained artifacts.
- The model/tool continuation loop, cancellation, steering, follow-ups, retries, and context compaction.
- One capability implementation shared by provider tool calls and Clojure calls.
- A persistent evaluator namespace for each live session.
- Provider/authentication adapters, trusted Clojure extensions, skills, prompts, and package resources.
- SDK, stdio RPC, print/JSON, and terminal interfaces around the same session operations.

Arrodes is an agent harness, not a generalized workspace model. It does not include a built-in subagent system, MCP client, plan mode, to-do manager, or operating-system sandbox.

The functional reference is Pi's supported coding-agent surface at [`71dca871bc80`](https://github.com/earendil-works/pi/commit/71dca871bc80b6bc97be37f0ca3189399d651fff). This is a behavioral target, not TypeScript extension compatibility or a claim that every parity item is already verified.

## Requirements

- Java 21 or newer.
- Clojure CLI for development/source execution.
- Provider credentials for live model requests.

The provider layer uses a pinned revision of [clojure-llm-sdk](https://github.com/DeadMeme5441/clojure-llm-sdk). Session storage uses SQLite and immutable artifact files, not Datahike.

## SDK example

From the repository's Clojure classpath:

```clojure
(require '[arrodes.runtime :as runtime]
         '[arrodes.provider :as provider])

(def rt (runtime/open! {:cwd "/path/to/project"}))

;; Uses an available ChatGPT OAuth credential, without embedding it in a session.
(provider/refresh! (:provider rt) :codex-backend)

(def session
  (runtime/create-session!
   rt {:name "Project work"
       :config {:provider :codex-backend
                :model "gpt-5.6-luna"
                :thinking :high
                :tools :all}}))

(runtime/run! rt (:id session) "Explain the project." {})
(runtime/evaluate! rt (:id session) "(def retained 42) retained" {})
(runtime/evaluate! rt (:id session) "(inc retained)" {})

(runtime/close! rt)
```

Use `try`/`finally` to close runtimes in application code. `run!` is blocking; `start!` returns an operation receipt that can be inspected, waited for, or cancelled. See [architecture](docs/ARCHITECTURE.md) and [the protocol](docs/PROTOCOL.md).

## Command-line entry points

The help/version paths are verified:

```sh
clojure -Srepro -M:run --help
clojure -Srepro -M:run --version
```

The controller surface includes:

```sh
bin/arrodes --cwd /path/to/project --provider codex-backend --model gpt-5.6-luna --thinking high
bin/arrodes --cwd /path/to/project --print "Explain this project"
bin/arrodes --mode json --cwd /path/to/project "Explain this project"
bin/arrodes --headless
```

The interactive terminal uses JLine, with a line-oriented fallback for redirected input. Startup, evaluator input, settings, reload, model-effort selection, sharing consent, and clean exit have been exercised. [STATUS.md](docs/STATUS.md) records the current evidence for each interface.

## State and privacy

The default application home is `~/.arrodes-mono`, overridden by `ARRODES_HOME` or `--home`. Project resources live in `.arrodes-mono` within the selected project. Credentials belong in the private authentication store or environment, not session configuration.

A file-backed data directory has **one live runtime owner**. A second runtime opening the same directory is rejected before recovery or expiry can modify it. Multiple sessions can run inside one runtime. Use separate data directories for separate live runtimes.

Conversation history and supported durable results survive restart. Arbitrary REPL definitions, JVM objects, and live-only results do not. Branch movement/reload resets the evaluator; model changes and compaction do not.

The evaluator, shell, and trusted extensions execute local code with the process's permissions. Project trust is not a sandbox. Session sharing creates an **unlisted GitHub gist**: anyone with its URL can read it. Nothing is shared automatically.

## Development

```sh
clojure -Srepro -M:test
python3 scripts/check-private.py --check-git
clojure -Srepro -T:build uber
java -jar target/arrodes.jar --help
```

The test suite is offline; live checks are separate and use explicitly selected credentials/model settings. See [development and verification](docs/DEVELOPMENT.md), [extensions](docs/EXTENSIONS.md), and [current status](docs/STATUS.md).

## License

[MIT](LICENSE). Copyright 2026 DeadMeme5441.
