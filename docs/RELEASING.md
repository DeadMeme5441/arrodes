# Releases

`package.json` is the authoritative application version. `python3 scripts/version.py check`
checks its executable mirrors, and `python3 scripts/version.py set X.Y.Z` updates them.

A `vX.Y.Z` tag matching that version creates one release candidate. The tag workflow builds
each supported executable exactly once:

- macOS arm64
- macOS x64
- Linux arm64
- Linux x64

The candidate also contains a SHA-256 checksum for each executable, third-party notices,
and the required corresponding sources and checksum. The workflow attaches these files to
a draft release. It does not publish the draft.

A human tries the applicable executable from that draft. If accepted, the same draft is
published with the same asset bytes. Publication does not rebuild the executables or run an
automatic smoke job. If the candidate fails, fix the source and create a new versioned
candidate; do not replace assets in the existing candidate.

The tag, version, platform, architecture, checksum, and draft assets identify the candidate.
Published release assets are immutable. Compatibility and rollback behavior follows
[the compatibility contract](COMPATIBILITY.md).
