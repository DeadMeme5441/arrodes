---
name: arrodes-release
description: Prepare and inspect an Arrodes release candidate without rebuilding it.
---

Read [the release contract](../../../docs/RELEASING.md). Confirm a clean source commit,
the intended `vX.Y.Z` tag, and `python3 scripts/version.py check`. Use
`python3 scripts/version.py set X.Y.Z` when preparing the version, then review the diff.

The tag workflow builds the four supported executables once and stages them, their
checksums, notices, and corresponding sources in a draft release. Inspect that draft and
try the actual candidate executable for the current platform. Use
`python3 scripts/verify-install.py` or `python3 scripts/verify-release.py PATH` only when
those diagnostics help investigate the candidate.

Publish the existing draft only after human approval. Do not rebuild or replace candidate
assets during publication. Report the tag, tested artifact and checksum, manual result,
and draft or publication state.
