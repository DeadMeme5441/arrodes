# Contributing to Arrodes

Keep contributions focused and preserve behavior outside the agreed scope. Start with
[development](docs/DEVELOPMENT.md), [the documentation map](docs/README.md), and
[AGENTS.md](AGENTS.md).

Use `bun run dev` while developing. Run the tests relevant to the changed behavior;
the pull request runs the complete core and TUI suite with `bun run test`. Update the
owning contract and the Unreleased section of `CHANGELOG.md` when user-visible behavior
changes.

A pull request should describe the problem, resulting behavior, and validation. Generated
files should be regenerated through their owning tool. Releases follow
[the release contract](docs/RELEASING.md). Vulnerabilities follow
[the security policy](SECURITY.md).
