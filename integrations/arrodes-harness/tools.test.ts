import { expect, test } from "bun:test";
import { toolDefinitions } from "./tools";

// MCP clients validate the whole tools/list result; one schema without
// type "object" makes them drop every tool.
test("every tool inputSchema is a top-level object schema", () => {
  const bad = toolDefinitions.filter((tool) => tool.inputSchema.type !== "object").map((tool) => tool.name);
  expect(bad).toEqual([]);
});
