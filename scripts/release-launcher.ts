import { chmodSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { isAbsolute, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import payloadPath from "../target/release-build/payload.tar.gz" with { type: "file" };

declare const __ARRODES_VERSION__: string;
declare const __ARRODES_PLATFORM__: string;
declare const __ARRODES_ARCH__: string;
declare const __ARRODES_PAYLOAD_SHA256__: string;

const VERSION = __ARRODES_VERSION__;
const PLATFORM = __ARRODES_PLATFORM__;
const ARCH = __ARRODES_ARCH__;
const PAYLOAD_SHA256 = __ARRODES_PAYLOAD_SHA256__;
const HELP = `Arrodes - conversation-first terminal agent

Usage: arrodes [options] [prompt]
       arrodes --rpc [RPC options]

  --cwd PATH        Project directory
  --home PATH       Application home (default: ~/.arrodes)
  --data-dir PATH   Session storage directory
  --session ID      Resume a stored session
  --provider NAME   Provider for a new session
  --model ID        Model for a new session
  --thinking LEVEL  Reasoning level
  --trust           Load trusted project resources
  --no-trust        Do not load executable project resources
  --memory          Ephemeral in-memory sessions
  --no-mouse        Disable mouse capture
  --rpc             Run headless JSONL RPC on stdio
  --help, -h        Show help
  --version, -v     Show version
`;

function option(args: string[], name: string): string | undefined {
  let value: string | undefined;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--") break;
    if (args[i] !== name) continue;
    if (!args[i + 1] || args[i + 1].startsWith("--")) throw new Error(`${name} requires a value`);
    value = args[++i];
  }
  return value;
}

function withoutOptions(args: string[], names: Record<string, true>): string[] {
  const result: string[] = [];
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--") return [...result, ...args.slice(i)];
    if (names[args[i]]) {
      if (!args[i + 1] || args[i + 1].startsWith("--")) throw new Error(`${args[i]} requires a value`);
      i++;
    } else {
      result.push(args[i]);
    }
  }
  return result;
}

function complete(directory: string): boolean {
  try {
    return statSync(directory).isDirectory() && readFileSync(join(directory, ".complete"), "utf8") === `${PAYLOAD_SHA256}\n`;
  } catch {
    return false;
  }
}

function makeExecutable(directory: string): void {
  if (process.platform === "win32") return;
  const bin = join(directory, "java", "bin");
  for (const name of readdirSync(bin)) chmodSync(join(bin, name), 0o755);
  for (const relative of ["java/lib/jspawnhelper", "java/lib/jexec"]) {
    const path = join(directory, relative);
    try {
      if (statSync(path).isFile()) chmodSync(path, 0o755);
    } catch {
      // Not every JDK platform carries both helpers.
    }
  }
}

async function runtime(home: string): Promise<string> {
  const root = join(home, "runtime");
  const directory = join(root, `${VERSION}-${PLATFORM}-${ARCH}-${PAYLOAD_SHA256}`);
  if (complete(directory)) return directory;
  try {
    statSync(directory);
    if (complete(directory)) return directory;
    throw new Error(`Embedded runtime is incomplete; refusing to replace it: ${directory}`);
  } catch (error) {
    if (error instanceof Error && !("code" in error && error.code === "ENOENT")) throw error;
  }

  mkdirSync(root, { recursive: true, mode: 0o700 });
  const temporary = `${directory}.tmp-${process.pid}-${crypto.randomUUID()}`;
  mkdirSync(temporary, { mode: 0o700 });
  try {
    const bytes = await Bun.file(payloadPath).bytes();
    const actual = new Bun.CryptoHasher("sha256").update(bytes).digest("hex");
    if (actual !== PAYLOAD_SHA256) throw new Error("Embedded runtime payload checksum mismatch");
    await new Bun.Archive(bytes).extract(temporary);
    makeExecutable(temporary);
    writeFileSync(join(temporary, ".complete"), `${PAYLOAD_SHA256}\n`, { mode: 0o600 });
    try {
      renameSync(temporary, directory);
    } catch (error) {
      if (!complete(directory)) throw error;
      rmSync(temporary, { recursive: true, force: true });
    }
    return directory;
  } catch (error) {
    rmSync(temporary, { recursive: true, force: true });
    throw error;
  }
}



