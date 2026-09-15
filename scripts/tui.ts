import { existsSync, readdirSync, rmSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import * as OpenTUI from "@opentui/core";

// Only launch/build mechanics live in JavaScript. The application is ClojureScript.
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const output = join(root, "target/tui/main.cjs");
function newest(path: string): number {
  if (!existsSync(path)) return 0;
  const info = statSync(path);
  if (!info.isDirectory()) return info.mtimeMs;
  return readdirSync(path).reduce((time, name) => Math.max(time, newest(join(path, name))), info.mtimeMs);
}
const modified = Math.max(...["src/cljs", "src/cljc", "resources/arrodes/themes", "deps.edn", "tui.edn"].map(path => newest(join(root, path))));
if (!existsSync(output) || statSync(output).mtimeMs < modified) {
  // Built-in EDN packs are macro inputs, so invalidate their compiled consumers.
  if (!existsSync(output) || newest(join(root, "resources/arrodes/themes")) > statSync(output).mtimeMs) {
    rmSync(join(root, "target/tui/out/arrodes"), { recursive: true, force: true });
  }
  const result = Bun.spawnSync(["clojure", "-Srepro", "-M:tui-build"], {
    cwd: root, stdin: "inherit", stdout: "inherit", stderr: "inherit",
  });
  if (result.exitCode !== 0) process.exit(result.exitCode);
}
process.env.ARRODES_TUI_ROOT = root;
Object.assign(globalThis, { ARRODES_OPENTUI: OpenTUI });
process.argv = [process.execPath, output, ...process.argv.slice(2)];
// This generated module can be absent until the compilation above finishes.
// A static import would be evaluated before that build boundary.
await import(pathToFileURL(output).href);
