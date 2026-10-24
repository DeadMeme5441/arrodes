import { expect, test } from "bun:test";
import { invokeTool, toolDefinitions } from "./tools";

// MCP clients validate the whole tools/list result; one schema without
// type "object" makes them drop every tool.
test("every tool inputSchema is a top-level object schema", () => {
  const bad = toolDefinitions.filter((tool) => tool.inputSchema.type !== "object").map((tool) => tool.name);
  expect(bad).toEqual([]);
});
test("agent starts require explicit provider and model", () => {
  const agentStart = toolDefinitions.find((tool) => tool.name === "arrodes_agent_start");
  expect(agentStart?.inputSchema.required).toEqual(["name", "task", "provider", "model"]);
});
test("agent starts translate explicit routing fields into child config", async () => {
  const calls: Array<[string, Record<string, unknown>]> = [];
  const client = {
    call: async (method: string, params: Record<string, unknown>) => {
      calls.push([method, params]);
      return {};
    },
  };
  await invokeTool(client as never, "arrodes_agent_start", {
    sessionId: "session-1",
    name: "review",
    task: "Review the change",
    provider: "anthropic",
    model: "claude-test",
    thinking: "high",
    submissionId: "submission-1",
  });
  expect(calls[0]).toEqual([
    "agent.start",
    {
      "session-id": "session-1",
      name: "review",
      task: "Review the change",
      config: { provider: "anthropic", model: "claude-test", thinking: "high" },
      "submission-id": "submission-1",
    },
  ]);
});
test("session listing filters by project and naming forwards the selected session", async () => {
  const calls: Array<[string, Record<string, unknown>]> = [];
  const client = {
    call: async (method: string, params: Record<string, unknown>) => {
      calls.push([method, params]);
      return {};
    },
  };
  await invokeTool(client as never, "arrodes_session_list", { cwd: "/workspace/project" });
  await invokeTool(client as never, "arrodes_session_name", { sessionId: "session-2", name: "API auth review" });
  expect(calls).toEqual([
    ["session.list", { cwd: "/workspace/project" }],
    ["session.name", { "session-id": "session-2", name: "API auth review" }],
  ]);
});
