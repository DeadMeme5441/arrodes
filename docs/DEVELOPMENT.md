# Development

## Toolchains

Use Bun 1.3.14, Clojure CLI, Python 3.12+ and Java 21. The release build requires a
complete matching-architecture JDK in `JAVA_HOME`; CI pins the release toolchain.
Install dependencies with `bun install --frozen-lockfile`. Source and packaged paths
must both remain usable.

For packaged smoke tooling, create an ignored virtual environment and install the
pinned requirements:

```sh
python3 -m venv target/verify-venv
target/verify-venv/bin/python -m pip install -r scripts/requirements-verify.txt
```

Use that interpreter for `scripts/dev.py package` or `preview`.

## Verification entry points

| Command | Scope |
| --- | --- |
| `python3 scripts/dev.py check` | Documentation links, skill metadata, versions, Python script tests, installer |
| `python3 scripts/dev.py test` | Check plus Clojure behavior, native TUI/controller and source RPC |
| `python3 scripts/dev.py package` | Build native executable, installed smoke, isolated preview launcher |
| `python3 scripts/dev.py preview` | Test plus package in one local workflow |

Set `ARRODES_TEST_OFFLINE=1` for fixture-only tests. No verification entry point commits,
pushes, tags or publishes. Reports in `target/verification/` identify source revision,
dirty state, completed checks and failed status. Skipping a command is not a passing test.

`target/preview/start` runs the self-contained binary outside the checkout, with a
fresh temporary home and synthetic project, and clears inherited credential variables.
The temporary state is removed on exit. This is environment isolation, not an OS/network
sandbox. Supply a different `--cwd` explicitly to test a real project, or `--home` to
deliberately retain preview state. Rebuild the
preview after source changes; a source checkout launch is `bun scripts/tui.ts`.

## Change workflow

Agree on acceptance criteria, implement on a short feature branch, verify affected
contracts, inspect the exact diff, and update documentation/Unreleased notes. Keep `main`
buildable. A PR should describe the resulting behavior and validation, with visual evidence
for UI changes. Existing task authorization determines which external steps to perform.

CI classifies documentation-only changes, runs a fast preflight, and verifies executable
changes across all four supported platform/architecture targets. Branch pushes do not
also duplicate PR pipelines. Dependency caches contain inputs, not trusted build outputs.
See [CI decisions](decisions/0001-ci-release-gates.md) and [releasing](RELEASING.md).
