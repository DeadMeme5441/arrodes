"""Stage verified executables and source materials for the official Arrodes repository."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from release_manifest import validate_manifest


def gh(*arguments):
    return subprocess.check_output(["gh", *arguments], text=True).strip()


def local_digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def release_commit(root):
    commit = os.environ.get("GITHUB_SHA")
    if not commit:
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    if len(commit) != 40 or any(char not in "0123456789abcdefABCDEF" for char in commit):
        raise RuntimeError("Release commit must be a full 40-character hexadecimal ID")
    return commit.lower()


def checksum_from(path):
    fields = path.read_text().split()
    if not fields or len(fields[0]) != 64:
        raise RuntimeError(f"Checksum file is empty or invalid: {path.name}")
    return fields[0].lower()


def main():
    root = Path(__file__).resolve().parent.parent
    repository = os.environ.get("GITHUB_REPOSITORY", "DeadMeme5441/arrodes")
    if repository != "DeadMeme5441/arrodes":
        raise RuntimeError("Release publishing is restricted to the official Arrodes repository")
    metadata = json.loads(gh("api", f"repos/{repository}"))
    if metadata.get("full_name") != repository:
        raise RuntimeError("Repository identity does not match the release destination")
    version = json.loads((root / "package.json").read_text())["version"]
    commit = release_commit(root)
    tag = os.environ.get("GITHUB_REF_NAME", "")
    if tag != f"v{version}":
        raise RuntimeError(f"Release tag must match application version v{version}")
    directory = Path(os.environ.get("ARRODES_RELEASE_DIRECTORY", str(root / "target" / "release")))
    assets = []
    names = [f"arrodes-{target}" for target in ["darwin-arm64", "darwin-x64", "linux-arm64", "linux-x64"]]
    for name in names:
        binary = directory / name
        checksum = binary.with_name(binary.name + ".sha256")
        manifest = binary.with_name(binary.name + ".manifest.json")
        expected = checksum_from(checksum)
        actual = local_digest(binary)
        if actual != expected:
            raise RuntimeError(f"Checksum mismatch: {binary.name}")
        try:
            data = json.loads(manifest.read_text())
            validate_manifest(data, executable=binary, version=version, commit=commit,
                              platform=name.split("-")[1], arch=name.split("-")[2], require_clean=True)
        except (OSError, ValueError, json.JSONDecodeError) as error:
            raise RuntimeError(f"Invalid release manifest for {binary.name}: {error}") from error
        assets.extend([str(binary), str(checksum), str(manifest)])
    source_archive = directory / "arrodes-third-party-sources.tar.gz"
    source_checksum = source_archive.with_name(source_archive.name + ".sha256")
    if local_digest(source_archive) != checksum_from(source_checksum):
        raise RuntimeError(f"Checksum mismatch: {source_archive.name}")
    assets.extend([str(source_archive), str(source_checksum)])
    notices = directory / "THIRD_PARTY_NOTICES.txt"
    if notices.read_bytes() != (root / "THIRD_PARTY_NOTICES.txt").read_bytes():
        raise RuntimeError("Release notices do not match the source revision")
    assets.append(str(notices))
    if "--check" in sys.argv[1:]:
        print(f"Release {tag}: four executables, notices, and corresponding sources verified; nothing published")
        return
    existing = subprocess.run(["gh", "api", f"repos/{repository}/releases/tags/{tag}"],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if existing.returncode == 0:
        published = {asset["name"]: asset for asset in json.loads(existing.stdout)["assets"]}
        expected_names = {Path(asset).name for asset in assets}
        if not expected_names <= published.keys():
            raise RuntimeError(f"Existing release {tag} is missing supported artifacts; refusing to overwrite it")
        for asset in assets:
            path = Path(asset)
            record = published[path.name]
            digest = record.get("digest")
            if not isinstance(digest, str) or not digest.startswith("sha256:"):
                raise RuntimeError(f"Existing release asset {path.name} has no reliable GitHub SHA-256 digest; refusing to accept it")
            remote_digest = digest.removeprefix("sha256:").lower()
            if remote_digest != local_digest(path):
                raise RuntimeError(f"Existing release asset bytes differ: {path.name}; refusing to overwrite it")
        print(f"Release {tag} already exists; leaving its assets unchanged")
        return
    subprocess.run(
        ["gh", "release", "create", tag, "--repo", repository, "--verify-tag", "--draft",
         "--title", f"Arrodes {version}", "--notes",
         f"Arrodes {version} for macOS and Linux (arm64 and x64). "
         "Self-contained executables with SHA-256 checksums; no language runtimes to install. "
         "First-launch sign-in, persistent sessions, and all Arrodes state under ~/.arrodes. "
         "No project dotfolder. Windows remains experimental and is not included. "
         "Includes third-party notices and corresponding sources. "
         "Anthropic access uses API keys/cloud credentials; Claude.ai subscription OAuth and Copilot login are not supported. "
         "macOS binaries are not Developer ID signed or notarized.",
         *assets], check=True)


if __name__ == "__main__":
    main()
