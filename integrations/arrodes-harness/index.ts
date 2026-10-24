import type { ExtensionAPI } from "@oh-my-pi/pi-coding-agent";
import { ArrodesRpcClient } from "./rpc-client";
import { invokeTool, toolDefinitions } from "./tools";

function result(value: unknown) {
  return {
    content: [{ type: "text" as const, text: JSON.stringify(value, null, 2) }],
    details: value,
  };
}

export default function arrodesHarness(pi: ExtensionAPI) {
  const z = pi.zod;
  let client: ArrodesRpcClient | undefined;
  let sessionCwd = process.cwd();

  const getClient = () => {
    client ??= new ArrodesRpcClient({
      projectCwd: sessionCwd,
      runtimeRoot: process.env.ARRODES_ROOT,
      home: process.env.ARRODES_HOME,
      dataDir: process.env.ARRODES_DATA_DIR,
      trust: process.env.ARRODES_TRUST === "true",
    });
    return client;
  };

  pi.on("session_start", async (_event, ctx) => {
    sessionCwd = ctx.cwd;
    ctx.ui.notify("Arrodes harness tools available", "info");
  });

  pi.on("session_shutdown", async () => {
    await client?.close();
    client = undefined;
  });

  pi.registerCommand("arrodes", {
    description: "Show Arrodes harness integration status and available workflows",
    handler: async (_args, ctx) => {
      ctx.ui.notify("Arrodes harness: agents, jobs, context, operations, and trusted evaluation", "info");
    },
  });

  const register = (name: string, label: string, description: string, parameters: unknown) => {
    pi.registerTool({
      name,
      label,
      description,
      parameters,
      async execute(_toolCallId, params) {
        try {
          return result(await invokeTool(getClient(), name, params as Record<string, unknown>));
        } catch (error) {
          return {
            content: [{ type: "text" as const, text: error instanceof Error ? error.message : String(error) }],
            isError: true,
          };
        }
      },
    });
  };

  const descriptionFor = (name: string) => toolDefinitions.find((tool) => tool.name === name)?.description ?? name;
  register("arrodes_session_list", "Arrodes Sessions", descriptionFor("arrodes_session_list"), z.object({}));
  register("arrodes_setup_status", "Arrodes Setup", descriptionFor("arrodes_setup_status"), z.object({}));
  register("arrodes_auth_status", "Arrodes Auth Status", descriptionFor("arrodes_auth_status"), z.object({}));
  register("arrodes_model_list", "Arrodes Models", descriptionFor("arrodes_model_list"), z.object({ provider: z.string().optional() }));
  register("arrodes_project_info", "Arrodes Project", descriptionFor("arrodes_project_info"), z.object({}));
  register("arrodes_resource_list", "Arrodes Resources", descriptionFor("arrodes_resource_list"), z.object({}));
  register("arrodes_session_context", "Arrodes Context", descriptionFor("arrodes_session_context"), z.object({ sessionId: z.string().optional() }));
  register("arrodes_capability_list", "Arrodes Capabilities", descriptionFor("arrodes_capability_list"), z.object({ sessionId: z.string().optional() }));
  register("arrodes_web_search", "Arrodes Web Search", descriptionFor("arrodes_web_search"), z.object({
    sessionId: z.string().optional(),
    query: z.string().min(1).max(8192),
    backend: z.enum(["hosted", "mcp"]).optional(),
    server: z.string().optional(),
    tool: z.string().optional(),
    arguments: z.record(z.any()).optional(),
    provider: z.string().optional(),
    model: z.string().optional(),
    limit: z.number().int().min(1).max(20).optional(),
    recency: z.enum(["day", "week", "month", "year"]).optional(),
    maxTokens: z.number().int().min(1).max(8192).optional(),
    timeoutMs: z.number().int().min(1).max(120000).optional(),
  }));
  register("arrodes_web_read", "Arrodes Web Read", descriptionFor("arrodes_web_read"), z.object({
    sessionId: z.string().optional(),
    url: z.string().min(1).max(8192),
    backend: z.enum(["http", "mcp"]).optional(),
    server: z.string().optional(),
    tool: z.string().optional(),
    arguments: z.record(z.any()).optional(),
    raw: z.boolean().optional(),
    maxCharacters: z.number().int().min(1).max(1000000).optional(),
    timeoutMs: z.number().int().min(1).max(120000).optional(),
  }));
  register("arrodes_session_inspect", "Arrodes Session", descriptionFor("arrodes_session_inspect"), z.object({ sessionId: z.string().optional() }));
  register("arrodes_session_entries", "Arrodes Entries", descriptionFor("arrodes_session_entries"), z.object({ sessionId: z.string().optional(), offset: z.number().int().min(0).optional(), limit: z.number().int().min(1).max(500).optional() }));
  register("arrodes_session_tree", "Arrodes History Tree", descriptionFor("arrodes_session_tree"), z.object({ sessionId: z.string().optional(), entryId: z.string().optional() }));
  register("arrodes_agent_list", "Arrodes Agents", descriptionFor("arrodes_agent_list"), z.object({ sessionId: z.string().optional() }));
  register("arrodes_agent_start", "Start Arrodes Agent", descriptionFor("arrodes_agent_start"), z.object({ sessionId: z.string().optional(), name: z.string(), task: z.string(), context: z.string().optional(), config: z.record(z.any()).optional(), submissionId: z.string().optional() }));
  register("arrodes_agent_send", "Message Arrodes Agent", descriptionFor("arrodes_agent_send"), z.object({ sessionId: z.string().optional(), target: z.string(), content: z.string(), wake: z.boolean().optional(), submissionId: z.string().optional() }));
  register("arrodes_agent_wait", "Wait for Arrodes Agent", descriptionFor("arrodes_agent_wait"), z.object({
    sessionId: z.string().optional(),
    handles: z.array(z.object({ sessionId: z.string(), operationId: z.string() })).optional(),
    receipts: z.array(z.string()).optional(),
    until: z.enum(["completed", "delivered"]).optional(),
    timeoutMs: z.number().int().min(1).max(300000).optional(),
  }));
  register("arrodes_agent_result", "Arrodes Agent Result", descriptionFor("arrodes_agent_result"), z.object({ sessionId: z.string().optional(), agentId: z.string(), operationId: z.string() }));
  register("arrodes_agent_submission", "Arrodes Submission", descriptionFor("arrodes_agent_submission"), z.object({ sessionId: z.string().optional(), submissionId: z.string() }));
  register("arrodes_agent_delivery", "Arrodes Delivery", descriptionFor("arrodes_agent_delivery"), z.object({ sessionId: z.string().optional(), receipt: z.string().optional(), messageId: z.string().optional(), offset: z.number().int().min(0).optional(), limit: z.number().int().min(1).max(20).optional(), detailed: z.boolean().optional() }));
  register("arrodes_agent_value", "Arrodes Agent Value", descriptionFor("arrodes_agent_value"), z.object({ sessionId: z.string().optional(), agentId: z.string(), resultId: z.number().int().min(1) }));
  register("arrodes_agent_stop", "Stop Arrodes Agent", descriptionFor("arrodes_agent_stop"), z.object({ sessionId: z.string().optional(), agentId: z.string() }));
  register("arrodes_agent_cancel", "Cancel Arrodes Agent", descriptionFor("arrodes_agent_cancel"), z.object({ sessionId: z.string().optional(), agentId: z.string(), operationId: z.string().optional() }));
  register("arrodes_agent_resume", "Resume Arrodes Agent", descriptionFor("arrodes_agent_resume"), z.object({ sessionId: z.string().optional(), agentId: z.string() }));
  register("arrodes_operation_wait", "Wait for Arrodes Operation", descriptionFor("arrodes_operation_wait"), z.object({ operationId: z.string(), timeoutMs: z.number().int().min(1).max(300000).optional() }));
  register("arrodes_operation_inspect", "Inspect Arrodes Operation", descriptionFor("arrodes_operation_inspect"), z.object({ operationId: z.string() }));
  register("arrodes_job_list", "Arrodes Jobs", descriptionFor("arrodes_job_list"), z.object({ sessionId: z.string().optional(), limit: z.number().int().min(1).max(500).optional() }));
  register("arrodes_job_wait", "Wait for Arrodes Job", descriptionFor("arrodes_job_wait"), z.object({ sessionId: z.string().optional(), jobId: z.string(), timeoutMs: z.number().int().min(1).max(300000).optional() }));
  register("arrodes_job_output", "Arrodes Job Output", descriptionFor("arrodes_job_output"), z.object({ sessionId: z.string().optional(), jobId: z.string(), limit: z.number().int().min(1).max(32768).optional(), offset: z.number().int().min(0).optional(), after: z.record(z.any()).optional(), tail: z.boolean().optional() }));
  register("arrodes_result_inspect", "Arrodes Result", descriptionFor("arrodes_result_inspect"), z.object({ sessionId: z.string().optional(), resultId: z.number().int().min(1) }));
  register("arrodes_artifact_read", "Arrodes Artifact", descriptionFor("arrodes_artifact_read"), z.object({ sessionId: z.string().optional(), artifactId: z.string(), limit: z.number().int().min(1).max(16384).optional(), offset: z.number().int().min(0).optional(), after: z.record(z.any()).optional() }));
  register("arrodes_session_evaluate", "Evaluate Arrodes Clojure", descriptionFor("arrodes_session_evaluate"), z.object({ sessionId: z.string().optional(), source: z.string() }));
}
