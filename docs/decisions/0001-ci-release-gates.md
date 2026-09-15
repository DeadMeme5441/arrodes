# 0001: CI and release gates

Status: accepted, 2026-09-15

## Context

The initial pipeline runs the full four-platform test/build matrix for every push and PR,
including documentation changes. It has no dependency cache, builds an unused SDK artifact
on every runner, and merges repeated notice files from platform artifacts. Executable checksums
establish byte consistency but do not bind a release to its intended source revision.

## Decision

Keep macOS/Linux arm64/x64 support and per-platform tests plus installed executable smoke.
Run one fast preflight before platform jobs, skip native jobs only for documentation-only
changes, and trigger branch work through PRs rather than duplicate branch pushes. Cancel
superseded PR runs; never cancel an in-flight version-tag release automatically. Cache pinned
dependencies, pin action revisions, and have Dependabot propose reviewed action updates.

Build the headless SDK once and upload it. Upload target-specific binaries, checksums and
manifests without overlapping filenames. Corresponding sources own the common release notices.
A single aggregate gate records whether all applicable jobs succeeded. Draft staging requires
all release jobs and matching version/commit/target manifests.

## Consequences and deferred work

Docs-only PRs are inexpensive while changes to workflows, scripts, dependencies or runtime
still exercise all targets. Full builds retain real platform/process coverage. The bundled
JDK module set remains unchanged: shrinking it needs separate runtime coverage, not a cost-only
edit. Source archives remain pinned by revisions/available hashes; expanding independent
source-digest verification is separate supply-chain work. No new distribution channels are added.
