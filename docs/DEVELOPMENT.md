# Development

## Toolchains

Arrodes uses Bun 1.3.14, Clojure CLI, Python 3.12+, and Java 21. Install JavaScript
dependencies with `bun install --frozen-lockfile`.

`bun run dev` starts the TUI from the checkout. Local development uses tests relevant to
the change:

| Command | Coverage |
| --- | --- |
| `bun run test:core` | Clojure runtime and shared behavior |
| `bun run test:tui` | TUI controller, RPC interaction, and rendering |
| `bun run test:themes` | Theme pack parsing, preview, persistence and native repainting |
| `bun run test` | Both suites; the pull request CI command |

Tests use fixtures by default and must not require real accounts or paid model calls.
Optional diagnostics remain available for focused investigation:

- `python3 scripts/verify-rpc.py`
- `python3 scripts/verify-install.py`
- `python3 scripts/verify-release.py PATH`

These diagnostics are not a default local gate. `python3 scripts/version.py check` verifies
that application version mirrors match `package.json`; `set X.Y.Z` updates them together.

## Pull requests

Pull requests to `main` run one Linux job with `bun run test`, covering the core and TUI
suites once. There is no push pipeline for `main` and no repeated test run after merge.
The protected `main` branch requires a pull request and the **Core and TUI tests** check;
force-pushes and deletion are disabled. No second-person approval is required.
Release builds run only from version tags. See [decision 0002](decisions/0002-lightweight-development-and-release.md).

## TUI visual review

Set `ARRODES_CAPTURE_UI=target/theme-previews` while running `bun run test:themes` to
retain native terminal text/span captures for both built-in themes. The optional
`python3 scripts/render-tui-capture.py INPUT.json OUTPUT.png` helper renders the actual
cell colors and text for visual review; it requires Pillow and a monospace font.
Use `--font PATH` when Menlo or DejaVu Sans Mono is unavailable. These generated files
stay under `target/` and are not release assets.
