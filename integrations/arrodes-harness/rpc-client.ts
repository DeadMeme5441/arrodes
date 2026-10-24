import { existsSync } from "node:fs";
import { join } from "node:path";

export type RpcParams = Record<string, unknown>;

export interface RpcClientOptions {
  projectCwd: string;
  runtimeRoot?: string;
  command?: string[];
  home?: string;
  dataDir?: string;
  trust?: boolean;
}

type Pending = {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
};

type ArrodesReader = {
  read(): Promise<{ done: boolean; value?: Uint8Array }>;
  cancel(reason?: unknown): Promise<void>;
  releaseLock(): void;
};

type ArrodesProcess = {
  stdin: { write(input: string): number | Promise<number>; end?: () => void };
  stdout: { getReader(): ArrodesReader };
  kill(signal?: number | string): void;
  exited: Promise<number>;
};

type Deferred<T> = {
  promise: Promise<T>;
  resolve: (value: T | PromiseLike<T>) => void;
  reject: (error: Error) => void;
};

function deferred<T>(): Deferred<T> {
  return Promise.withResolvers<T>();
}

function field(value: unknown, key: string): unknown {
  if (value && typeof value === "object" && key in value) {
    return Reflect.get(value, key);
  }
  return undefined;
}

function errorFromRpc(value: unknown): Error {
  if (value && typeof value === "object") {
    const code = field(value, "code");
    const message = field(value, "message");
    const data = field(value, "data");
    const error = new Error(typeof message === "string" ? message : "Arrodes RPC request failed");
    Object.assign(error, { code, data });
    return error;
  }
  return new Error(String(value ?? "Arrodes RPC request failed"));
}

function commandFromEnvironment(options: RpcClientOptions): { command: string[]; cwd: string } {
  if (options.command) return { command: options.command, cwd: options.runtimeRoot ?? options.projectCwd };
  const configured = process.env.ARRODES_RPC_COMMAND_JSON;
  if (configured) {
    const command = JSON.parse(configured);
    if (!Array.isArray(command) || command.length === 0 || command.some((item) => typeof item !== "string")) {
      throw new Error("ARRODES_RPC_COMMAND_JSON must be a non-empty JSON string array");
    }
    return { command, cwd: options.runtimeRoot ?? options.projectCwd };
  }

  const binary = process.env.ARRODES_BIN;
  if (binary) return { command: [binary, "--rpc"], cwd: options.runtimeRoot ?? options.projectCwd };

  const runtimeRoot = options.runtimeRoot ?? process.env.ARRODES_ROOT;
  if (runtimeRoot && existsSync(join(runtimeRoot, "deps.edn")) && existsSync(join(runtimeRoot, "hosts", "rpc"))) {
    return { command: ["clojure", "-Srepro", "-M:host"], cwd: runtimeRoot };
  }

  return { command: ["arrodes", "--rpc"], cwd: options.runtimeRoot ?? options.projectCwd };
}


export class ArrodesRpcClient {
  private process?: ArrodesProcess;
  private stdoutReader?: ArrodesReader;
  private startPromise?: Promise<void>;
  private nextRequestId = 0;
  private pending = new Map<string, Pending>();
  private hello = deferred<void>();
  private closed = false;
  constructor(private readonly options: RpcClientOptions) {}

  async call(method: string, params: RpcParams = {}): Promise<unknown> {
    await this.ensureStarted();
    return this.request(method, params);
  }

  async close(): Promise<void> {
    if (this.closed) return;
    const process = this.process;
    if (!process) {
      this.closed = true;
      return;
    }

    // Do not wait on the RPC shutdown response here. A runtime shutdown can
    // legitimately wait for owned workers, while the host is already tearing
    // down the transport. Closing the pipes and killing this owned process
    // avoids leaving a pending write or response promise in the host.
    this.closed = true;
    try {
      process.stdin.end?.();
    } catch {
      // The pipe may already be closed.
    }
    try {
      await this.stdoutReader?.cancel("adapter-closing");
    } catch {
      // The reader may already have been released by process exit.
    }
    try {
      process.kill("SIGKILL");
      await process.exited;
    } catch {
      // The RPC process may already have exited.
    }
  }

  private async ensureStarted(): Promise<void> {
    if (!this.startPromise) this.startPromise = this.start();
    return this.startPromise;
  }

