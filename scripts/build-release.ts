import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { basename, delimiter, dirname, join, relative, resolve, sep } from "node:path";

const BUN_VERSION = "1.3.14";
const SUPPORTED: Record<string, readonly string[]> = {
  darwin: ["arm64", "x64"],
  linux: ["arm64", "x64"],
  win32: ["x64"],
};
const RELEASE_PLATFORM: Record<string, string> = { darwin: "darwin", linux: "linux", win32: "windows" };
const NATIVE_LIBRARY: Record<string, string> = { darwin: "libopentui.dylib", linux: "libopentui.so", win32: "opentui.dll" };
const NPM_PACKAGES = [
  "@opentui/core",
  "bun-ffi-structs",
  "diff",
  "marked",
  "string-width",
  "strip-ansi",
  "ansi-regex",
  "emoji-regex",
  "get-east-asian-width",
  "web-tree-sitter",
] as const;
const BUN_LICENSE = `Bun itself is MIT-licensed.

## JavaScriptCore

Bun statically links JavaScriptCore (and WebKit) which is LGPL-2 licensed. WebCore files from WebKit are also licensed under LGPL2. Per LGPL2:

> (1) If you statically link against an LGPL’d library, you must also provide your application in an object (not necessarily source) format, so that a user has the opportunity to modify the library and relink the application.

You can find the patched version of WebKit used by Bun here: <https://github.com/oven-sh/webkit>. If you would like to relink Bun with changes:

- \`git submodule update --init --recursive\`
- \`make jsc\`
- \`zig build\`

This compiles JavaScriptCore, compiles Bun’s \`.cpp\` bindings for JavaScriptCore (which are the object files using JavaScriptCore) and outputs a new \`bun\` binary with your changes.

## Linked libraries

| Library | License |
|---------|---------|
| boringssl | several licenses |
| brotli | MIT |
| libarchive | several licenses |
| lol-html | BSD 3-Clause |
| ls-hpack | MIT |
| ls-qpack | MIT |
| lsquic | MIT / BSD 3-Clause |
| mimalloc | MIT |
| picohttp | Perl License or MIT |
| zstd | BSD or GPLv2 |
| simdutf | Apache 2.0 |
| tinycc | LGPL v2.1 |
| uSockets | Apache 2.0 |
| zlib-ng | zlib |
| c-ares | MIT |
| libicu | ICU |
| libbase64 | BSD 2-Clause |
| libuv (Windows) | MIT |
| libdeflate | MIT |
| libjpeg-turbo | BSD 3-Clause / IJG / zlib |
| libspng | BSD 2-Clause |
| libwebp | BSD 3-Clause |
| highway | Apache 2.0 |
| uucode | MIT |
| uWebSockets fork | Apache 2.0 |
| TigerBeetle IO code | Apache 2.0 |
| LLVM libc++abi fallback | Apache 2.0 with LLVM exception |

## Polyfills

The assert, browserify-zlib, buffer, constants-browserify, crypto-browserify, domain-browser, events, https-browserify, os-browserify, path-browserify, process, punycode, querystring-es3, stream-browserify, stream-http, string_decoder, timers-browserify, tty-browserify, url, util, and vm-browserify compatibility polyfills are MIT licensed.

## Additional credits

Bun's JS transpiler, CSS lexer, and Node.js module resolver source code is a Zig port of esbuild. This notice is reproduced from <https://github.com/oven-sh/bun/blob/bun-v1.3.14/LICENSE.md>.
`;

type CommandOptions = { cwd?: string; env?: Record<string, string | undefined>; quiet?: boolean };

function run(command: string[], options: CommandOptions = {}): string {
  const result = Bun.spawnSync(command, {
    cwd: options.cwd,
    env: options.env ?? process.env,
    stdin: "inherit",
    stdout: options.quiet ? "pipe" : "inherit",
    stderr: options.quiet ? "pipe" : "inherit",
  });
  if (result.exitCode !== 0) {
    const detail = options.quiet ? `\n${result.stderr.toString().trim()}` : "";
    throw new Error(`${command[0]} exited with status ${result.exitCode}${detail}`);
  }
  return options.quiet ? result.stdout.toString().trim() : "";
}

