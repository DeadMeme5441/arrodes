import { createHash } from "node:crypto";
import { chmodSync, closeSync, fsyncSync, lstatSync, openSync, renameSync, rmSync, writeSync } from "node:fs";
import { basename, dirname, isAbsolute, join } from "node:path";

const REPOSITORY = "DeadMeme5441/arrodes";
const API = `https://api.github.com/repos/${REPOSITORY}`;
const RELEASES = `https://github.com/${REPOSITORY}/releases/download`;
const MAX_METADATA = 512 * 1024;
const MAX_BINARY = 1024 * 1024 * 1024;
const VERSION = /^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;

export const UPDATE_HELP = `Usage: arrodes update [--check] [--version VERSION]

  --check            Check the published release without installing
  --version VERSION  Select a published version (older versions may not read newer data)
  --help, -h         Show this help

Updates only the packaged executable. Your home, settings, and sessions are not touched.
Older binaries may not understand newer session data; no database rollback is provided.
`;

type Release = {
  tag_name?: unknown;
  draft?: unknown;
  prerelease?: unknown;
  assets?: unknown;
};
type Asset = { name?: unknown; browser_download_url?: unknown; digest?: unknown; size?: unknown };
type Dependency = { fetch?: typeof fetch; apiBase?: string; releaseBase?: string };
type UpdateOptions = {
  args: string[];
  currentVersion: string;
  executable: string;
  platform: string;
  arch: string;
  packaged: boolean;
  output?: (message: string) => void;
};

function parseVersion(value: string): number[] {
  const match = VERSION.exec(value);
  if (!match) throw new Error(`Invalid release version: ${value}`);
  return match.slice(1).map(Number);
}

function compareVersion(a: string, b: string): number {
  const left = parseVersion(a);
  const right = parseVersion(b);
  for (let i = 0; i < 3; i++) if (left[i] !== right[i]) return left[i] > right[i] ? 1 : -1;
  return 0;
}

async function stream(response: Response, limit: number, chunk: (bytes: Uint8Array) => void): Promise<void> {
  if (!response.ok || !response.body) throw new Error(`Release download failed (HTTP ${response.status})`);
  const length = response.headers.get("content-length");
  if (length && (!/^\d+$/.test(length) || Number(length) > limit)) throw new Error("Release download exceeds size limit");
  let size = 0;
  for await (const bytes of response.body) {
    size += bytes.length;
    if (size > limit) throw new Error("Release download exceeds size limit");
    chunk(bytes);
  }
  if (length && size !== Number(length)) throw new Error("Release download is truncated");
}

async function text(response: Response, limit: number): Promise<string> {
  const parts: Uint8Array[] = [];
  await stream(response, limit, bytes => parts.push(bytes));
  return Buffer.concat(parts).toString("utf8");
}

function asset(release: Release, name: string, releaseBase: string, tag: string): Asset {
  if (!Array.isArray(release.assets)) throw new Error("Release has no published assets");
  const matches = release.assets.filter((item: Asset) => item && item.name === name) as Asset[];
  if (matches.length !== 1) throw new Error(`Release asset missing or duplicated: ${name}`);
  const found = matches[0];
  if (found.browser_download_url !== `${releaseBase}/${tag}/${name}` ||
      typeof found.digest !== "string" || !/^sha256:[a-f0-9]{64}$/i.test(found.digest) ||
      !Number.isSafeInteger(found.size) || (found.size as number) < 1) {
    throw new Error(`Untrusted release asset metadata: ${name}`);
  }
  return found;
}

function ownedTarget(path: string) {
  if (!isAbsolute(path) || basename(path) === "bun" || basename(path) === "bun.exe") {
    throw new Error("Update requires an installed packaged Arrodes executable, not Bun or a development launcher");
  }
  const info = lstatSync(path);
  if (!info.isFile() || info.nlink !== 1 || info.uid !== process.getuid?.() || (info.mode & 0o6000) !== 0) {
    throw new Error("Executable is not an owned regular file; refusing to replace it");
  }
  return info;
}

