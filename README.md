# Arrodes

Arrodes is a terminal coding agent built around a persistent Clojure REPL. The agent composes ordinary functions and retains native values between steps, while your conversation, results, and project configuration stay outside the repository.

Arrodes is open source under the [MIT license](LICENSE). It runs trusted local code with your permissions; it is not a sandbox. See the [security policy](SECURITY.md).

## Install

Install the latest release on macOS or glibc-based Linux:

```sh
curl -fsSL https://raw.githubusercontent.com/DeadMeme5441/arrodes/main/install.sh | sh
```

The installer detects your OS and architecture, verifies the SHA-256 checksum, and installs to `~/.local/bin/arrodes`. No sudo, language runtimes, or package managers required. Run the same command to update.

If `~/.local/bin` is not on your `PATH`, the installer prints the command to add it. Then run `arrodes` inside a project to begin setup.

Prefer to inspect scripts before running them? [Read the installer](install.sh), or use the manual downloads below.

### Manual download

Download the executable for your system and its matching `.sha256` file from [Releases](https://github.com/DeadMeme5441/arrodes/releases/latest).

| System | Executable |
| --- | --- |
| macOS Apple silicon | `arrodes-darwin-arm64` |
| macOS Intel | `arrodes-darwin-x64` |
| Linux arm64, glibc | `arrodes-linux-arm64` |
| Linux x64, glibc | `arrodes-linux-x64` |

The executable is self-contained. End users do not need to install a language runtime or package manager.

Windows support is experimental and is not included in this release.

#### macOS

Verify before renaming or moving the file:

```sh
shasum -a 256 -c arrodes-darwin-arm64.sha256
chmod +x arrodes-darwin-arm64
mkdir -p ~/.local/bin
mv arrodes-darwin-arm64 ~/.local/bin/arrodes
```

Use the `x64` filename on an Intel Mac. Ensure `~/.local/bin` is on `PATH`.

The macOS binaries are not Developer ID signed or notarized. After verifying the checksum, the first launch may require **System Settings → Privacy & Security → Open Anyway**. Only approve an artifact obtained from the release you trust.

#### Linux

```sh
sha256sum -c arrodes-linux-x64.sha256
chmod +x arrodes-linux-x64
mkdir -p ~/.local/bin
mv arrodes-linux-x64 ~/.local/bin/arrodes
```

Use the `arm64` filename on arm64. The preview targets glibc-based Linux systems.

Confirm the install:

```sh
arrodes --version
```

## First run

Open a repository and start Arrodes:

```sh
cd /path/to/project
arrodes
```

On first run, setup asks you to:

1. choose a provider;
2. sign in, enter an API key, or explicitly reuse available credentials;
3. choose a model returned by that provider;
4. choose a supported reasoning level; and
5. decide whether to trust project-scoped instructions and executable resources when a decision is needed.

OAuth can open a browser or show a URL for manual completion. API keys and pasted authorization codes are masked and excluded from conversation drafts and history. Press `Esc` to cancel a setup screen without exiting; run `/setup` or `/login` later to continue.

Codex supports ChatGPT sign-in. Anthropic access uses a Console API key or a supported cloud provider; Claude.ai subscription OAuth is not supported. GitHub Copilot login is not included. Each provider's account terms and data practices apply.

Existing valid defaults skip setup. Launch selections take precedence for a new session:

```sh
arrodes --provider codex-backend --model MODEL_ID --thinking high
```

Use `/models`, `/refresh-models`, and `/thinking` to change the current session from the interface.

## Daily use

Launch in the repository you want Arrodes to work on, then describe the change or question in the composer. A one-line starting prompt can also be supplied at launch:

```sh
arrodes "Explain the failing command, fix the cause, and verify the result."
```

Arrodes keeps one live evaluator per session. Function calls appear compactly in the conversation; select a row to inspect its summary, output, native value, or evaluation source. Pending follow-ups can be edited or removed before delivery.

### Keys

| Action | Key |
| --- | --- |
| Send while idle; steer while running | `Enter` |
| Insert a newline | `Shift+Enter` or `Ctrl+J` |
| Queue a follow-up | `Ctrl+Q` |
| Close a panel; otherwise request cancellation | `Esc` |
| Sessions | `F2` |
| Commands | `F3` or `Ctrl+P` |
| Move to the next pane | `F6` |
| Attach a project file | `@` |
| Discover commands | `/` |
| Scroll without following output | `PgUp` / `PgDn` |
| Inspect / expand a selected conversation row | `Enter` / `Space` |
| Follow the latest output | `End` |
| Inspector tabs | `1` Summary, `2` Output, `3` Value, `4` Code |
| Copy selected text; otherwise stop work | `Ctrl+C` |
| Exit | `Ctrl+D` |

### Commands

| Command | Purpose |
| --- | --- |
| `/new` | Create a session |
| `/sessions` | Switch sessions |
| `/history` | Inspect history and create a branch |
| `/refresh` | Reconcile recorded session state without repeating a mutation |
| `/pending` | Edit or drop queued input |
| `/attach`, `/attachments` | Add or remove project-file context |
| `/setup`, `/login` | Run provider setup or sign-in |
| `/models`, `/refresh-models`, `/thinking` | Select or refresh model settings |
| `/rename` | Rename the current session |
| `/continue` | Continue from the current conversation |
| `/compact` | Compact model context without deleting history |
| `/eval` | Evaluate trusted Clojure input in the live session |
| `/reload` | Reset the evaluator and reload resources; live definitions are lost |
| `/reconnect` | Restart the owned core without repeating an interrupted mutation |
| `/copy` | Copy the visible conversation |
| `/export` | Write a local HTML export |
| `/reasoning` | Show or hide reasoning |
| `/expand`, `/collapse` | Expand or collapse recorded activity |
| `/delete` | Permanently delete the current session after confirmation |
| `/help` | Show keyboard help |
| `/quit` | Exit Arrodes |

Prefix a message with `//` to send a literal leading slash.

## Sessions and results

Sessions are durable and can be reopened through `/sessions` or directly:

```sh
arrodes --session SESSION_ID
```

Conversation history, session configuration, stored artifacts, and supported retained values survive restart. Arbitrary live definitions, JVM objects, and live-only results do not. Branching changes conversation history; it does **not** undo edits, commands, or other filesystem effects. See [Sessions and results](docs/SESSIONS.md).

For automation and integrations, the only headless terminal mode is:

```sh
arrodes --rpc
```

It serves the versioned JSON Lines API documented in the [RPC protocol reference](docs/PROTOCOL.md).

## State, privacy, and trust

Application home resolves in this order: `--home`, `ARRODES_HOME`, then `~/.arrodes`.

```text
~/.arrodes/
  config/
    settings.edn
    keybindings.edn
    trust.edn
  auth/
  skills/
  extensions/
  prompts/
  themes/
  packages/
  projects/
    <readable-name>-<sha256-of-real-worktree-root>/
      project.edn
      settings.edn
      data/
      skills/ extensions/ prompts/ themes/ packages/
  cache/
    models/ jna/ bun/ opentui/
  runtime/
    <version>-<platform>-<arch>-<payload-digest>/
```

The project identity is the real Git worktree root, or the launch directory outside Git. Subdirectories and symlink aliases share a project bucket; separate worktrees do not. Arrodes does not create `.arrodes`, instructions, or ignore-file changes in the repository. Use `project.info` over RPC to discover the exact external directory.

Each project data store has one live owner. Different projects can run concurrently; a second process opening the same store is rejected. Use `--data-dir` only when deliberately selecting another store.

Trust allows project instructions and executable resources to load; it is not an operating-system sandbox. The evaluator, shell, MCP clients, and trusted extensions run with the Arrodes process's permissions. Credentials stay under `auth/` or in referenced environment variables, not settings files.

Nothing is shared automatically. The RPC `session.share` method explicitly creates an **unlisted GitHub gist**; anyone with its URL can read it.

See [Configuration and project state](docs/CONFIGURATION.md) and [Troubleshooting](docs/TROUBLESHOOTING.md) for migration, ownership, and recovery details.

## Documentation

- [Configuration and project state](docs/CONFIGURATION.md)
- [Sessions and results](docs/SESSIONS.md)
- [Extensions, resources, MCP, and packages](docs/EXTENSIONS.md)
- [RPC protocol reference](docs/PROTOCOL.md)
- [Troubleshooting and preview limits](docs/TROUBLESHOOTING.md)
- [Security policy and private vulnerability reporting](SECURITY.md)

## License

[MIT](LICENSE). Copyright 2026 DeadMeme5441.

Bundled third-party components retain their own licenses. Each release includes [third-party notices](THIRD_PARTY_NOTICES.txt) and a corresponding-source companion with the applicable source code and rebuild instructions.
