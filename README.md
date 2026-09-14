# Arrodes

A conversation-first terminal coding agent, powered by a persistent JVM/Clojure evaluation environment.

The ClojureScript/OpenTUI interface keeps the conversation and composer primary. Observed function activity stays compact inline; output, native values, and exact evaluation source are available in a contextual inspector. The JVM remains usable independently through the SDK or JSONL RPC. See [verification status](docs/STATUS.md).

The repository is intentionally private.

## What Arrodes owns

- Append-only, branchable conversation history, session configuration, queues, operations, results, and retained artifacts.
- REPL-driven agent continuation, cancellation, steering, follow-ups, retries, and context compaction.
- Ordinary Clojure functions for coding work, skills, prompts, extensions, and an MCP **client**.
- One persistent namespace and native retained values per live session.
- Provider/authentication adapters; providers see one evaluation action, not every available function.
- A ClojureScript/OpenTUI interface, embedding API, optional stdio RPC, and an isolated legacy CLI.

Arrodes is not an MCP server or a generalized workspace model. It does not include a built-in subagent system, plan mode, to-do manager, or operating-system sandbox.

Pi/OMP is a reference for interaction behaviour, not a requirement to reproduce its functionality or implementation. The historical Pi baseline is [`71dca871bc80`](https://github.com/earendil-works/pi/commit/71dca871bc80b6bc97be37f0ca3189399d651fff).

## Requirements

- Java 21 or newer.
- Clojure CLI for development/source execution.
- Bun 1.3.14 or newer for the OpenTUI interface.
- Provider credentials for live model requests.

The provider layer uses the published [clojure-llm-sdk 0.6.0](https://github.com/DeadMeme5441/clojure-llm-sdk/releases/tag/v0.6.0) release. Session storage uses SQLite and immutable artifact files, not Datahike. JNA supplies the native process-group/Job Object boundary shared by shell commands and stdio MCP clients; it does not add an OS sandbox.

## Start the terminal interface

```sh
bun install --frozen-lockfile
bun run build
bin/arrodes --cwd /path/to/project
```

The launcher rebuilds changed ClojureScript sources, then starts OpenTUI and its owned JVM RPC process. It does not require a prebuilt core JAR. New sessions default to Codex OAuth, `gpt-5.6-luna`, and `high` reasoning, without silent model fallback. Use `--provider`, `--model`, and `--thinking` to choose another route; existing sessions retain their configuration.

| Interaction | Control |
| --- | --- |
| Send / steer the running operation | Enter |
| Queue a follow-up / insert a newline | Ctrl+Q / Shift+Enter |
| Dismiss selection or a panel, otherwise stop work | Esc |
| Sessions / command palette / next pane | F2 / F3 or Ctrl+P / F6 |
| Attach context / discover commands | `@` / `/` |
| Scroll without following new output | PgUp / PgDn |
| Inspect / expand the selected conversation row | Enter / Space with conversation focus |
| Copy selected text / exit | Ctrl+C / Ctrl+D |

Pending prompts can be edited or dropped before delivery. `/history` inspects the recorded path and can branch from an entry; branching never restores files. `/eval` opens explicit Clojure input without replacing the normal conversation workflow. `/refresh` reconciles state after an uncertain request outcome; it does not resend a mutation.

The inspector provides Summary, Output, Value, and Code tabs, with on-demand artifact pages and honest live/saved/unavailable result lifetimes. It appears beside the conversation on wide terminals and takes a dedicated view on narrow terminals. Drafts, expanded rows, and reading position survive session navigation within the running interface.

`bin/arrodes.ps1` is the PowerShell entry point. The old line-oriented/JLine interface remains available explicitly as `bin/arrodes-cli` or `bin/arrodes-cli.ps1`. Windows terminal behaviour has not been exercised in the current verification.

## SDK example

From the repository's Clojure classpath:

```clojure
(require '[arrodes.runtime :as runtime]
         '[arrodes.provider :as provider])

(let [rt (runtime/open! {:cwd "/path/to/project"})]
  (try
    ;; Uses available Codex/ChatGPT OAuth; credentials are not session data.
    (provider/refresh! (:provider rt) :codex-backend)
    (let [session (runtime/create-session!
                   rt {:name "Project work"
                       :config {:provider :codex-backend
                                :model "gpt-5.6-luna"
                                :thinking :high
                                :tools :all
                                :settings {:fallback-model? false}}})
          sid (:id session)]
      (runtime/run! rt sid "Inspect the project and explain it.")
      (runtime/evaluate! rt sid "(defn twice [x] (* 2 x))")
      (runtime/evaluate! rt sid "(twice 21)"))
    (finally (runtime/close! rt))))
```

`run!` is blocking; `start!` returns an operation receipt that can be inspected, waited for, or cancelled. `evaluate!` is a core operation, not an invocation of a special registered tool. See [architecture](docs/ARCHITECTURE.md) and [the protocol](docs/PROTOCOL.md).

Inside the session REPL:

```clojure
(registered-tools)                           ; callable symbols, schemas, descriptions
(def source (read {:path "src/example.clj"})) ; native value, retained in this namespace
(skill {:action "catalog"})
(:content (skill {:action "read" :name "review"}))
(mcp {:action "catalog"})                    ; configured external servers
(result 42)                                 ; native value for a returned result ID
```

Ordinary functions need no registration. `register-tool!` adds discovery and invocation tracing when needed; it does not expose another provider tool. The provider adapter encodes evaluation as `repl`.

## Source and host boundaries

```text
src/clj/arrodes/    JVM core and effects
src/cljc/arrodes/   Portable session, run, value, and TUI projection logic
src/cljs/arrodes/   OpenTUI view, controller, and JSONL client
hosts/rpc/arrodes/  Optional JSONL command host
hosts/cli/arrodes/  Existing CLI/JLine host
```

The default classpath has no CLI, RPC host, or JLine dependency. Start only the host you need:

```sh
clojure -Srepro -M:host       # standalone headless RPC, no JLine
clojure -Srepro -M:run --help # existing optional CLI
```

The OpenTUI host shares pure `.cljc` data logic. The JVM owns OAuth, MCP clients, evaluation, and persistence; JavaScript does not duplicate the agent loop.

## State and privacy

The default application home is `~/.arrodes`, overridden by `ARRODES_HOME` or `--home`. Project resources live in `.arrodes` within the selected project. Credentials belong in the private authentication store or environment, not session configuration.

A file-backed data directory has **one live runtime owner**. A second runtime opening the same directory is rejected before recovery or expiry can modify it. Multiple sessions can run inside one runtime. Use separate data directories for separate live runtimes.

Conversation history and supported durable results survive restart. Arbitrary REPL definitions, JVM objects, and live-only results do not. Branch movement/reload resets the evaluator; model changes and compaction do not.

The evaluator, shell, and trusted extensions execute local code with the process's permissions. Project trust is not a sandbox. Session sharing creates an **unlisted GitHub gist**: anyone with its URL can read it. Nothing is shared automatically.

## Development

```sh
clojure -Srepro -M:test
bun run build
bun scripts/tui.ts --help
python3 scripts/check-private.py --check-git
clojure -Srepro -T:build uber
clojure -Srepro -T:build rpc
java -jar target/arrodes-rpc.jar --help
```

`target/arrodes.jar` is the headless core library. `:build rpc` produces the standalone RPC executable. `:build cli` produces `target/arrodes-cli.jar`, used by `bin/arrodes-cli`; the core JAR is not a CLI executable. The TUI build produces `target/tui/main.cjs` and runs through the Bun bootstrap, not directly through Node.

The test suite is offline; live checks are separate and use explicitly selected credentials/model settings. See [development and verification](docs/DEVELOPMENT.md), [extensions](docs/EXTENSIONS.md), and [current status](docs/STATUS.md).

## License

[MIT](LICENSE). Copyright 2026 DeadMeme5441.
