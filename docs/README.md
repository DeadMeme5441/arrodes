# Documentation map

Documents define product behavior and contracts. Project skills contain practical
development procedures. Keep each fact in its owning document and link to it elsewhere.

| Question | Owner |
| --- | --- |
| What is Arrodes, and what is in scope? | [Product](PRODUCT.md) |
| How do the parts fit together? | [Architecture](ARCHITECTURE.md) |
| What persists, resets, branches, or recovers? | [Sessions](SESSIONS.md) |
| How are providers and model settings handled? | [Providers](PROVIDERS.md) |
| Where do settings and project resources live? | [Configuration](CONFIGURATION.md) |
| How do extensions, MCP, skills, and packages work? | [Extensions](EXTENSIONS.md) |
| What is the integration contract? | [RPC](PROTOCOL.md) |
| What must the terminal interface preserve? | [TUI](TUI.md), [design system](TUI_DESIGN.md) |
| How does development and CI work? | [Development](DEVELOPMENT.md) |
| What is a releasable candidate? | [Releases](RELEASING.md) |
| What compatibility is supported? | [Compatibility](COMPATIBILITY.md) |
| Why were important decisions made? | [Decision records](decisions/README.md) |
| What are the trust boundaries? | [Security](../SECURITY.md) |
| How do users recover from problems? | [Troubleshooting](TROUBLESHOOTING.md) |

The machine-readable [scope](../resources/arrodes/scope.edn) records product domains and
exclusions. [AGENTS.md](../AGENTS.md) gives source orientation and routes work to the
project skills.
