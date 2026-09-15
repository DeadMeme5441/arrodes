# Release qualification

## Candidate identity

`package.json` is the authoritative application version. `python3 scripts/version.py check`
verifies executable mirrors. `python3 scripts/version.py set X.Y.Z` updates them together;
it does not commit or tag. Review the generated diff and finalize Unreleased notes when
preparing a release. Version changes do not imply publication permission.

An executable candidate must have an exact source commit, version, platform/architecture,
SHA-256 checksum and build manifest. Record a dirty working tree honestly for local previews;
only clean, committed candidates qualify for release.

## Acceptance checklist

1. Full local verification and the CI aggregate gate pass.
2. macOS/Linux arm64/x64 executable manifests, checksums and installed smoke pass.
3. The preceding supported data/configuration fixtures still work, or tested migration
   and recovery behavior is documented. See [compatibility](COMPATIBILITY.md).
4. Interactive startup, provider/model selection and one real response are checked using
   an isolated packaged candidate when live-provider use is authorized. State any unrun
   live-provider check explicitly; fixture tests are not a substitute.
5. User-facing notes describe behavior, known limitations, migration and downgrade effects.
6. Notices and corresponding sources accompany a public binary release.

Do not equate `--help`, health checks or green intermediate jobs with a complete release.
The tested executable hash must be the uploaded executable hash.

## Publication and recovery

The version tag workflow builds and validates platform artifacts and corresponding source
materials, then stages an official-repository draft release. Only that job has release-write
permission. Publishing a draft is a separate authorized action. Normal main/PR builds never
create public releases.

Before a requested merge, inspect the PR diff and checks at the exact head revision. After
merge, identify the resulting main commit and its build artifacts. Local previews are under
ignored build output and do not replace the installed stable executable automatically.

If a release step fails, inspect which assets already exist. Existing published assets are
immutable: verify identity and bytes, and fail on a mismatch. Do not overwrite assets or
repeat a version bump to repair an incomplete publication. Retry the failed safe step after
its cause is resolved. Keep the preceding release available for executable rollback; data
rollback follows the documented compatibility contract.
