# Security policy

## Supported versions

Security fixes target the current `0.1.x` release line, starting with `0.1.1`. The private `0.1.0` preview and unreleased development builds are not supported.

## Reporting a vulnerability

Report vulnerabilities privately through [GitHub private vulnerability reporting](https://github.com/DeadMeme5441/arrodes/security/advisories/new). Do not open a public issue before a fix or disclosure is coordinated.

Include:

- the affected Arrodes version, operating system, and architecture;
- the impact and the security boundary crossed;
- minimal steps or input needed to reproduce the problem; and
- any known mitigation.

Redact logs and examples. Never include passwords, API keys, OAuth tokens or codes, authorization redirect URLs, environment secrets, raw session databases, transcripts, exports, or artifacts. A small synthetic reproduction is preferred.

There is no guaranteed response or remediation SLA; timing depends on maintainer availability and the report's severity and completeness.

## Security boundaries

Arrodes is a trusted-local coding agent, not a sandbox. Its JVM/Clojure evaluator, shell commands, built-in coding functions, installed packages, trusted extensions, and local MCP server processes can act with the Arrodes process's operating-system permissions.

Project trust controls whether project-scoped settings, instructions, packages, and executable resources are loaded. It does not restrict the built-in execution capabilities or make a project safe to execute. Inspect a project before trusting it, and use an operating-system sandbox or isolated account when you need an isolation boundary.

Application home is selected by `--home`, then `ARRODES_HOME`, then `~/.arrodes`. Provider credentials are stored in `auth/credentials.edn` below that home; supported ambient credentials may also come from the process environment or an existing provider-managed credential store. Session history and retained artifacts are stored under `projects/.../data/`, or under the explicit `--data-dir`. Treat the entire application home and any custom data directory as sensitive local data. Arrodes does not add application-layer encryption to these files.

A model request sends its included prompts, conversation context, attachments, and tool or evaluation output to the selected provider. Provider account settings, terms, and data practices apply. Requests made to a configured MCP server send the requested operation and supplied data to that server; its operator and transport determine how that data is handled. MCP responses can be retained in the session or included in later model context.

Arrodes does not publish sessions automatically. The explicit RPC `session.share` operation uploads an HTML session export as an **unlisted GitHub gist**. Unlisted is not private: anyone with the URL can read it. Review the session before sharing it.

Provider and product names identify compatible services only. Compatibility does not imply affiliation, endorsement, partnership, or security certification. This policy describes known boundaries; it is not a security guarantee.

## Release integrity

Release binaries are not signed with an independently verified publisher identity; macOS binaries are not Developer ID signed or notarized. Download the executable and its matching `.sha256` asset from this repository's Releases page and verify the checksum before running or bypassing an operating-system warning. A matching checksum detects a mismatched or corrupted download; it is not an independent signature or proof of publisher identity.
