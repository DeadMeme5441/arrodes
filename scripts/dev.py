#!/usr/bin/env python3
"""Local verification and isolated packaged previews. Never pushes or publishes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["check", "test", "package", "preview"])
    args = parser.parse_args()
    report = {"format": 1, "action": args.action, "version": json.loads((ROOT / "package.json").read_text())["version"],
              "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)), "checks": []}
    output = ROOT / "target" / "verification"
    output.mkdir(parents=True, exist_ok=True)
    def run(label, command):
        print(f"== {label} ==", flush=True)
        start = time.monotonic()
        result = subprocess.run(command, cwd=ROOT)
        report["checks"].append({"name": label, "status": "passed" if result.returncode == 0 else "failed",
                                 "seconds": round(time.monotonic() - start, 2)})
        if result.returncode:
            raise RuntimeError(f"{label} failed ({result.returncode})")
    try:
        if args.action in {"check", "test", "preview"}:
            run("Project contracts", [sys.executable, "scripts/check-project.py"])
            run("Script tests", [sys.executable, "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"])
            run("Installer contracts", [sys.executable, "scripts/verify-install.py"])
        if args.action in {"test", "preview"}:
            run("Core behavior", ["clojure", "-Srepro", "-M:test"])
            run("Native TUI and controller", ["bun", "scripts/test-tui.ts"])
            run("Source RPC", [sys.executable, "scripts/verify-rpc.py"])
        if args.action in {"package", "preview"}:
            # Fail before a costly build if the smoke-test prerequisites are unavailable.
            run("Packaged smoke prerequisites", [sys.executable, "-c", "import pyte, wcwidth"])
            run("Self-contained executable", ["bun", "scripts/build-release.ts"])
            target = {"Darwin": "darwin", "Linux": "linux"}.get(platform.system())
            arch = {"arm64": "arm64", "aarch64": "arm64", "x86_64": "x64", "AMD64": "x64"}.get(platform.machine())
            binary = ROOT / "target/release" / f"arrodes-{target}-{arch}"
            run("Artifact manifest", [sys.executable, "scripts/release_manifest.py", "validate",
                                      str(binary.relative_to(ROOT)) + ".manifest.json",
                                      "--executable", str(binary.relative_to(ROOT)),
                                      "--payload", "target/release-build/payload.tar.gz",
                                      "--version", report["version"],
                                      "--commit", report["commit"], "--platform", target, "--arch", arch])
            run("Installed executable", [sys.executable, "scripts/verify-release.py", str(binary.relative_to(ROOT))])
            preview = ROOT / "target/preview"
            preview.mkdir(parents=True, exist_ok=True)
            name = "arrodes-preview"
            shutil.copy2(binary, preview / name)
            shutil.copy2(Path(str(binary) + ".manifest.json"), preview / "manifest.json")
            (preview / name).chmod(0o755)
            launcher = preview / "start"
            shutil.copy2(ROOT / "scripts/preview-launcher.sh", launcher)
            launcher.chmod(0o755)
            report["artifact"] = {"file": "target/preview/arrodes-preview", "sha256": hashlib.sha256(binary.read_bytes()).hexdigest()}
            print("Preview ready: target/preview/start (separate state; packaged runtime).", flush=True)
        report["status"] = "passed"
    except (RuntimeError, OSError) as error:
        report["status"] = "failed"
        print(str(error), file=sys.stderr)
    finally:
        (output / f"{args.action}.json").write_text(json.dumps(report, indent=2) + "\n")
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
