#!/usr/bin/env python3
"""Build the complete source companion distributed with native releases."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
RELEASE = ROOT / "target" / "release"
OUTPUT = RELEASE / "arrodes-third-party-sources.tar.gz"
CHECKSUM = Path(f"{OUTPUT}.sha256")
TEMURIN_RELEASE = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1"


def github(name: str, repo: str, revision: str, version: str, license_id: str, purpose: str) -> dict[str, str]:
    return {
        "name": name,
        "version": version,
        "license": license_id,
        "revision": revision,
        "url": f"https://codeload.github.com/{repo}/tar.gz/{revision}",
        "archive": f"{name}-{revision}.tar.gz",
        "purpose": purpose,
    }


SOURCES = [
    github("bun", "oven-sh/bun", "0d9b296af33f2b851fcbf4df3e9ec89751734ba4", "1.3.14", "MIT AND bundled third-party licenses", "Bun launcher source, build system, dependency pins, and tinycc patch"),
    {
        "name": "webkit",
        "version": "Bun 1.3.14 pin",
        "license": "LGPL-2.0-or-later AND bundled third-party licenses",
        "revision": "5488984d20e0dbfe4be2c3ba8fb18eb81a5e0e8b",
        "url": "https://github.com/oven-sh/WebKit/commit/5488984d20e0dbfe4be2c3ba8fb18eb81a5e0e8b",
        "git_ref": "refs/tags/autobuild-5488984d20e0dbfe4be2c3ba8fb18eb81a5e0e8b",
        "git_url": "https://github.com/oven-sh/WebKit.git",
        "archive": "webkit-5488984d20e0dbfe4be2c3ba8fb18eb81a5e0e8b.tar.gz",
        "exclude_unlinked_directories": [
            ".claude", ".codex", ".gemini", "JSTests", "LayoutTests",
            "ManualTests", "PerformanceTests", "WebDriverTests", "Websites",
        ],
        "purpose": "Exact JavaScriptCore/WebKit source used by Bun; Git archive is generated because GitHub disables codeload for this large repository",
    },
    github("tinycc", "oven-sh/tinycc", "12882eee073cfe5c7621bcfadf679e1372d4537b", "Bun 1.3.14 pin", "LGPL-2.1-or-later", "Exact tinycc source used by Bun; Bun's patch is in the Bun archive"),
    {
        "name": "temurin-openjdk",
        "version": "21.0.12.1+1",
        "license": "GPL-2.0-only WITH Classpath-exception-2.0 and OpenJDK Assembly Exception",
        "revision": "1c417fbfc2f70ab03a565b0af0a5a3c6f5e15ad6",
        "url": f"{TEMURIN_RELEASE}/OpenJDK21U-jdk-sources_21.0.12.1_1.tar.gz",
        "archive": "OpenJDK21U-jdk-sources_21.0.12.1_1.tar.gz",
        "expected_sha256": "573057d03584ae793fb7ec9a14c76d826d9187a53efeefd99da47403a5308234",
        "purpose": "Official Temurin corresponding-source asset for the bundled jlink runtime",
    },
    github("temurin-build", "adoptium/temurin-build", "e6ba7dec3d07654074559310376a3ae89da5f4ac", "21.0.12.1+1 build", "Apache-2.0", "Vendor scripts used to build and package Temurin"),
    github("clj-yaml", "clj-commons/clj-yaml", "57c817a20910003583b0b0dde16a76ee101fd7e7", "1.0.29", "EPL-1.0", "EPL component source and build files"),
    github("plumcp", "plumce/plumcp", "f4111b46dc0677bb0a54865b501d59b82a181897", "0.2.2", "EPL-1.0", "Source and build files for both bundled PlumCP artifacts"),
    github("clojure", "clojure/clojure", "3bc2b3e91fdff462620d84bbdfd40379d9c562e5", "1.12.5", "EPL-1.0", "EPL component source, Java portions, and build files"),
    github("data.json", "clojure/data.json", "b5a5898fe49c0412732a5784cd01a60b1e7f29ca", "2.5.1", "EPL-1.0", "EPL component source and build files"),
    github("ordered", "clj-commons/ordered", "574e47018623b9f1b09b0f9b754b02d6633f3437", "1.15.12", "EPL-1.0", "EPL component source and build files"),
    github("malli", "metosin/malli", "59470f974d64c7971ec3a3300819d7f5b8155749", "0.17.0", "EPL-2.0", "EPL component source and build files"),
    github("core.specs.alpha", "clojure/core.specs.alpha", "bb3b3ba31cd3250aee37ac6471075b032384e1a5", "0.4.74", "EPL-1.0", "EPL component source and build files"),
    github("spec.alpha", "clojure/spec.alpha", "360abedca4ab2a471c305f168379db7657b0bcf8", "0.5.238", "EPL-1.0", "EPL component source and build files"),
    github("tigris", "dakrone/tigris", "458dd3dbe5bcff63e72aa1ff5931177ad9c3aecd", "0.1.2", "EPL-1.0", "EPL component source and build files"),
    github("dynaload", "borkdude/dynaload", "ad11c760fc5b9c7d8d7817f1433eecf09115b2d0", "0.3.5", "EPL-1.0", "EPL component source and build files"),
    github("edamame", "borkdude/edamame", "85c27fa80f93eb260a8a35b7ce15324a169b0f4d", "1.4.27", "EPL-1.0", "EPL component source and build files"),
    github("fipp", "brandonbloom/fipp", "9e4eb78a9056e4764b9b7dc8b86bd18f419a4d82", "0.6.27", "EPL-1.0", "EPL component source and build files"),
    github("test.check", "clojure/test.check", "50f816191dfa6185d8b72b5c7fd84fc56241d359", "1.1.1", "EPL-1.0", "EPL component source and build files"),
    github("tools.reader", "clojure/tools.reader", "bebdbc81924ebf967583284bd31d3853e920f230", "1.3.4", "EPL-1.0", "EPL component source and build files"),
    github("core.rrb-vector", "clojure/core.rrb-vector", "d90812665b7f5af502efd881d8a100c5b2186fd9", "0.1.2", "EPL-1.0", "EPL component source and build files"),
    github("clojure-llm-sdk", "DeadMeme5441/clojure-llm-sdk", "c59b19f993d1229016218c141592d09a3dc5a6ff", "0.6.0", "MIT", "Bundled pure-Clojure component source and build files"),
    github("arrangement", "greglook/clj-arrangement", "4428958f130e93a170735c4b792a801041fdf23e", "2.1.0", "Unlicense", "Bundled pure-Clojure component source and build files"),
]

for platform in ("aarch64_mac", "x64_mac", "aarch64_linux", "x64_linux"):
    filename = f"OpenJDK21U-jdk_{platform}_hotspot_21.0.12.1_1.tar.gz.json"
    SOURCES.append({
        "name": f"temurin-provenance-{platform}",
        "version": "21.0.12.1+1",
        "license": "metadata",
        "revision": "1c417fbfc2f70ab03a565b0af0a5a3c6f5e15ad6 / e6ba7dec3d07654074559310376a3ae89da5f4ac",
        "url": f"{TEMURIN_RELEASE}/{filename}",
        "archive": filename,
        "purpose": "Vendor build arguments and toolchain provenance for a supported release platform",
    })


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def materialize(source: dict, destination: Path) -> tuple[str, int]:
    partial = destination.with_suffix(destination.suffix + ".part")
    checkout = destination.parent / f".{source['name']}-git"
    try:
        if git_url := source.get("git_url"):
            ref = source["git_ref"].removeprefix("refs/tags/")
            subprocess.run([
                "git", "clone", "--quiet", "--depth=1", "--filter=blob:none",
                "--no-checkout", "--branch", ref, git_url, str(checkout),
            ], check=True)
            actual = subprocess.run(
                ["git", "-C", str(checkout), "rev-parse", "HEAD^{commit}"],
                check=True, text=True, stdout=subprocess.PIPE,
            ).stdout.strip()
            if actual != source["revision"]:
                raise RuntimeError(f"Revision mismatch for {source['name']}: expected {source['revision']}, got {actual}")
            excluded = set(source.get("exclude_unlinked_directories", []))
            entries = subprocess.check_output(
                ["git", "-C", str(checkout), "ls-tree", actual], text=True).splitlines()
            selected = []
            directories = []
            for entry in entries:
                metadata, name = entry.split("\t", 1)
                if name not in excluded:
                    selected.append(name)
                    if metadata.split()[1] == "tree":
                        directories.append(name)
            # Sparse checkout batches the required blob download instead of fetching
            # one object per archive entry; unlinked test corpora are not needed.
            subprocess.run(["git", "-C", str(checkout), "sparse-checkout", "set", "--cone", *directories], check=True)
            subprocess.run(["git", "-C", str(checkout), "checkout", "--quiet", "--detach", actual], check=True)
            subprocess.run([
                "git", "-C", str(checkout), "archive", "--format=tar.gz",
                f"--prefix={source['name']}-{source['revision']}/",
                "-o", str(partial), actual, "--", *selected,
            ], check=True)
        else:
            request = urllib.request.Request(source["url"], headers={"User-Agent": "arrodes-source-release/1"})
            with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as output:
                shutil.copyfileobj(response, output, length=1024 * 1024)
        digest = sha256(partial)
        expected = source.get("expected_sha256")
        if expected and digest != expected:
            raise RuntimeError(f"SHA-256 mismatch for {source['name']}: expected {expected}, got {digest}")
        partial.replace(destination)
        return digest, destination.stat().st_size
    finally:
        partial.unlink(missing_ok=True)
        if checkout.exists():
            shutil.rmtree(checkout)


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, text=True, stdout=subprocess.PIPE).stdout.strip()


def readme(commit: str) -> str:
    return f"""ARRODES SOURCE COMPANION

