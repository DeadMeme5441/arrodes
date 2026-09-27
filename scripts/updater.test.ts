import { createHash } from "node:crypto";
import { linkSync, lstatSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { gzipSync } from "node:zlib";
import { afterEach, expect, test } from "bun:test";
import { runUpdate } from "./updater";

const roots: string[] = [];
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }); });
const digest = (value: string) => createHash("sha256").update(value).digest("hex");

function fixture(body = "new verified binary", tag = "v0.1.8") {
  const root = mkdtempSync(join(tmpdir(), "arrodes-update-"));
  roots.push(root);
  const executable = join(root, "arrodes");
  writeFileSync(executable, "original binary", { mode: 0o750 });
  const name = "arrodes-darwin-arm64";
  const checksum = `${digest(body)}  ${name}\n`;
  const base = "http://127.0.0.1:1";
  const release = { tag_name: tag, draft: false, prerelease: false, assets: [
    { name, browser_download_url: `${base}/${tag}/${name}`, digest: `sha256:${digest(body)}`, size: Buffer.byteLength(body) },
    { name: `${name}.sha256`, browser_download_url: `${base}/${tag}/${name}.sha256`, digest: `sha256:${digest(checksum)}`, size: Buffer.byteLength(checksum) },
  ] };
  const output: string[] = [];
  let binaryRequests = 0;
  let binaryResponse = () => new Response(body);
  const fetcher: typeof fetch = async (url) => {
    const path = String(url);
    if (path.endsWith("/releases/latest") || path.includes("/releases/tags/")) return Response.json(release);
    if (path.endsWith(".sha256")) return new Response(checksum);
    if (path.endsWith(`/${name}`)) { binaryRequests++; return binaryResponse(); }
    throw new Error(`Unexpected fixture URL: ${path}`);
  };
  const options = { args: [] as string[], currentVersion: "0.1.7", executable,
    platform: "darwin", arch: "arm64", packaged: true,
    output: (line: string) => output.push(line) };
  const dependencies = { fetch: fetcher, apiBase: base, releaseBase: base };
  return { root, executable, release, options, dependencies, output,
    binaryRequests: () => binaryRequests, setBinaryResponse: (respond: () => Response) => { binaryResponse = respond; } };
}

test("check and installed-version noop preserve executable and do not download it", async () => {
  const f = fixture();
  await runUpdate({ ...f.options, args: ["--check"] }, f.dependencies);
  expect(f.output.join(" ")).toContain("v0.1.8 is available");
  expect(f.binaryRequests()).toBe(0);
  expect(readFileSync(f.executable, "utf8")).toBe("original binary");
  f.release.tag_name = "v0.1.7";
  await runUpdate(f.options, f.dependencies);
  expect(f.output.join(" ")).toContain("already installed");
  expect(readdirSync(f.root)).toEqual(["arrodes"]);
});

test("installs published bytes atomically, preserving executable mode and unrelated data", async () => {
  const f = fixture();
  const home = join(f.root, "settings.edn");
  writeFileSync(home, "keep");
  await runUpdate(f.options, f.dependencies);
  expect(readFileSync(f.executable, "utf8")).toBe("new verified binary");
  expect(lstatSync(f.executable).mode & 0o777).toBe(0o750);
  expect(readFileSync(home, "utf8")).toBe("keep");
  expect(f.binaryRequests()).toBe(1);
  expect(readdirSync(f.root).sort()).toEqual(["arrodes", "settings.edn"]);
});

test("local HTTP fixture installs without any production endpoint override", async () => {
  const f = fixture();
  const server = Bun.serve({
    port: 0,
    fetch(request) {
      const path = new URL(request.url).pathname;
      if (path === "/releases/latest") return Response.json(f.release);
      if (path.endsWith(".sha256")) return new Response(`${digest("new verified binary")}  arrodes-darwin-arm64\n`);
      if (path.endsWith("/arrodes-darwin-arm64")) return new Response("new verified binary");
      return new Response("missing", { status: 404 });
    },
  });
  try {
    const base = `http://127.0.0.1:${server.port}`;
    for (const item of f.release.assets) item.browser_download_url = item.browser_download_url.replace("http://127.0.0.1:1", base);
    await runUpdate(f.options, { apiBase: base, releaseBase: base });
    expect(readFileSync(f.executable, "utf8")).toBe("new verified binary");
  } finally {
    server.stop(true);
  }
});

function gzipResponse(text: string): Response {
  const encoded = gzipSync(text);
  return new Response(encoded, {
    headers: { "content-encoding": "gzip", "content-length": String(encoded.byteLength) },
  });
}

test("HTTP-compressed metadata and assets install the verified decoded executable", async () => {
  const body = "verified executable bytes\n";
  const f = fixture(body);
  const server = Bun.serve({
    port: 0,
    fetch(request) {
      const path = new URL(request.url).pathname;
      if (path === "/releases/latest") return gzipResponse(JSON.stringify(f.release));
      if (path.endsWith(".sha256")) return gzipResponse(`${digest(body)}  arrodes-darwin-arm64\n`);
      if (path.endsWith("/arrodes-darwin-arm64")) return gzipResponse(body);
      return new Response("missing", { status: 404 });
    },
  });
  try {
    const base = `http://127.0.0.1:${server.port}`;
    for (const item of f.release.assets) item.browser_download_url = item.browser_download_url.replace("http://127.0.0.1:1", base);
    await runUpdate(f.options, { apiBase: base, releaseBase: base });
    expect(readFileSync(f.executable, "utf8")).toBe(body);
    expect(lstatSync(f.executable).mode & 0o777).toBe(0o750);
    expect(readdirSync(f.root)).toEqual(["arrodes"]);
  } finally {
    server.stop(true);
  }
});

