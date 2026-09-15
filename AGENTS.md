# Working on Arrodes

Arrodes is a terminal coding agent whose model composes ordinary Clojure functions
in a persistent session REPL. Preserve that execution model and its durable session
contracts. Start with [the documentation map](docs/README.md) and read only the
documents relevant to the change.

## Source map

- `src/clj/arrodes/runtime.clj`: operation ownership, model loop, queues, and recovery.
- `src/clj/arrodes/repl.clj`, `capabilities.clj`: evaluation, function wrappers, native results.
- `src/clj/arrodes/store.clj`, `artifacts.clj`: transactional records and retained content.
- `src/clj/arrodes/provider*.clj`, `auth.clj`: provider boundary and credentials.
- `src/cljc/arrodes/`: shared pure session, context, and presentation decisions.
- `src/cljs/arrodes/tui_app.cljs`, `tui_view.cljs`: controller and view composition roots.
- `src/cljs/arrodes/tui/`: feature modules, local theme packs, and palette bindings.
- `src/cljs/arrodes/tui/controller/`: transport helpers, sessions, submission, catalogs, attachments.
- `src/cljc/arrodes/theme.cljc`, `resources/arrodes/themes/`: theme schema and built-in manifests.
- `src/cljs/arrodes/tui_rpc.cljs`: RPC client transport lifecycle.
- `hosts/rpc/arrodes/`: transport-independent commands, setup, and JSONL host.

## Project skills

- [Change](.agents/skills/arrodes-change/SKILL.md)
- [TUI](.agents/skills/arrodes-tui/SKILL.md)
- [Compatibility](.agents/skills/arrodes-compatibility/SKILL.md)
- [Release](.agents/skills/arrodes-release/SKILL.md)

## Working rules

Respect the user's scope and preserve unrelated work. Source is authoritative for
implemented behavior; docs define intended contracts. Resolve discrepancies in the
affected scope. Keep ordinary model tests offline and synthetic.

Develop with `bun run dev`. Run the tests relevant to the change. Before handing work
off, report what changed, what you tested, and anything still unverified. Use
`python3 scripts/version.py check` when changing or releasing a version.
