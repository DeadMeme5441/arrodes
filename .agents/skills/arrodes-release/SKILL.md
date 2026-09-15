---
name: arrodes-release
description: Prepare and qualify an isolated Arrodes executable candidate, inspect CI artifacts, and perform separately authorized release operations.
---

Read [release qualification](../../../docs/RELEASING.md) and
[development](../../../docs/DEVELOPMENT.md). Resolve the exact source revision and intended
version. Check the working tree and use `python3 scripts/version.py check`.

For a local candidate, run `python3 scripts/dev.py preview`; use the printed launcher,
which runs the packaged binary outside the checkout with separate application state.
Exercise interactive startup, provider/model selection and a real prompt only when live
provider use is authorized. Fixtures and installed smoke establish different evidence.

For CI, require the aggregate verification gate and all four supported platform artifacts.
Validate manifest revision/version/target and hashes. Review compatibility evidence and
release notes. Do not declare an artifact tested if a later rebuild changed its hash.

Tagging, pushing and publication require authorization in the current task. Preparation
commands never publish. The tag workflow stages a draft release; making it public is a
separate action. Recover partial publication by inspecting existing assets and rerunning
only safe steps; never overwrite a published artifact or blindly repeat a version bump.

Report source revision, version, artifact hashes, completed/not-run checks, preview location
and publication state. See the release contract for the exact candidate acceptance checklist.
