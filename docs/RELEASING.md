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

The embedded runtime payload is explicitly serialized to gzip bytes before writing;
passing `Bun.Archive` directly to `Bun.write` would bypass its compression settings.
Native SQLite/JNA dependencies in each executable are limited to its target OS/CPU.
The Java module set, provider implementations, parser assets and licenses are retained.
Standalone `clojure -T:build rpc` remains platform-neutral; release builds pass
`:platform` and `:arch` to select native payloads. `bun run test:release` checks
payload compression/extraction and updater behavior without network access.

A human tries the applicable executable from that draft. If accepted, the same draft is
published with the same asset bytes. Publication does not rebuild the executables or run an
automatic smoke job. If the candidate fails, fix the source and create a new versioned
candidate; do not replace assets in the existing candidate.

The tag, version, platform, architecture, checksum, and draft assets identify the candidate.
Published release assets are immutable. Compatibility and rollback behavior follows
[the compatibility contract](COMPATIBILITY.md).

The optional `python3 scripts/verify-release.py PATH` diagnostic exercises the exact
candidate outside the checkout without developer runtimes on `PATH`. Its terminal
probe waits for reconciled provider state, applies inline model settings, and sends
the first message before evaluating in that session. Transient footer notices are
not completion signals. This diagnostic does not replace human candidate acceptance.

## Installed updates

`arrodes update` installs the latest published stable release for macOS or glibc
Linux on arm64/x64. `--check` reports availability without replacing the executable;
`--version X.Y.Z` selects a published stable version explicitly. Release tag, asset
name/URL, byte size, GitHub SHA-256 digest and checksum-file content must agree.
These checks use the official HTTPS release endpoints, not independent code signing.

HTTP-compressed responses are decoded by Fetch. Transfer `Content-Length` is
compared only for unencoded responses; decoded byte limits and published asset
sizes/checksums remain enforced for every response.

The updater accepts only an owned regular executable, not a symlink, hard link,
setuid/setgid file or development Bun process. It streams into a private file beside
that executable, syncs verified bytes, checks the original file has not changed,
and renames atomically while retaining executable permissions. Normal failure or
cancellation removes staging and leaves the original executable in place. An exclusive
`.EXECUTABLE.update.lock` prevents simultaneous replacement. After an uncatchable
termination, confirm no updater is running before removing its stale lock/staging.

Updates do not open the application home or store. A default update refuses a
downgrade; explicit older versions warn that binary replacement does not restore an
older data format. Store upgrade backups and unsupported-version rejection follow
the compatibility contract. Source development supports update help only; it must
never overwrite the Bun interpreter.
