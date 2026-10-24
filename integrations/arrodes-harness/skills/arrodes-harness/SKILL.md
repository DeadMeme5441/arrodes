---
name: arrodes-harness
description: Use Arrodes as a durable coding harness for delegated investigation, background work, operation-scoped results, context inspection, and explicit verification. Use when a coding task is large, asynchronous, multi-step, or benefits from independent review.
---

# Arrodes coding harness

Use the Arrodes integration tools when work benefits from durable operations or independent agents. The integration is an adapter over Arrodes' JSONL RPC protocol; it does not replace the host's normal file and shell tools.

## Operating rules

1. Inspect the repository and current task before delegating.
2. Delegate bounded questions, not vague requests to "solve everything".
3. Give each child a named task, explicit scope, evidence requirements, and a report format.
4. For `arrodes_agent_start`, always provide an explicit allowed `provider` and exact `model`; choose `thinking` when needed and set `fallback-model?` to false when provider isolation matters.
5. For `arrodes_agent_send`, generate one stable `submissionId` and reuse it for reconciliation or retry; never silently issue a second submission.
6. Before switching hosts, use `arrodes_session_list` with the current project directory, choose by the human-readable session name, and retain the selected UUID for `ARRODES_SESSION_ID`; use `arrodes_session_name` to name otherwise opaque sessions.
7. Retain the returned `sessionId` and `operationId`; never infer completion from prose.
8. Use `arrodes_agent_wait` or `arrodes_operation_wait` instead of polling.
9. Inspect the submission or delivery state when a mutation response is uncertain, then inspect the operation result after completion, including failed, cancelled, and interrupted outcomes.
10. Do not assume child agents are filesystem-isolated. They share the working checkout unless the task explicitly arranges isolation.
11. Verify the changed behavior with a focused test and a real smoke scenario where possible.
12. Report what was verified and what remains unknown.

## Recommended decomposition

For a substantial change, prefer independent tasks such as:

- architecture/call-site inventory;
- reproduction and root-cause analysis;
- implementation proposal or focused patch;
- test and fixture review;
- compatibility/security review;
- final behavioral verification.

Keep mutations centralized unless the repository has an explicit ownership plan for concurrent edits.

## Context and results

Use `arrodes_session_context` before deciding whether compaction or history inspection is needed. Use `arrodes_agent_result` with the exact child operation identity. Use `arrodes_job_output` for bounded pages; do not inject an entire retained artifact into the conversation.

## Trusted evaluation

`arrodes_session_evaluate` executes trusted Clojure with Arrodes process permissions. Use it only when the user explicitly asks for REPL evaluation or when a documented project workflow requires a registered Arrodes function. Never use it as a substitute for ordinary file inspection or verification.

## Failure handling

A timeout is not proof that work failed. Inspect the operation or job. A lost mutation response must be reconciled by the durable submission or operation identity before retrying. Cancellation is not rollback; already-completed file edits or external effects remain.
