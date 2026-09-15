---
name: arrodes-change
description: Implement a bounded Arrodes behavior change with contract-driven verification and an inspectable handoff.
---

Read the relevant contracts from [the documentation map](../../../docs/README.md).
Identify the trigger and expected behavior, affected ownership boundaries and acceptance
criteria. Preserve explicit user scope and existing unrelated work. Use a feature branch
unless the user selected another arrangement.

Trace the current implementation and existing tests before editing. A model action is REPL
evaluation; functions remain Clojure functions. Preserve durable/transient distinctions and
operation ownership. For UI changes also use the TUI workflow; for persisted/RPC changes
use the compatibility workflow.

Use `python3 scripts/dev.py check` for fast structure/script feedback and
`python3 scripts/dev.py test` for the full local integration baseline. Run focused tests
while iterating. Change tests when behavior changes, not merely to mirror implementation.
Update each affected contract at its owning document and add user-facing changes to
`CHANGELOG.md` under Unreleased.

Before a requested commit, inspect the exact staged diff and verify only scoped files are
included. Report changes, acceptance evidence, failed/not-run checks and remaining manual
work. Commits, pushes, PRs and publication follow the user's existing authorization.
