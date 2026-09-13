#!/usr/bin/env python3
"""Reject machine-local paths, credentials, and accidental nested Git state."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

EXPECTED_NAME = "DeadMeme5441"
EXPECTED_EMAIL = "deadmeme5441@gmail.com"
EXPECTED_REMOTE = "https://github.com/DeadMeme5441/arrodes-mono.git"


def git(*args):
    env = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
    return subprocess.run(["git", *args], check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, env=env).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-git", action="store_true")
    args = parser.parse_args()
    files = set(git("ls-files", "--cached", "--others", "--exclude-standard", "-z").decode().split("\0"))
    patterns = {
        "machine user path": re.compile("/" + r"Users/[^/\s]+/|/" + r"home/[^/\s]+/|[A-Za-z]:\\Users\\[^\\\s]+\\"),
        "GitHub credential": re.compile(r"gh[pousr]_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{40,}"),
        "API credential": re.compile(r"sk-(?:proj-)?[A-Za-z0-9_-]{32,}"),
        "AWS access key": re.compile(r"AKIA[A-Z0-9]{16}"),
        "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    }
    errors = []
    checked = 0
    for filename in sorted(files - {""}):
        file = Path(filename)
        if ".git" in file.parts or file.name in {".gitconfig", ".env", "auth.json", "credentials.edn"}:
            errors.append(f"{filename}: private runtime/Git file")
        if not file.is_file():
            continue
        data = file.read_bytes()
        if b"\0" in data:
            continue
        text = data.decode("utf-8", errors="replace")
        checked += 1
        for label, pattern in patterns.items():
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                errors.append(f"{filename}:{line}: {label}")
    if args.check_git:
        for key, expected in [("user.name", EXPECTED_NAME), ("user.email", EXPECTED_EMAIL),
                              ("remote.origin.url", EXPECTED_REMOTE)]:
            actual = git("config", "--local", "--get", key).decode().strip()
            if actual != expected:
                errors.append(f"Git {key} does not match personal repository identity")
        try:
            identities = git("log", "--format=%an <%ae>|%cn <%ce>").decode().splitlines()
        except subprocess.CalledProcessError:
            identities = []
        expected = f"{EXPECTED_NAME} <{EXPECTED_EMAIL}>"
        if any(line != f"{expected}|{expected}" for line in identities):
            errors.append("Git history contains a different author or committer identity")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Privacy check passed: {checked} text files; no embedded machine paths or credentials.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
