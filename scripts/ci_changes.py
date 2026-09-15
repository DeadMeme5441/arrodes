#!/usr/bin/env python3
"""Classify docs-only changes; unknown or non-doc changes require native verification."""
import argparse
import subprocess


def native_required(names):
    docs = {"README.md", "AGENTS.md", "CHANGELOG.md", "SECURITY.md", "LICENSE"}
    return any(name not in docs and not name.startswith(("docs/", ".agents/skills/")) for name in names)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base")
    args = parser.parse_args()
    required = True
    if args.base and set(args.base) != {"0"}:
        result = subprocess.run(["git", "diff", "--name-only", args.base, "HEAD"], capture_output=True, text=True)
        if result.returncode == 0:
            required = native_required(result.stdout.splitlines())
    print("native=" + str(required).lower())
