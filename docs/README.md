# Documentation map

Documents define behavior and contracts; project skills explain the development
workflow. Keep a fact in its owning document and link to it elsewhere.

| Question | Owner |
| --- | --- |
| What is Arrodes, and what is in scope? | [Product](PRODUCT.md) |
| How do the parts fit together? | [Architecture](ARCHITECTURE.md) |
| What persists, resets, branches, or recovers? | [Sessions](SESSIONS.md) |
| How are providers and model settings handled? | [Providers](PROVIDERS.md) |
| Where do settings and project resources live? | [Configuration](CONFIGURATION.md) |
| How do extensions, MCP, skills and packages work? | [Extensions](EXTENSIONS.md) |
| What is the integration contract? | [RPC](PROTOCOL.md) |
| What must the terminal interface preserve? | [TUI](TUI.md) |
| How do I develop and verify a change? | [Development](DEVELOPMENT.md), [Contributing](../CONTRIBUTING.md) |
| What makes an artifact ready to ship? | [Releasing](RELEASING.md) |
| Which compatibility decisions need recording? | [Compatibility](COMPATIBILITY.md) |
| Why were important decisions made? | [Decision records](decisions/README.md) |
| How are vulnerabilities and trust boundaries handled? | [Security](../SECURITY.md) |
| How do users recover from problems? | [Troubleshooting](TROUBLESHOOTING.md) |

The machine-readable [scope](../resources/arrodes/scope.edn) records product domains
and exclusions. [AGENTS.md](../AGENTS.md) routes agents to these contracts and the
four project workflow skills. Documentation changes should accompany behavior changes;
released behavior and proposed behavior must be clearly distinguished.
