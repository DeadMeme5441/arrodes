# Working on Arrodes

Arrodes is a terminal coding agent whose model composes ordinary Clojure functions
in a persistent session REPL. Preserve that execution model and its durable session
contracts. Start with [the documentation map](docs/README.md); load only the domains
needed for the task.

## Source map

- `src/clj/arrodes/runtime.clj`: operation ownership, model loop, queues and recovery.
- `src/clj/arrodes/repl.clj`, `capabilities.clj`: evaluation, function wrappers, native results.
- `src/clj/arrodes/store.clj`, `artifacts.clj`: transactional records and retained content.
- `src/clj/arrodes/provider*.clj`, `auth.clj`: provider boundary and credentials.
- `src/cljc/arrodes/`: shared pure session, context and presentation decisions.
- `src/cljs/arrodes/`: OpenTUI controller, rendering and RPC client.
- `hosts/rpc/arrodes/`: transport-independent commands, setup and JSONL host.
- `scripts/`: verification, packaging and release operations.

## Workflow routing

Project skills live in `.agents/skills/`. Read the applicable `SKILL.md`:

- [Change](.agents/skills/arrodes-change/SKILL.md): bounded implementation and handoff.
- [TUI](.agents/skills/arrodes-tui/SKILL.md): interface behavior and native visual verification.
- [Compatibility](.agents/skills/arrodes-compatibility/SKILL.md): session, config or RPC evolution.
- [Release](.agents/skills/arrodes-release/SKILL.md): isolated preview and release qualification.

## Operating rules

Keep user-requested discussion/read-only boundaries. User authorization in the task
controls commits, pushes, PRs, merges and publication; a skill is not authorization.
Use short feature branches for changes and keep `main` buildable. Preserve unrelated
work. Never hide a failed check by retrying until it passes; record the failure and
identify whether the cause was the implementation or the test/environment.

Source is authoritative for implementation; docs define intended contracts. If they
disagree, explain and resolve the discrepancy in the affected scope. Test completed
behavior, including cancellation, failure and reload where relevant. Model fixtures
must not use real accounts or paid completions by default.

## Commands and completion

- `python3 scripts/dev.py check`: structural and script checks.
- `python3 scripts/dev.py test`: check plus core, TUI and RPC suites.
- `python3 scripts/dev.py preview`: test, package, installed smoke, isolated launcher.
- `python3 scripts/dev.py package`: package and installed smoke after independent tests.
- `python3 scripts/version.py check`: verify version consistency.

See [development](docs/DEVELOPMENT.md) for prerequisites and
[release qualification](docs/RELEASING.md) for candidate evidence. Report what changed,
what was verified, failures/not-run checks, and the exact commit/artifact when built.
