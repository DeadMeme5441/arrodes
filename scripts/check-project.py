#!/usr/bin/env python3
"""Validate maintained documentation links, skill entry points, and version mirrors."""
from pathlib import Path
import re
import sys
from urllib.parse import unquote
import version

ROOT = Path(__file__).resolve().parent.parent


def check(root=ROOT):
    version.check(root)
    files = [root / "AGENTS.md", root / "README.md", root / "CONTRIBUTING.md", *sorted((root / "docs").rglob("*.md")),
             *sorted((root / ".agents/skills").rglob("SKILL.md"))]
    errors = []
    for path in files:
        content = path.read_text()
        content = re.sub(r"```.*?```", "", content, flags=re.S)
        for target in re.findall(r"\[[^\]]*\]\(([^\s)]+)\)", content):
            if target.startswith(("http:", "https:", "mailto:", "#")):
                continue
            destination = unquote(target.split("#")[0])
            if destination and not (path.parent / destination).exists():
                errors.append(f"Broken documentation link: {path.relative_to(root)} -> {target}")
        if path.name == "SKILL.md":
            frontmatter = re.match(r"\A---\n(.*?)\n---\n", path.read_text(), re.S)
            if not frontmatter:
                errors.append(f"Missing skill frontmatter: {path.parent.name}")
                continue
            fields = dict(re.findall(r"^(name|description):\s*(.+)$", frontmatter[1], re.M))
            if fields.get("name") != path.parent.name or not fields.get("description"):
                errors.append(f"Invalid skill discovery metadata: {path.parent.name}")
    if errors:
        raise ValueError("\n".join(errors))
    print(f"Project contracts passed: {len(files)} documents and skills; version {version.version(root)}.")


if __name__ == "__main__":
    try:
        check()
    except ValueError as error:
        print(error, file=sys.stderr)
        sys.exit(1)