function copy(source: string, destination: string): void {
  if (!existsSync(source)) throw new Error(`Required release input is missing: ${relative(process.cwd(), source)}`);
  mkdirSync(dirname(destination), { recursive: true });
  cpSync(source, destination, { recursive: true, dereference: true });
}

function parseJavaRelease(path: string): Record<string, string> {
  const values: Record<string, string> = {};
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    const match = /^([A-Z_]+)="(.*)"$/.exec(line);
    if (match) values[match[1]] = match[2];
  }
  return values;
}

function javaPlatform(value: string): string | undefined {
  if (/darwin|mac/i.test(value)) return "darwin";
  if (/linux/i.test(value)) return "linux";
  if (/windows/i.test(value)) return "win32";
}

function javaArch(value: string): string | undefined {
  if (/^(aarch64|arm64)$/i.test(value)) return "arm64";
  if (/^(amd64|x86_64|x64)$/i.test(value)) return "x64";
}

function walkFiles(root: string): string[] {
  const files: string[] = [];
  const visit = (directory: string) => {
    for (const entry of readdirSync(directory)) {
      const path = join(directory, entry);
      if (statSync(path).isDirectory()) visit(path);
      else files.push(path);
    }
  };
  visit(root);
  return files;
}

function copyNpmLicenses(root: string, licenses: string, nativePackage: string): void {
  for (const name of [...NPM_PACKAGES, nativePackage]) {
    const directory = join(root, "node_modules", ...name.split("/"));
    if (!existsSync(directory)) throw new Error(`Required package is not installed: ${name} (run bun install --frozen-lockfile)`);
    const notices = readdirSync(directory).filter(file => /^(license|notice|copying|patents|authors)/i.test(file));
    if (notices.length === 0) throw new Error(`Installed package has no license notice: ${name}`);
    for (const notice of notices) copy(join(directory, notice), join(licenses, "npm", name, notice));
  }
}

function copyMavenLicenses(root: string, javaHome: string, licenses: string, buildRoot: string): void {
  const classpath = run(["clojure", "-Srepro", "-Spath", "-M:host"], { cwd: root, quiet: true });
  const jars = [...new Set(classpath.split(delimiter).filter(path => path.endsWith(".jar") && existsSync(path)))];
  const inventory: string[] = [];
  for (let index = 0; index < jars.length; index++) {
    const jar = jars[index];
    const label = `${String(index + 1).padStart(2, "0")}-${basename(jar).replace(/[^A-Za-z0-9._-]/g, "_")}`;
    inventory.push(label.slice(3));
    const entries = run([join(javaHome, "bin", process.platform === "win32" ? "jar.exe" : "jar"), "--list", "--file", jar], { quiet: true })
      .split(/\r?\n/)
      .filter(entry => entry.startsWith("META-INF/") && !entry.includes("\\") && !entry.split("/").includes("..") && !entry.endsWith("/") && (
        /(^|\/)META-INF\/.*(LICENSE|NOTICE|COPYING|COPYRIGHT|DEPENDENCIES|EPL|APACHE|BSD|MIT)([._/-].*)?$/i.test(entry)
        || /(^|\/)META-INF\/maven\/.*\/pom\.(xml|properties)$/i.test(entry)
      ));
    if (entries.length === 0) continue;
    const temporary = join(buildRoot, "maven-license", label);
    mkdirSync(temporary, { recursive: true });
    run([join(javaHome, "bin", process.platform === "win32" ? "jar.exe" : "jar"), "--extract", "--file", jar, ...entries], { cwd: temporary, quiet: true });
    for (const entry of entries) copy(join(temporary, ...entry.split("/")), join(licenses, "maven", label, ...entry.split("/")));
  }
  writeFileSync(join(licenses, "MAVEN-DEPENDENCIES.txt"), `${inventory.sort().join("\n")}\n`);
}