  private async start(): Promise<void> {
    const launch = commandFromEnvironment(this.options);
    this.process = Bun.spawn(launch.command, {
      cwd: launch.cwd,
      stdin: "pipe",
      stdout: "pipe",
      stderr: "inherit",
    });

    const helloTimeout = Promise.withResolvers<never>();
    const helloTimer = setTimeout(() => helloTimeout.reject(new Error("Timed out waiting for Arrodes RPC hello")), 30_000);
    try {
      await Promise.race([this.hello.promise, helloTimeout.promise]);
    } finally {
      clearTimeout(helloTimer);
    }

    await this.request("initialize", {
      cwd: this.options.projectCwd,
      ...(this.options.home ?? process.env.ARRODES_HOME ? { home: this.options.home ?? process.env.ARRODES_HOME } : {}),
      ...(this.options.dataDir ?? process.env.ARRODES_DATA_DIR ? { "data-dir": this.options.dataDir ?? process.env.ARRODES_DATA_DIR } : {}),
      ...((this.options.trust ?? process.env.ARRODES_TRUST === "true") ? { trust: true } : {}),
    });
  }

  private async request(method: string, params: RpcParams): Promise<unknown> {
    if (!this.process || this.closed) throw new Error("Arrodes RPC client is closed");
    const id = `arrodes-${++this.nextRequestId}`;
    const response = deferred<unknown>();
    this.pending.set(id, response);

    try {
      await this.process.stdin.write(`${JSON.stringify({ type: "request", id, method, params })}\n`);
      return await response.promise;
    } finally {
      this.pending.delete(id);
    }
  }

  private async readOutput(): Promise<void> {
    if (!this.process) return;
    const decoder = new TextDecoder();
    let buffer = "";
    const reader = this.process.stdout.getReader();
    this.stdoutReader = reader;

    try {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        if (!chunk.value) continue;
        buffer += decoder.decode(chunk.value, { stream: true });
        let newline = buffer.indexOf("\n");
        while (newline >= 0) {
          const line = buffer.slice(0, newline).replace(/\r$/, "");
          buffer = buffer.slice(newline + 1);
          if (line.trim()) this.handleMessage(JSON.parse(line));
          newline = buffer.indexOf("\n");
        }
      }
      buffer += decoder.decode();
      if (buffer.trim()) this.handleMessage(JSON.parse(buffer));
      throw new Error("Arrodes RPC process exited unexpectedly");
    } catch (error) {
      const failure = error instanceof Error ? error : new Error(String(error));
      this.hello.reject(failure);
      for (const waiter of this.pending.values()) waiter.reject(failure);
    } finally {
      reader.releaseLock();
      this.stdoutReader = undefined;
    }
  }

  private handleMessage(message: Record<string, unknown>): void {
    if (message.type === "hello") {
      this.hello.resolve(undefined);
      return;
    }

    if (message.type === "host-request") {
      // These adapters are intentionally headless. Never auto-approve UI, auth, or
      // attached-capability requests originating from the trusted Arrodes process.
      void this.process?.stdin.write(`${JSON.stringify({
        type: "host-cancel",
        id: message.id,
        reason: "headless-integration-does-not-approve-host-requests",
      })}\n`);
      return;
    }

    if (message.type !== "response") return;
    const waiter = this.pending.get(String(message.id));
    if (!waiter) return;
    if (message.error !== undefined) waiter.reject(errorFromRpc(message.error));
    else waiter.resolve(message.result);
  }
}

export async function resolveSessionId(client: ArrodesRpcClient, explicit?: string): Promise<string> {
  if (explicit?.trim()) return explicit.trim();
  const configured = process.env.ARRODES_SESSION_ID?.trim();
  if (configured) return configured;

  const result = await client.call("session.list");
  const rows = Array.isArray(result)
    ? result
    : result && typeof result === "object" && Array.isArray((result as { sessions?: unknown[] }).sessions)
      ? (result as { sessions: unknown[] }).sessions
      : [];
  const ids = rows
    .map((row) => row && typeof row === "object" ? (row as { id?: unknown; "session-id"?: unknown }).id ?? (row as { "session-id"?: unknown })["session-id"] : undefined)
    .filter((id): id is string => typeof id === "string" && id.length > 0);

  if (ids.length === 1) return ids[0];
  if (ids.length === 0) throw new Error("No Arrodes session is available; start Arrodes once or set ARRODES_SESSION_ID");
  throw new Error(`Multiple Arrodes sessions are available; pass sessionId or set ARRODES_SESSION_ID (${ids.join(", ")})`);
}