test("compressed metadata still enforces the decoded size limit before installation", async () => {
  const body = "verified executable bytes\n";
  const f = fixture(body);
  const server = Bun.serve({
    port: 0,
    fetch(request) {
      const path = new URL(request.url).pathname;
      if (path === "/releases/latest") {
        return gzipResponse(JSON.stringify({ ...f.release, body: "x".repeat(1024 * 1024) }));
      }
      if (path.endsWith(".sha256")) return gzipResponse(`${digest(body)}  arrodes-darwin-arm64\n`);
      if (path.endsWith("/arrodes-darwin-arm64")) return gzipResponse(body);
      return new Response("missing", { status: 404 });
    },
  });
  try {
    const base = `http://127.0.0.1:${server.port}`;
    for (const item of f.release.assets) item.browser_download_url = item.browser_download_url.replace("http://127.0.0.1:1", base);
    await expect(runUpdate(f.options, { apiBase: base, releaseBase: base })).rejects.toThrow();
    expect(readFileSync(f.executable, "utf8")).toBe("original binary");
    expect(readdirSync(f.root)).toEqual(["arrodes"]);
  } finally {
    server.stop(true);
  }
});

test("checksum mismatch and truncated or failed downloads preserve original and clean staging", async () => {
  for (const respond of [() => new Response("tampered binary"),
    () => new Response("new verified binary", { headers: { "content-length": "100" } }),
    () => new Response("unavailable", { status: 503 })]) {
    const f = fixture();
    f.setBinaryResponse(respond);
    await expect(runUpdate(f.options, f.dependencies)).rejects.toThrow();
    expect(readFileSync(f.executable, "utf8")).toBe("original binary");
    expect(readdirSync(f.root)).toEqual(["arrodes"]);
  }
});

test("interruption before replacement preserves executable and removes owned staging", async () => {
  const f = fixture();
  f.setBinaryResponse(() => {
    process.emit("SIGINT");
    return new Response("new verified binary");
  });
  await expect(runUpdate(f.options, f.dependencies)).rejects.toThrow();
  expect(readFileSync(f.executable, "utf8")).toBe("original binary");
  expect(readdirSync(f.root)).toEqual(["arrodes"]);
});

test("external executable changes during download are never overwritten", async () => {
  const f = fixture();
  f.setBinaryResponse(() => {
    writeFileSync(f.executable, "externally replaced executable");
    return new Response("new verified binary");
  });
  await expect(runUpdate(f.options, f.dependencies)).rejects.toThrow();
  expect(readFileSync(f.executable, "utf8")).toBe("externally replaced executable");
  expect(readdirSync(f.root)).toEqual(["arrodes"]);
});

test("rejects untrusted releases, wrong explicit versions, and automatic downgrades", async () => {
  const f = fixture();
  await expect(runUpdate({ ...f.options, args: ["--version", "0.1.9"] }, f.dependencies)).rejects.toThrow("metadata");
  f.release.prerelease = true;
  await expect(runUpdate(f.options, f.dependencies)).rejects.toThrow("metadata");
  f.release.prerelease = false;
  f.release.tag_name = "v0.1.6";
  await expect(runUpdate(f.options, f.dependencies)).rejects.toThrow("downgrade");
  expect(readFileSync(f.executable, "utf8")).toBe("original binary");
});

test("rejects mismatched checksum, duplicate artifact metadata and oversized binary before replacement", async () => {
  const f = fixture();
  f.release.assets[0].digest = `sha256:${"0".repeat(64)}`;
  await expect(runUpdate(f.options, f.dependencies)).rejects.toThrow("checksum");
  f.release.assets.push({ ...f.release.assets[0] });
  await expect(runUpdate(f.options, f.dependencies)).rejects.toThrow("duplicated");
  f.release.assets.pop();
  f.release.assets[0].size = 1024 * 1024 * 1024 + 1;
  await expect(runUpdate(f.options, f.dependencies)).rejects.toThrow("size limit");
  expect(readFileSync(f.executable, "utf8")).toBe("original binary");
  expect(readdirSync(f.root)).toEqual(["arrodes"]);
});

test("rejects development Bun target, symlink, unowned ownership and simultaneous update", async () => {
  const f = fixture();
  await expect(runUpdate({ ...f.options, packaged: false }, f.dependencies)).rejects.toThrow("packaged");
  const symlink = join(f.root, "symlink");
  symlinkSync(f.executable, symlink);
  await expect(runUpdate({ ...f.options, executable: symlink }, f.dependencies)).rejects.toThrow("owned regular file");
  await expect(runUpdate({ ...f.options, executable: process.execPath }, f.dependencies)).rejects.toThrow();
  const hardlink = join(f.root, "hardlink");
  linkSync(f.executable, hardlink);
  await expect(runUpdate(f.options, f.dependencies)).rejects.toThrow("owned regular file");
  rmSync(hardlink);
  const lock = join(f.root, ".arrodes.update.lock");
  writeFileSync(lock, "other updater");
  await expect(runUpdate(f.options, f.dependencies)).rejects.toThrow();
  expect(readFileSync(lock, "utf8")).toBe("other updater");
  expect(readFileSync(f.executable, "utf8")).toBe("original binary");
});

test("explicit older release warns about incompatible data and does not roll anything back", async () => {
  const f = fixture("older verified binary", "v0.1.6");
  await runUpdate({ ...f.options, args: ["--version", "v0.1.6"] }, f.dependencies);
  expect(readFileSync(f.executable, "utf8")).toBe("older verified binary");
  expect(f.output.join(" ")).toContain("databases are not rolled back");
});