function rpcCommand(directory: string, home: string, cwd: string): string[] {
  const nativeCache = join(home, "cache", "jna");
  mkdirSync(nativeCache, { recursive: true, mode: 0o700 });
  return [
    join(directory, "java", "bin", process.platform === "win32" ? "java.exe" : "java"),
    `-Duser.home=${homedir()}`,
    `-Djna.tmpdir=${nativeCache}`,
    "-jar",
    join(directory, "arrodes-rpc.jar"),
    "--home",
    home,
    "--cwd",
    cwd,
  ];
}

async function launchChild(command: string[], cwd: string, env: Record<string, string | undefined>): Promise<void> {
  const child = Bun.spawn(command, {
    cwd,
    env,
    stdin: "inherit",
    stdout: "inherit",
    stderr: "inherit",
  });
  const forward = (signal: "SIGINT" | "SIGTERM") => {
    try { child.kill(signal); } catch { /* The child already exited. */ }
  };
  const interrupt = () => forward("SIGINT");
  const terminate = () => forward("SIGTERM");
  process.on("SIGINT", interrupt);
  process.on("SIGTERM", terminate);
  try {
    process.exitCode = await child.exited;
  } finally {
    process.off("SIGINT", interrupt);
    process.off("SIGTERM", terminate);
  }
}

async function tui(directory: string, args: string[], home: string, cwd: string): Promise<void> {
  process.env.ARRODES_HOME = home;
  process.env.ARRODES_TUI_ROOT = directory;
  process.env.OTUI_ASSET_ROOT = join(directory, "opentui");
  process.env.ARRODES_TUI_RPC_COMMAND = JSON.stringify(rpcCommand(directory, home, cwd));
  if (process.platform === "linux") process.env.OPENTUI_LIBC = "glibc";
  // These imports must occur after the private runtime and OpenTUI asset root exist.
  const OpenTUI = await import("@opentui/core");
  Object.assign(globalThis, { ARRODES_OPENTUI: OpenTUI });
  const entry = join(directory, "tui", "main.cjs");
  process.argv = [process.execPath, entry, ...args];
  // The precompiled ClojureScript entry is selected from the verified extracted payload.
  await import(pathToFileURL(entry).href);
}

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  const separator = args.indexOf("--");
  const flags = separator < 0 ? args : args.slice(0, separator);
  if (flags.includes("--help") || flags.includes("-h")) {
    process.stdout.write(HELP);
    return;
  }
  if (flags.includes("--version") || flags.includes("-v")) {
    process.stdout.write(`Arrodes ${VERSION}\n`);
    return;
  }

  const launch = process.cwd();
  const requestedHome = option(args, "--home") ?? (process.env.ARRODES_HOME || join(homedir(), ".arrodes"));
  const requestedCwd = option(args, "--cwd") ?? launch;
  const home = isAbsolute(requestedHome) ? resolve(requestedHome) : resolve(launch, requestedHome);
  const cwd = isAbsolute(requestedCwd) ? resolve(requestedCwd) : resolve(launch, requestedCwd);
  const transpilerCache = join(home, "cache", "bun");
  // Bun selects its transpiler cache at process startup, before JavaScript can change it.
  if (!flags.includes("--rpc") && process.env.BUN_RUNTIME_TRANSPILER_CACHE_PATH !== transpilerCache) {
    await launchChild([process.execPath, ...args], launch,
                      { ...process.env, BUN_RUNTIME_TRANSPILER_CACHE_PATH: transpilerCache });
    return;
  }
  mkdirSync(home, { recursive: true, mode: 0o700 });
  const directory = await runtime(home);
  if (flags.includes("--rpc")) {
    const passthrough = withoutOptions(args.filter(argument => argument !== "--rpc"), { "--home": true, "--cwd": true });
    await launchChild([...rpcCommand(directory, home, cwd), ...passthrough], cwd,
                      { ...process.env, ARRODES_HOME: home });
  } else {
    await tui(directory, args, home, cwd);
  }
}

try {
  await main();
} catch (error) {
  process.stderr.write(`Arrodes: ${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
}
