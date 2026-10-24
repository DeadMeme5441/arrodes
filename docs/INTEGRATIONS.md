# Host integrations

Arrodes exposes a versioned JSONL RPC host. Host integrations should reuse that contract rather than creating a second persistence or agent scheduler.

The repository ships `integrations/arrodes-harness`, a dual adapter for oh-my-pi and Claude Code:

- Agent Skills provide the shared delegation, jobs, context, and verification workflow;
- the OMP extension registers tools and a `/arrodes` command;
- the Claude plugin registers an MCP server with the same tool names and schemas;
- both adapters launch one headless `arrodes --rpc` connection and forward durable RPC operations.

## Installation

From a source checkout, configure the runtime explicitly:

```sh
export ARRODES_ROOT=/path/to/arrodes
export ARRODES_SESSION_ID=SESSION_ID # optional if only one Arrodes session exists
```

For a packaged installation, set `ARRODES_BIN` to the installed executable instead. The host's current working directory remains the project directory passed to Arrodes during initialization.

Load OMP with:

```sh
omp --extension ./integrations/arrodes-harness/index.ts
```

Load Claude Code with:

```sh
claude --plugin-dir ./integrations/arrodes-harness
```

## Exposed capabilities

The adapters expose provider/model/auth/setup/project/resource inspection; session listing/inspection, history entries/tree and context inspection; child-agent start/list/send/wait/result/value/stop/cancel/resume; submission and message-delivery reconciliation; operation waiting/inspection; background job list/wait/output with cursors; retained result/artifact inspection; bounded `web-search`/`web-read`; and explicit trusted Clojure evaluation. Tool inputs use host-neutral camelCase names and are translated to the RPC protocol's canonical parameter names.

For mutating agent calls, pass one stable `submissionId` when possible. If omitted, the adapter creates one for Arrodes' idempotency contract. If a response is lost, reconcile by durable submission/operation identity rather than repeat the prompt or task text.

## Security and lifecycle

The adapters are headless. They cancel reverse UI, authentication, and attached-capability requests instead of auto-approving them. Provider setup, project trust, and credentials remain Arrodes-owned concerns, including the original project's experimental Anthropic OAuth path; configure those interactively in Arrodes before starting a host adapter.

A child agent shares the checkout and process permissions with other Arrodes sessions. Cancellation is not rollback, and restart does not replay external effects. Results and job output remain bounded and must be paged or inspected by ID.

The bridge exits with the host session; Arrodes owns its own durable state and cleanup. An adapter must not infer successful execution from a broken process or a missing response.
