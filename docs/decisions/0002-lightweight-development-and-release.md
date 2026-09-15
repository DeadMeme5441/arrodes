# 0002: Lightweight development and release

Status: accepted, 2026-09-15

## Context

The earlier workflow made routine changes pass through local orchestration, duplicated CI,
platform builds, packaged smoke checks, and extensive evidence collection. That cost did not
match a small project with human-reviewed releases.

## Decision

Develop with `bun run dev` and run relevant tests locally. Each pull request has one Linux
job running `bun run test`, which covers core and TUI tests. `main` has no push pipeline or
post-merge duplicate run.

A matching `vX.Y.Z` tag builds each macOS and Linux arm64/x64 executable once, packages
checksums, notices, and corresponding sources, and stages a draft release. A human tries
that candidate and publishes the draft unchanged. Publication performs no rebuild or
automatic smoke job. Focused diagnostic scripts remain optional.

## Consequences

Pull requests have one clear gate and releases preserve the exact bytes a human evaluated.
Platform-specific acceptance depends on the tag-built candidate and human release check.
This decision supersedes [0001](0001-ci-release-gates.md).
