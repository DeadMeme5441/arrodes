# Troubleshooting and preview limits

## The executable will not start

### Checksum mismatch

Do not run the file. Download both the executable and matching `.sha256` asset again from the private release. Confirm that the platform and architecture names match and that the checksum is verified before renaming the executable.

### Permission denied on macOS or Linux

Make the downloaded file executable:

```sh
chmod +x arrodes-PLATFORM-ARCH
```

Then place it on `PATH` as `arrodes`.

### macOS says the developer cannot be verified

The private preview is not Developer ID signed or notarized. Verify the release checksum first, then use **System Settings → Privacy & Security → Open Anyway** if you trust the artifact. Do not bypass Gatekeeper for an unverified download.

### Windows SmartScreen appears

The private preview is unsigned. Compare `Get-FileHash -Algorithm SHA256` with the downloaded checksum, confirm the artifact came from the private release, then use the SmartScreen continuation only if you trust it.

### Unsupported platform

Use an artifact matching your operating system and processor: macOS arm64/x64, glibc Linux arm64/x64, or Windows x64. Other targets are not supported. When reporting a platform-specific problem, include `arrodes --version`, your operating system and terminal, and the exact error.

## Setup and provider errors

### Setup was cancelled

Run `/setup` or `/login`. Cancellation does not exit Arrodes. Authentication or settings writes completed before cancellation are not undone; setup inspects current state before continuing.

### Browser sign-in did not open

Choose manual continuation and open the displayed URL yourself. Paste the requested authorization result into the secret input. The value is masked and excluded from draft/history state.

### Provider credentials are unavailable

Run `/login` and select the provider again. Providers using ambient credentials must receive them through their documented environment; setup cannot create those credentials. API keys belong in the authentication flow, not `settings.edn`.

### No models were returned

Use `/refresh-models` after authentication. Setup performs model discovery only for the selected provider. An exact `--model` value must occur in that provider's discovered list; Arrodes does not silently accept an unknown model.

`setup.status` does not authenticate or change settings. It can read and cache public SDK model metadata, which is different from live provider discovery.

### A reasoning level is rejected

Choose `/thinking` and select one of the levels advertised for the current model. Supported names are model-dependent; a globally recognized name can still be unavailable for a particular model.

## Project and storage errors

### `store-in-use`

Another live runtime owns the same data directory. Close the other Arrodes process and retry. Do not delete `sessions.sqlite`, lock files, or artifacts. Different projects can run concurrently because they use separate default stores.

If you deliberately need another store, pass a distinct `--data-dir PATH`. This creates a separate history; it is not a shared-runtime mode.

### Legacy session history found

Arrodes found session history at legacy `HOME/data` and refused to assign it to the current project. The directory is unchanged. Open it explicitly:

```sh
arrodes --data-dir ~/.arrodes/data
```

Close any process already using that store. New sessions should normally use the project-specific default under `HOME/projects/.../data`.

### Global configuration migration conflict

Root-level `settings.edn`, `keybindings.edn`, and `trust.edn` move to `HOME/config/` only when the destination is free. If both copies exist, Arrodes preserves both and stops. Compare them, keep the intended map in `HOME/config/`, and archive the other copy outside the application home before retrying.

### Where is this project's state?

Arrodes intentionally creates no `.arrodes` directory in the repository. Call `project.info` over `arrodes --rpc`; its `directory` field is authoritative. Subdirectory and symlink launches resolve to the real Git worktree root.

## Trust and resource errors

### Project trust is required

Project settings and executable project resources require an exact-root trust decision. Use the setup prompt, launch with `--trust`, or call `project.trust` with `{"trusted?":true}`. Start a new session or `/reload` to activate the changed resource set.

Trust is permission to load local project code and context, not an OS sandbox. Inspect project resources before trusting them.

### A resource file was not found

Check the path and expected file type in `settings.edn`. Global relative resource paths resolve from application home; project relative paths resolve from the external project state directory. A configured directory must contain resources of the requested type. Use `{:path "..." :enabled? false}` to disable an optional path that may not exist.

### Malformed EDN

Settings, keybindings, trust, package manifests, and package indexes are EDN maps. Check balanced delimiters, keyword spelling, and quoted strings. Settings writes performed through `settings.update` roll back if reloading the new map fails.

### Project resources did not change after trust or settings update

Use `/reload` to reset the evaluator and reload the resource set, or start a new session. Reload preserves durable history but discards live definitions and live-only values.

### MCP server does not connect

MCP connections are lazy: they open when the `mcp` function first uses a configured server. Check the absolute stdio command or HTTP URL, environment references, and timeout. Arrodes is an MCP client, not an MCP server. Use `(mcp {:action "status"})` or reconnect that server through the `mcp` function.

If MCP cleanup is incomplete, retry reload or close. Arrodes may retain store ownership until owned resources have actually stopped.

## Session recovery

### The UI reports an unknown outcome

Do not immediately repeat the command. The core may have accepted a mutation before the response was lost. Use `/refresh`, inspect the current operation and history, then decide whether another action is needed.

### A live value or definition disappeared

Branch movement, `/reload`, `/reconnect`, process exit, and restart create a new evaluator. Arbitrary definitions and JVM objects are live-only. Durable inline and artifact-backed results remain available; inspect their descriptors rather than expecting a live numeric result ID to reference an old object.

### Branching did not restore files

This is expected. Branches select conversation history only. They do not undo edits, shell commands, or external effects. Restore files with version control or another explicit filesystem operation.

### Output is not following new activity

Press `End` to return to the latest conversation row. `PgUp` and `PgDn` intentionally leave follow mode so new output does not pull the viewport away from what you are reading.

### The terminal is difficult to use with mouse capture

Launch with `--no-mouse`. Keyboard navigation, commands, inspection, and text entry remain available.

## Privacy reminders

- Provider credentials belong under `HOME/auth/` or in the provider's environment, never settings or transcripts.
- Evaluations, shell commands, MCP servers, and trusted extensions run with the Arrodes process's permissions.
- Project trust is not sandboxing.
- `/export` writes locally and does not share.
- RPC `session.share` creates an unlisted GitHub gist. Anyone with the URL can read it.
