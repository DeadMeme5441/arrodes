# Contributing to Arrodes

Discuss substantial product, UI, ownership or compatibility changes before implementing
them. Keep changes focused and preserve existing behavior outside the agreed scope.
Start with [development](docs/DEVELOPMENT.md), [the documentation map](docs/README.md),
and [AGENTS.md](AGENTS.md) for source orientation and workflow skills.

Use a short feature branch. A contribution should explain the problem, resulting behavior,
and how it was verified. Include actual rendered evidence for UI changes and synthetic
upgrade/recovery fixtures for format changes. Update affected contracts and Unreleased notes.

Run `python3 scripts/dev.py test` and the relevant packaged checks. Clearly distinguish
fixture tests from live-provider checks and record failed/not-run checks. Review every
agent-authored change before submission. Generated files should be regenerated through
their owning tool rather than hand-edited.

Publication follows [release qualification](docs/RELEASING.md). Vulnerabilities follow
[the security policy](SECURITY.md).
