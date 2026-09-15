#!/usr/bin/env python3
"""Keep executable version mirrors aligned with package.json; never commit or tag."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
NUMBER = r"(?:0|[1-9][0-9]*)"
PRERELEASE = r"(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)"
VERSION = re.compile(rf"{NUMBER}\.{NUMBER}\.{NUMBER}(?:-{PRERELEASE}(?:\.{PRERELEASE})*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?")
MIRRORS = {
    "build.clj": r'(\(def version ")([^"]+)("\))',
    "hosts/rpc/arrodes/commands.clj": r'(\(def version ")([^"]+)("\))',
    "hosts/rpc/arrodes/rpc.clj": r'(\(def version ")([^"]+)("\))',
    "src/cljs/arrodes/tui.cljs": r'(\(println "Arrodes )([^"]+)("\))',
    "src/clj/arrodes/mcp.clj": r'(make-info "Arrodes" ")([^"]+)(")',
    "resources/arrodes/scope.edn": r'(:version ")([^"]+)(")',
}


def version(root=ROOT):
    return json.loads((root / "package.json").read_text())["version"]


def check(root=ROOT):
    current = version(root)
    if not VERSION.fullmatch(current):
        raise ValueError("Invalid application version")
    for name, pattern in MIRRORS.items():
        matches = list(re.finditer(pattern, (root / name).read_text()))
        if len(matches) != 1 or matches[0][2] != current:
            raise ValueError(f"Version drift: {name}")
    return current


def set_version(value, root=ROOT):
    if not VERSION.fullmatch(value):
        raise ValueError("Expected a semantic version")
    check(root)
    pending = {}
    for name, pattern in MIRRORS.items():
        pending[root / name] = re.sub(pattern, lambda m: m[1] + value + m[3], (root / name).read_text())
    package = root / "package.json"
    manifest = json.loads(package.read_text())
    manifest["version"] = value
    pending[package] = json.dumps(manifest, indent=2) + "\n"
    for path, content in pending.items():
        path.write_text(content)
    return check(root)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["check", "set"])
    parser.add_argument("value", nargs="?")
    args = parser.parse_args()
    if args.action == "set" and not args.value:
        parser.error("set requires a version")
    print("Application version:", set_version(args.value) if args.action == "set" else check())
