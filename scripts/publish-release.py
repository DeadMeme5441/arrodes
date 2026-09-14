"""Stage a private draft release only after all native artifacts passed CI."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def gh(*arguments):
    return subprocess.check_output(["gh", *arguments], text=True).strip()


def main():
    root = Path(__file__).resolve().parent.parent
    repository = os.environ.get("GITHUB_REPOSITORY", "DeadMeme5441/arrodes")
    if repository != "DeadMeme5441/arrodes":
        raise RuntimeError("Release publishing is restricted to the private Arrodes repository")
    metadata = json.loads(gh("api", f"repos/{repository}"))
    if metadata.get("private") is not True:
        raise RuntimeError("Refusing to publish: the repository is not private")
    version = json.loads((root / "package.json").read_text())["version"]
    tag = os.environ.get("GITHUB_REF_NAME", "")
    if tag != f"v{version}":
        raise RuntimeError(f"Release tag must match application version v{version}")
    directory = root / "target" / "release"
    assets = []
    for target in ["darwin-arm64", "darwin-x64", "linux-arm64", "linux-x64", "windows-x64.exe"]:
        binary = directory / f"arrodes-{target}"
        checksum = binary.with_name(binary.name + ".sha256")
        expected = checksum.read_text().split()[0]
        with binary.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if actual != expected:
            raise RuntimeError(f"Checksum mismatch: {binary.name}")
        assets.extend([str(binary), str(checksum)])
    if "--check" in sys.argv[1:]:
        print(f"Private release {tag}: all five native artifacts verified; nothing published")
        return
    subprocess.run(
        ["gh", "release", "create", tag, "--repo", repository, "--verify-tag", "--draft",
         "--title", f"Arrodes {version}", "--notes",
         "Private preview. Self-contained executables and SHA-256 checksums. "
         "No Java, Clojure CLI, or Bun installation required. "
         "Application state stays under ~/.arrodes. This release does not change repository visibility.",
         *assets], check=True)


if __name__ == "__main__":
    main()
