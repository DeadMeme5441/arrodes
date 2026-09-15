# Changelog

## Unreleased

- Add a provider-management browser, searchable model selection and explicit conversation/default settings.
- Refine the terminal conversation with a yellow/grey-on-black theme and expandable source/output.

- Add a maintained documentation map, agent workflows, and local candidate verification commands.
- Simplify CI to one Linux core/TUI test job per PR. Build each release target once, stage a draft, and publish the same artifacts.

## 0.1.1

Initial public release for macOS and glibc Linux on arm64 and x64, with a persistent
Clojure evaluator, durable sessions, terminal/RPC interfaces, supported authentication,
and checksum-verified installation.
