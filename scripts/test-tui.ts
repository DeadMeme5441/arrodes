import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import * as OpenTUI from "@opentui/core";
import { createTestRenderer } from "@opentui/core/testing";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const temporary = mkdtempSync(join(tmpdir(), "arrodes-tui-test-"));
const output = join(temporary, "main.cjs");
try {
  const options = `{:target :nodejs :main arrodes.tui-view-test :optimizations :simple :infer-externs true :output-to ${JSON.stringify(output)} :output-dir ${JSON.stringify(join(temporary, "out"))}}`;
  const compiled = Bun.spawnSync([
    "clojure", "-Srepro", "-Sdeps", '{:paths ["src/cljc" "src/cljs" "test"]}',
    "-M:cljs", "-co", options, "-c", "arrodes.tui-view-test",
  ], { cwd: root, stdin: "inherit", stdout: "inherit", stderr: "inherit" });
  if (compiled.exitCode !== 0) throw new Error(`TUI regression compilation failed (${compiled.exitCode})`);
  process.env.ARRODES_TUI_ROOT = root;
  Object.assign(globalThis, {
    ARRODES_OPENTUI: OpenTUI,
    ARRODES_CREATE_TEST_RENDERER: createTestRenderer,
  });
  // Generated ClojureScript must load after compilation and the native ESM bridge.
  await import(pathToFileURL(output).href);
  const finished = Reflect.get(globalThis, "ARRODES_TUI_TEST_DONE");
  if (!(finished instanceof Promise)) throw new Error("TUI regression did not start");
  await finished;
} finally {
  rmSync(temporary, { recursive: true, force: true });
}
