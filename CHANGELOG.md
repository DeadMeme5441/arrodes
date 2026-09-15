# Changelog

## Unreleased

## 0.1.3

- Add data-only theme packs with semantic colors/text treatments, live preview/cancel, and a saved UI preference; include the silver/gold Arrodes theme and Dracula.
- Refactor the TUI into feature modules for screens, model controls, input, transcript, inspection, chrome, and controller responsibilities.
- Add theme-colored turn dividers, outlined execution artifacts, syntax-colored code boundaries, and bordered tables while keeping prose open and full width.
- Start the assistant turn before its first reasoning/tool activity so activity and final prose remain under the same speaker.
- Show each session's last recorded message timestamp in local time, independently of configuration changes.

## 0.1.2

- Simplify the header to the session name and distinguish user turns, assistant prose, code, and execution with spacing and restrained surfaces.
- Make reasoning and execution headings clickable to expand or collapse their blocks.
- Resume following when wheel scrolling reaches the transcript bottom and only show Jump to latest while content remains below.
- Move compact model/provider, project/branch, and reported context metadata below the composer; expire routine confirmations without an alert toolbar.
- Allow left/right arrows to cross model-browser columns at search and effort boundaries.
- Put effort and apply controls directly in the model listing screen, with responsive placement and consistent arrow/Tab navigation.
- Open an empty composer on launch and /new; create a session only on Send, and resume saved sessions only by explicit selection.
- Apply a default model to the current session too, including reasoning; validate both scopes before saving.
- Load models for the selected provider with cached results, provider-specific discovery, and inline connection/error status.
- Add a welcome screen with recent sessions, a bottom-anchored composer, and separated model/project metadata.
- Show searchable slash commands expanding upward above the composer and restore typing immediately after mouse reading or clicking the editor.
- Use full-screen navigation and inspection views instead of floating popups, combine reasoning and model application controls, and consolidate provider command aliases.
- Add a provider-management browser, searchable model selection and explicit conversation/default settings.
- Refine the terminal conversation with a yellow/grey-on-black theme and expandable source/output.
- Add a maintained documentation map, agent workflows, and local candidate verification commands.
- Simplify CI to one Linux core/TUI test job per PR. Build each release target once, stage a draft, and publish the same artifacts.

## 0.1.1

Initial public release for macOS and glibc Linux on arm64 and x64, with a persistent
Clojure evaluator, durable sessions, terminal/RPC interfaces, supported authentication,
and checksum-verified installation.