/** Only dependencies passed by an in-process caller can replace GitHub endpoints; no environment override. */
export async function runUpdate(options: UpdateOptions, dependency: Dependency = {}): Promise<void> {
  const output = options.output ?? (message => process.stdout.write(`${message}\n`));
  let check = false;
  let requested: string | undefined;
  for (let i = 0; i < options.args.length; i++) {
    const arg = options.args[i];
    if (arg === "--help" || arg === "-h") { output(UPDATE_HELP.trimEnd()); return; }
    if (arg === "--check" && !check) check = true;
    else if (arg === "--version" && requested === undefined) {
      requested = options.args[++i];
      if (!requested) throw new Error("update --version requires a version");
      parseVersion(requested);
    } else if (arg !== "--check") throw new Error(`Unknown update option: ${arg}`);
    else throw new Error("Duplicate update --check option");
  }
  if (!options.packaged) throw new Error("Self-update requires a packaged Arrodes executable. Install one with install.sh; a source checkout cannot replace Bun.");
  if (!(["darwin", "linux"].includes(options.platform) && ["arm64", "x64"].includes(options.arch))) {
    throw new Error(`No published executable for ${options.platform}-${options.arch}`);
  }
  parseVersion(options.currentVersion);
  const target = ownedTarget(options.executable);
  const fetcher = dependency.fetch ?? fetch;
  const api = dependency.apiBase ?? API;
  const releaseBase = dependency.releaseBase ?? RELEASES;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(new Error("Update timed out; original executable left unchanged")), 10 * 60_000);
  timeout.unref();
  const interrupt = () => controller.abort(new Error("Update interrupted; original executable left unchanged"));
  process.on("SIGINT", interrupt);
  process.on("SIGTERM", interrupt);
  const get = (url: string) => fetcher(url, { signal: controller.signal, headers: { "Accept": "application/vnd.github+json", "User-Agent": "arrodes-updater" } });
  let stage: string | undefined;
  let lock: string | undefined;
  try {
    const release = JSON.parse(await text(await get(requested ? `${api}/releases/tags/v${requested.replace(/^v/, "")}` : `${api}/releases/latest`), MAX_METADATA)) as Release;
    const tag = release.tag_name;
    if (typeof tag !== "string" || !/^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(tag) ||
        release.draft !== false || release.prerelease !== false ||
        (requested && compareVersion(tag, requested) !== 0)) throw new Error("Release metadata does not match a published stable version");
    if (!requested && compareVersion(tag, options.currentVersion) < 0) {
      throw new Error(`Latest release ${tag} is older than installed v${options.currentVersion}; refusing automatic downgrade`);
    }
    if (compareVersion(tag, options.currentVersion) === 0) { output(`Arrodes ${tag} is already installed.`); return; }
    const name = `arrodes-${options.platform}-${options.arch}`;
    const binary = asset(release, name, releaseBase, tag);
    const checksum = asset(release, `${name}.sha256`, releaseBase, tag);
    if (binary.size as number > MAX_BINARY || checksum.size as number > 1024) throw new Error("Release asset exceeds size limit");
    const checksumText = await text(await get(checksum.browser_download_url as string), 1024);
    if (Buffer.byteLength(checksumText) !== checksum.size ||
        createHash("sha256").update(checksumText).digest("hex") !== (checksum.digest as string).slice(7).toLowerCase()) {
      throw new Error("Release checksum asset mismatch");
    }
    const match = /^([a-f0-9]{64})  (arrodes-(?:darwin|linux)-(?:arm64|x64))\n$/i.exec(checksumText);
    if (!match || match[2] !== name || match[1].toLowerCase() !== (binary.digest as string).slice(7).toLowerCase()) {
      throw new Error("Release checksum does not match the selected executable");
    }
    if (check) { output(`Arrodes ${tag} is available (installed v${options.currentVersion}).`); return; }
    // Exclusive lock prevents two updaters from racing the same installed executable.
    const lockPath = join(dirname(options.executable), `.${basename(options.executable)}.update.lock`);
    let lockFd: number;
    try {
      lockFd = openSync(lockPath, "wx", 0o600);
    } catch (error) {
      if (error instanceof Error && "code" in error && error.code === "EEXIST") {
        throw new Error(`Another updater owns ${lockPath}; if none is running, remove the stale lock manually`);
      }
      throw error;
    }
    lock = lockPath;
    closeSync(lockFd);
    stage = join(dirname(options.executable), `.${basename(options.executable)}.update-${process.pid}-${crypto.randomUUID()}`);
    const fd = openSync(stage, "wx", 0o600);
    const hasher = createHash("sha256");
    let count = 0;
    try {
      await stream(await get(binary.browser_download_url as string), MAX_BINARY, bytes => {
        count += bytes.length;
        hasher.update(bytes);
        for (let offset = 0; offset < bytes.length;) offset += writeSync(fd, bytes, offset);
      });
      if (count !== binary.size || hasher.digest("hex") !== match[1].toLowerCase()) throw new Error("Release executable checksum or size mismatch; original left unchanged");
      chmodSync(stage, target.mode & 0o777);
      fsyncSync(fd);
      const current = ownedTarget(options.executable);
      if (current.dev !== target.dev || current.ino !== target.ino || current.size !== target.size || current.mtimeMs !== target.mtimeMs) {
        throw new Error("Installed executable changed during update; refusing replacement");
      }
      if (controller.signal.aborted) throw controller.signal.reason;
      renameSync(stage, options.executable);
      stage = undefined;
      output(`Updated Arrodes to ${tag}. Restart Arrodes to use it.${compareVersion(tag, options.currentVersion) < 0 ? " Older binaries may not read newer session data; databases are not rolled back." : ""}`);
    } finally {
      closeSync(fd);
    }
  } finally {
    clearTimeout(timeout);
    if (stage) rmSync(stage, { force: true });
    if (lock) rmSync(lock, { force: true });
    process.off("SIGINT", interrupt);
    process.off("SIGTERM", interrupt);
  }
}
