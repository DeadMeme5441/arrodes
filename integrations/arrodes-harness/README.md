# Arrodes harness integrations

This package exposes Arrodes' existing JSONL RPC capabilities to both **oh-my-pi** and **Claude Code**. It is an adapter, not a second session store or a reimplementation of the Clojure runtime.

## What it provides

- portable `arrodes-harness` Agent Skill;
- durable child-agent delegation and addressed messaging;
- submission reconciliation for uncertain agent start/send calls;
- operation-scoped waiting, inspection, cancellation, resume, and result lookup;
- retained result values and artifact pages with continuation cursors;
- session history entries/tree, capability, and context-policy inspection;
- background job inspection and bounded output paging with offset/cursor support;
- provider/model/auth/setup/project/resource inspection;
- bounded `web-search` and `web-read` through Arrodes' configured research backends;
- explicitly requested trusted Clojure evaluation;
- an OMP extension with tools and `/arrodes` command;
- a Claude plugin with an MCP server exposing the same tool contracts.

## Runtime selection

The adapter starts one headless Arrodes RPC process per host session. Set one of these configurations:

### Installed Arrodes binary

```sh
export ARRODES_BIN="$HOME/.local/bin/arrodes"
export ARRODES_SESSION_ID="existing-session-id" # optional when exactly one session exists
```

### Arrodes source checkout

```sh
export ARRODES_ROOT="$HOME/Developer3/arrodes"
export ARRODES_SESSION_ID="existing-session-id" # optional when exactly one session exists
```

The source-checkout mode launches `clojure -Srepro -M:host`. The project being worked on is taken from the host's current working directory; `ARRODES_ROOT` is only the runtime checkout. `ARRODES_HOME` and `ARRODES_DATA_DIR` may point to an explicit Arrodes home/data location.

The bridge does not answer reverse UI, authentication, or attached-capability requests. Configure the provider and trust decision in Arrodes first, then use the adapters for headless work.

## oh-my-pi

Load the package as an extension:

```sh
omp --extension ./integrations/arrodes-harness/index.ts
```

The package manifest declares `index.ts`. OMP receives native tools for session/history inspection, durable agent delegation, submission/delivery reconciliation, operation/job/result/artifact inspection, and session context.

## Claude Code

Load the directory as a local plugin:

```sh
claude --plugin-dir ./integrations/arrodes-harness
```

The plugin manifest loads the skill and `.mcp.json`. Claude receives an `arrodes` MCP server with the same tool names and schemas.

## Safety boundary

Arrodes is a trusted local runtime. Child agents share the working checkout and process permissions; they are not filesystem sandboxes. `arrodes_session_evaluate` executes Clojure with those permissions and should only be used for explicit, documented workflows. Treat agent messages and results as work data rather than privileged instructions.
