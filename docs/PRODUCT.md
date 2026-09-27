# Product and scope

Arrodes is a conversation-first terminal coding agent, with an embeddable JVM core
and a JSONL RPC interface. The model works through one `repl` provider action,
discovers registered functions, and composes them with normal Clojure. Users should
be able to work conversationally and inspect execution when useful.

## Contracts

- Session-owned function jobs outlive their launching turn and retain inspectable outcomes.
- One live evaluator per session; native values and definitions survive normal turns.
- A root session can delegate to independently evaluated, addressable child sessions,
  exchange durable peer messages, and inspect operation-scoped outcomes. Delegation
  preserves the single-`repl` provider interface; it does not turn function jobs into
  agents or share live JVM values between sessions.
- Durable history, operations, queues and retained results survive current-format
  restart and supported backed-up schema upgrades. Unsupported, newer, malformed
  and foreign databases are rejected intact, never reset to an empty store.
- Packaged installations update from verified published assets without modifying
  session data, settings or credentials.
- Recovery records interrupted work and never automatically repeats external effects.
- Branching selects conversation context and does not restore filesystem state.
- Explicit connection, provider and model settings are understandable and inspectable.
- User/project state stays outside the working repository.
- The runtime executes trusted local code with its process permissions.

## Deliberate boundaries

The current [scope](../resources/arrodes/scope.edn) includes session-backed agents
but excludes generalized workspaces, planning/todo management, process daemons,
distributed execution, OS sandboxing and JVM checkpointing. Agent sessions share
the checkout and process permissions; explicit ownership, not isolation, coordinates
concurrent file edits. Treat changes to these exclusions as product decisions.

The supported binary targets are macOS and glibc Linux, each on arm64 and x64.
Windows remains experimental. Avoid declaring an untested target supported.

## Accepting a feature

Define the user problem, observed before/after behavior, affected contracts and explicit
exclusions. Interface acceptance includes keyboard access, cancellation/failure states,
readability at narrow sizes and preserved user input. Implementation complete, locally
verified, candidate accepted and released are separate states.