This archive accompanies the Arrodes native release built from Git commit {commit}.
It contains actual source archives, not a list of links. SOURCES.json records each
component, exact revision, authoritative download URL, observed byte size, and
SHA-256. SHA256SUMS verifies every file under archives/.

PACKAGING RESOURCES

GitHub disables generated codeload archives for WebKit, so the packager
shallow-fetches the exact tag and archives all library sources, root files,
configuration, and build support. Unlinked test corpora, benchmarks, websites,
and assistant configuration are excluded and listed explicitly in SOURCES.json.
Allow several gigabytes of temporary disk; exact archive sizes are recorded.

REBUILDING ARRODES

1. Extract archives/arrodes-{commit}.tar.gz.
2. Install Bun 1.3.14, Clojure CLI, and a matching Temurin 21 JDK; run
   `bun install --frozen-lockfile` in the extracted Arrodes tree.
3. Set JAVA_HOME to that JDK and run `bun scripts/build-release.ts` on each
   supported platform. The repository's build.clj, deps.edn, bun.lock,
   scripts/release-launcher.ts, and scripts/build-release.ts are included.

MODIFYING OR RELINKING BUN'S LGPL LIBRARIES

The Bun, WebKit/JavaScriptCore, and tinycc archives contain the exact revisions
used by Bun 1.3.14. Extract them into sibling directories named bun, webkit,
and tinycc. Follow Bun's CONTRIBUTING.md for the platform toolchain and set
BUN_WEBKIT_PATH to the absolute path of that webkit directory.

