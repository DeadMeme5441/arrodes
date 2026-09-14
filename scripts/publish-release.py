"""Stage a private macOS/Linux release from verified artifacts."""
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
    directory = Path(os.environ.get("ARRODES_RELEASE_DIRECTORY", str(root / "target" / "release")))
    assets = []
    for target in ["darwin-arm64", "darwin-x64", "linux-arm64", "linux-x64"]:
        binary = directory / f"arrodes-{target}"
        checksum = binary.with_name(binary.name + ".sha256")
        expected = checksum.read_text().split()[0]
        with binary.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if actual != expected:
            raise RuntimeError(f"Checksum mismatch: {binary.name}")
        assets.extend([str(binary), str(checksum)])
    if "--check" in sys.argv[1:]:
        print(f"Private release {tag}: all four supported artifacts verified; nothing published")
        return
    existing = subprocess.run(["gh", "release", "view", tag, "--repo", repository, "--json", "assets"],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if existing.returncode == 0:
        published_names = {asset["name"] for asset in json.loads(existing.stdout)["assets"]}
        if not {Path(asset).name for asset in assets} <= published_names:
            raise RuntimeError(f"Existing release {tag} is missing supported artifacts; refusing to overwrite it")
        print(f"Release {tag} already exists; leaving its assets unchanged")
        return
    subprocess.run(
        ["gh", "release", "create", tag, "--repo", repository, "--verify-tag", "--draft",
         "--title", f"Arrodes {version}", "--notes",
         f"Arrodes {version} for macOS and Linux (arm64 and x64). "
         "Self-contained executables with SHA-256 checksums; no language runtimes to install. "
         "First-launch sign-in, persistent sessions, and all Arrodes state under ~/.arrodes. "
         "No project dotfolder. Windows remains experimental and is not included. "
         "This release and its repository are private. macOS binaries are not Developer ID signed or notarized.",
         *assets], check=True)


if __name__ == "__main__":
    main()
