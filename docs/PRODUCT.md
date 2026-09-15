# Product and scope

Arrodes is a conversation-first terminal coding agent, with an embeddable JVM core
and a JSONL RPC interface. The model works through one `repl` provider action,
discovers registered functions, and composes them with normal Clojure. Users should
be able to work conversationally and inspect execution when useful.

## Contracts

- One live evaluator per session; native values and definitions survive normal turns.
- Durable history, operations, queues and retained results survive process restart.
- Recovery records interrupted work and never automatically repeats external effects.
- Branching selects conversation context and does not restore filesystem state.
- Explicit connection, provider and model settings are understandable and inspectable.
- User/project state stays outside the working repository.
- The runtime executes trusted local code with its process permissions.

## Deliberate boundaries

The current [scope](../resources/arrodes/scope.edn) excludes generalized workspaces,
subagents, planning/todo management, background-job management, distributed execution,
OS sandboxing and JVM checkpointing. Treat changes to these as product decisions,
not incidental additions to another feature.

The supported binary targets are macOS and glibc Linux, each on arm64 and x64.
Windows remains experimental. Avoid declaring an untested target supported.

## Accepting a feature

Define the user problem, observed before/after behavior, affected contracts and explicit
exclusions. Interface acceptance includes keyboard access, cancellation/failure states,
readability at narrow sizes and preserved user input. Implementation complete, locally
verified, candidate accepted and released are separate states.