To rebuild with the included or modified tinycc, first apply Bun's exact patch
from the tinycc root with `patch -p0 < ../bun/patches/tinycc/tcc.h.patch`.
Then change tinycc's `source` entry in Bun's scripts/build/deps/tinycc.ts to
`source: () => ({{ kind: "local", path: process.env.BUN_TINYCC_PATH }})`, set
BUN_TINYCC_PATH to that patched tinycc directory and set its `patches` list to
[] to avoid applying the patch twice. The unmodified pin/build recipe remains
in the included Bun source for comparison.

Follow the included CONTRIBUTING.md toolchain requirements and run:
  bun scripts/build.ts --profile=release --webkit=local --build-dir=build/redistribution-local
This builds JavaScriptCore from BUN_WEBKIT_PATH and links the local libraries.
Use the resulting Bun executable to run Arrodes scripts/build-release.ts.
If a modified Bun has a different version stamp, update that script's
BUN_VERSION check to match your build. Source is supplied for the complete work
using the LGPL libraries rather than platform-specific object files.

REBUILDING THE TEMURIN RUNTIME

OpenJDK21U-jdk-sources_21.0.12.1_1.tar.gz is Adoptium's official source asset
for vendor commit 1c417fbfc2f70ab03a565b0af0a5a3c6f5e15ad6. The
Temurin-build archive contains the build and packaging scripts at commit
e6ba7dec3d07654074559310376a3ae89da5f4ac. The four included Temurin JSON files
record the published per-platform makejdk-any-platform arguments, configure
arguments, build commands, toolchain versions, source commit, and build commit.
Use those arguments with the included source and build scripts, then run jlink
with the options in Arrodes scripts/build-release.ts.