function inspectMacRuntime(javaRoot: string): void {
  if (process.platform !== "darwin") return;
  const candidates = walkFiles(javaRoot).filter(path => path.includes(`${sep}bin${sep}`) || path.endsWith(".dylib"));
  for (const path of candidates) {
    const result = Bun.spawnSync(["/usr/bin/otool", "-L", path], { stdout: "pipe", stderr: "pipe" });
    if (result.exitCode !== 0) continue;
    for (const line of result.stdout.toString().split(/\r?\n/).slice(1)) {
      const dependency = line.trim().split(/\s+/)[0];
      if (dependency?.startsWith("/") && !dependency.startsWith("/usr/lib/") && !dependency.startsWith("/System/Library/")) {
        throw new Error(`jlink produced a non-portable absolute dependency in ${relative(javaRoot, path)}: ${dependency}`);
      }
    }
  }
}

async function sha256(path: string): Promise<string> {
  const hasher = new Bun.CryptoHasher("sha256");
  for await (const chunk of Bun.file(path).stream()) hasher.update(chunk);
  return hasher.digest("hex");
}

async function archiveEntries(root: string): Promise<Record<string, Uint8Array>> {
  const entries: Record<string, Uint8Array> = {};
  for (const path of walkFiles(root)) {
    const name = relative(root, path).split(sep).join("/");
    if (name.startsWith("/") || name.split("/").includes("..")) throw new Error(`Unsafe payload path: ${name}`);
    entries[name] = await Bun.file(path).bytes();
  }
  return entries;
}