EPL COMPONENTS

Each EPL-1.0 or EPL-2.0 dependency is included as its full upstream repository
archive at the revision used for the bundled JAR, including its source and build
files. The pure-Clojure JARs also contain loadable source, but these repository
archives are supplied so their preferred source and build metadata stay together.

BOUNDARIES

Operating-system SDKs, compilers, boot JDKs, and other standard build tools are
not redistributed here. Bun's own build files identify and fetch its permissively
licensed dependencies; the separately linked LGPL components are included here.
No source-code offer or future service is made: the corresponding source and
rebuild inputs accompany the release in this archive.
"""


def main() -> None:
    commit = os.environ.get("GITHUB_SHA") or git("rev-parse", "HEAD")
    if not commit or any(character not in "0123456789abcdefABCDEF" for character in commit):
        raise RuntimeError("GITHUB_SHA/current Git commit must be hexadecimal")
    commit = git("rev-parse", f"{commit}^{{commit}}")
    if commit != git("rev-parse", "HEAD"):
        raise RuntimeError("Source materials must match the checked-out release commit")
    committed_version = json.loads(git("show", f"{commit}:package.json"))["version"]
    if committed_version != json.loads((ROOT / "package.json").read_text())["version"]:
        raise RuntimeError("Commit the release version before generating its source companion")
    if git("status", "--porcelain"):
        raise RuntimeError("Generate source materials from a clean committed checkout")
    RELEASE.mkdir(parents=True, exist_ok=True)
    OUTPUT.unlink(missing_ok=True)
    CHECKSUM.unlink(missing_ok=True)

    with tempfile.TemporaryDirectory(prefix="arrodes-sources-", dir=RELEASE) as temporary:
        kit = Path(temporary) / "arrodes-third-party-sources"
        archives = kit / "archives"
        archives.mkdir(parents=True)

        local_name = f"arrodes-{commit}.tar.gz"
        local_path = archives / local_name
        subprocess.run(
            ["git", "archive", "--format=tar.gz", f"--prefix=arrodes-{commit}/", "-o", str(local_path), commit],
            cwd=ROOT,
            check=True,
        )
        records: list[dict[str, object]] = [{
            "name": "arrodes",
            "version": json.loads((ROOT / "package.json").read_text())["version"],
            "license": "MIT",
            "revision": commit,
            "url": f"https://github.com/{os.environ.get('GITHUB_REPOSITORY', 'DeadMeme5441/arrodes')}/commit/{commit}",
            "archive": local_name,
            "purpose": "Complete tracked source and release build files for the work using Bun",
            "sha256": sha256(local_path),
            "bytes": local_path.stat().st_size,
        }]

        for source in SOURCES:
            destination = archives / source["archive"]
            print(f"Materializing {source['name']} from {source['url']}", flush=True)
            digest, size = materialize(source, destination)
            records.append({**source, "sha256": digest, "bytes": size})

        (kit / "SOURCES.json").write_text(json.dumps({"format": 1, "components": records}, indent=2) + "\n")
        sums = "".join(f"{record['sha256']}  archives/{record['archive']}\n" for record in records)
        (kit / "SHA256SUMS").write_text(sums)
        (kit / "README.txt").write_text(readme(commit))
        shutil.copy2(ROOT / "THIRD_PARTY_NOTICES.txt", kit / "THIRD_PARTY_NOTICES.txt")
        shutil.copytree(ROOT / "resources" / "licenses", kit / "licenses")

        temporary_output = Path(temporary) / OUTPUT.name
        with tarfile.open(temporary_output, "w:gz", compresslevel=1) as archive:
            archive.add(kit, arcname=kit.name)
        temporary_output.replace(OUTPUT)
        if OUTPUT.stat().st_size >= 2 * 1024**3:
            raise RuntimeError("Source companion exceeds GitHub's 2 GiB per-asset limit")

    digest = sha256(OUTPUT)
    CHECKSUM.write_text(f"{digest}  {OUTPUT.name}\n")
    shutil.copy2(ROOT / "THIRD_PARTY_NOTICES.txt", RELEASE / "THIRD_PARTY_NOTICES.txt")
    print(f"{OUTPUT} ({OUTPUT.stat().st_size} bytes)\n{CHECKSUM}\n{RELEASE / 'THIRD_PARTY_NOTICES.txt'}")


if __name__ == "__main__":
    main()