async function main(): Promise<void> {
  if (process.versions.bun !== BUN_VERSION) throw new Error(`Release builds require Bun ${BUN_VERSION}; found ${process.versions.bun}`);
  const supportedArchitectures = SUPPORTED[process.platform];
  if (!supportedArchitectures?.includes(process.arch)) throw new Error(`Unsupported release target: ${process.platform}-${process.arch}`);

  const root = resolve(import.meta.dir, "..");
  const manifest = JSON.parse(readFileSync(join(root, "package.json"), "utf8")) as { version: string };
  const version = manifest.version;
  const platform = RELEASE_PLATFORM[process.platform];
  const arch = process.arch;
  const nativePackage = `@opentui/core-${process.platform}-${arch}`;
  const nativeLibrary = NATIVE_LIBRARY[process.platform];
  const javaHome = process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME) : "";
  if (!javaHome) throw new Error("JAVA_HOME must name the JDK bundled into the release");
  for (const path of [join(javaHome, "release"), join(javaHome, "jmods"), join(javaHome, "bin", process.platform === "win32" ? "jlink.exe" : "jlink")]) {
    if (!existsSync(path)) throw new Error(`JAVA_HOME is not a complete JDK: ${path}`);
  }
  const java = parseJavaRelease(join(javaHome, "release"));
  if (!java.JAVA_VERSION?.startsWith("21.")) throw new Error(`Release builds require Java 21; found ${java.JAVA_VERSION ?? "unknown"}`);
  if (javaPlatform(java.OS_NAME ?? "") !== process.platform || javaArch(java.OS_ARCH ?? "") !== arch) {
    throw new Error(`JAVA_HOME target ${java.OS_NAME ?? "unknown"}-${java.OS_ARCH ?? "unknown"} does not match ${process.platform}-${arch}`);
  }

  const buildRoot = join(root, "target", "release-build");
  const payloadRoot = join(buildRoot, "payload");
  const archivePath = join(buildRoot, "payload.tar.gz");
  const releaseRoot = join(root, "target", "release");
  rmSync(buildRoot, { recursive: true, force: true });
  mkdirSync(payloadRoot, { recursive: true });
  mkdirSync(releaseRoot, { recursive: true });

  run(["clojure", "-Srepro", "-M:tui-build"], { cwd: root });
  run(["clojure", "-Srepro", "-T:build", "rpc"], { cwd: root });

  const javaRoot = join(payloadRoot, "java");
  run([
    join(javaHome, "bin", process.platform === "win32" ? "jlink.exe" : "jlink"),
    "--module-path", join(javaHome, "jmods"),
    "--add-modules", "ALL-MODULE-PATH",
    "--bind-services",
    "--strip-debug",
    "--no-man-pages",
    "--no-header-files",
    "--compress=zip-6",
    "--output", javaRoot,
  ]);
  run([join(javaRoot, "bin", process.platform === "win32" ? "java.exe" : "java"), "-version"], { quiet: true });
  inspectMacRuntime(javaRoot);

  copy(join(root, "target", "arrodes-rpc.jar"), join(payloadRoot, "arrodes-rpc.jar"));
  copy(join(root, "target", "tui", "main.cjs"), join(payloadRoot, "tui", "main.cjs"));
  copy(join(root, "node_modules", "@opentui", "core", "assets"), join(payloadRoot, "opentui", "@opentui", "core", "assets"));
  copy(join(root, "node_modules", "@opentui", "core", "parser.worker.js"), join(payloadRoot, "opentui", "@opentui", "core", "parser.worker.js"));
  copy(join(root, "node_modules", "web-tree-sitter", "tree-sitter.wasm"), join(payloadRoot, "opentui", "web-tree-sitter", "tree-sitter.wasm"));
  copy(join(root, "node_modules", ...nativePackage.split("/"), nativeLibrary), join(payloadRoot, "opentui", nativePackage, nativeLibrary));

  const licenses = join(payloadRoot, "licenses");
  copy(join(root, "LICENSE"), join(licenses, "Arrodes", "LICENSE"));
  mkdirSync(join(licenses, "Bun"), { recursive: true });
  writeFileSync(join(licenses, "Bun", "LICENSE.md"), BUN_LICENSE);
  copyNpmLicenses(root, licenses, nativePackage);
  copyMavenLicenses(root, javaHome, licenses, buildRoot);

  const gitCommit = process.env.GITHUB_SHA;
  if (gitCommit && !/^[0-9a-f]{7,64}$/i.test(gitCommit)) throw new Error("GITHUB_SHA must be a hexadecimal commit ID");
  const buildInfo = [
    `Arrodes: ${version}`,
    `Platform: ${platform}`,
    `Architecture: ${arch}`,
    `Bun: ${BUN_VERSION}`,
    `Java: ${java.JAVA_VERSION}`,
    `Java implementor: ${java.IMPLEMENTOR ?? "unknown"}`,
    ...(gitCommit ? [`Git commit: ${gitCommit}`] : []),
  ];
  writeFileSync(join(payloadRoot, "BUILD-INFO.txt"), `${buildInfo.join("\n")}\n`);

  await Bun.write(archivePath, new Bun.Archive(await archiveEntries(payloadRoot), { compress: "gzip", level: 9 }));
  const payloadDigest = await sha256(archivePath);
  const filename = `arrodes-${platform}-${arch}${process.platform === "win32" ? ".exe" : ""}`;
  const executable = join(releaseRoot, filename);
  const result = await Bun.build({
    entrypoints: [join(root, "scripts", "release-launcher.ts")],
    minify: true,
    define: {
      __ARRODES_VERSION__: JSON.stringify(version),
      __ARRODES_PLATFORM__: JSON.stringify(platform),
      __ARRODES_ARCH__: JSON.stringify(arch),
      __ARRODES_PAYLOAD_SHA256__: JSON.stringify(payloadDigest),
    },
    compile: {
      target: `bun-${process.platform === "win32" ? "windows" : process.platform}-${arch}` as Bun.Build.CompileTarget,
      outfile: executable,
      executablePath: process.execPath,
      autoloadDotenv: false,
      autoloadBunfig: false,
      autoloadPackageJson: false,
      autoloadTsconfig: false,
    },
  });
  if (!result.success) throw new Error(result.logs.map(log => log.message).join("\n"));
  const executableDigest = await sha256(executable);
  writeFileSync(`${executable}.sha256`, `${executableDigest}  ${filename}\n`);
  process.stdout.write(`${executable}\n${executable}.sha256\n`);
}

try {
  await main();
} catch (error) {
  process.stderr.write(`Release build failed: ${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
}
