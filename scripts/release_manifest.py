#!/usr/bin/env python3
"""Create and validate provenance manifests for native Arrodes releases."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re

SCHEMA_VERSION = 1
SHA256 = re.compile(r"^[0-9a-f]{64}$")
COMMIT = re.compile(r"^[0-9a-f]{40}$", re.IGNORECASE)
PLATFORMS = {"darwin", "linux", "windows"}
ARCHITECTURES = {"arm64", "x64"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def make_manifest(*, executable: Path, payload: Path, output: Path, version: str,
                  commit: str, platform: str, arch: str, bun: str, java: str, dirty: bool = False) -> dict:
    if not version:
        raise ValueError("package version is required")
    if not COMMIT.fullmatch(commit):
        raise ValueError("git commit must be a full 40-character hexadecimal ID")
    if platform not in PLATFORMS:
        raise ValueError(f"unsupported release platform: {platform}")
    if arch not in ARCHITECTURES:
        raise ValueError(f"unsupported release architecture: {arch}")
    if not executable.is_file() or not payload.is_file():
        raise ValueError("executable and payload must be regular files")
    return {
        "schema_version": SCHEMA_VERSION,
        "package_version": version,
        "git_commit": commit.lower(),
        "source_dirty": dirty,
        "platform": platform,
        "arch": arch,
        "executable": {"name": executable.name, "sha256": sha256(executable)},
        "payload": {"name": payload.name, "sha256": sha256(payload)},
        "toolchain": {"bun": bun, "java": java},
    }


def validate_manifest(manifest: object, *, executable: Path | None = None,
                      payload: Path | None = None, version: str | None = None,
                      commit: str | None = None, platform: str | None = None,
                      arch: str | None = None, require_clean: bool = False) -> None:
    if not isinstance(manifest, dict) or type(manifest.get("schema_version")) is not int or manifest["schema_version"] != SCHEMA_VERSION:
        raise ValueError("missing or unsupported manifest schema_version")
    required = {"package_version", "git_commit", "source_dirty", "platform", "arch", "executable", "payload", "toolchain"}
    if set(manifest) != required | {"schema_version"}:
        raise ValueError("manifest fields do not match schema")
    if type(manifest["source_dirty"]) is not bool or (require_clean and manifest["source_dirty"]):
        raise ValueError("release requires a clean committed source tree")
    if not isinstance(manifest["package_version"], str) or not manifest["package_version"]:
        raise ValueError("manifest package_version is invalid")
    if not isinstance(manifest["git_commit"], str) or not COMMIT.fullmatch(manifest["git_commit"]):
        raise ValueError("manifest git_commit is invalid")
    if (not isinstance(manifest["platform"], str) or not isinstance(manifest["arch"], str)
            or manifest["platform"] not in PLATFORMS or manifest["arch"] not in ARCHITECTURES):
        raise ValueError("manifest target is invalid")
    for kind in ("executable", "payload"):
        value = manifest[kind]
        if (not isinstance(value, dict) or set(value) != {"name", "sha256"}
                or not isinstance(value["name"], str) or not value["name"]
                or value["name"] != Path(value["name"]).name or "\\" in value["name"]
                or not isinstance(value["sha256"], str) or not SHA256.fullmatch(value["sha256"])):
            raise ValueError(f"manifest {kind} entry is invalid")
    if (not isinstance(manifest["toolchain"], dict) or set(manifest["toolchain"]) != {"bun", "java"}
            or any(not isinstance(value, str) or not value for value in manifest["toolchain"].values())):
        raise ValueError("manifest toolchain is invalid")
    checks = (("package version", version, manifest["package_version"]), ("git commit", commit, manifest["git_commit"]),
              ("platform", platform, manifest["platform"]), ("architecture", arch, manifest["arch"]))
    for label, expected, actual in checks:
        if expected is not None and (expected.lower() != actual.lower() if label == "git commit" else expected != actual):
            raise ValueError(f"manifest {label} mismatch")
    for path, kind in ((executable, "executable"), (payload, "payload")):
        if path is not None:
            entry = manifest[kind]
            if path.name != entry["name"] or sha256(path) != entry["sha256"]:
                raise ValueError(f"manifest {kind} digest mismatch")


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    create = sub.add_parser("create")
    create.add_argument("--executable", type=Path, required=True)
    create.add_argument("--payload", type=Path, required=True)
    create.add_argument("--output", type=Path, required=True)
    create.add_argument("--dirty", action="store_true")
    for name in ("version", "commit", "platform", "arch", "bun", "java"):
        create.add_argument(f"--{name}", required=True)
    validate = sub.add_parser("validate")
    validate.add_argument("manifest", type=Path)
    validate.add_argument("--require-clean", action="store_true")
    validate.add_argument("--executable", type=Path)
    validate.add_argument("--payload", type=Path)
    for name in ("version", "commit", "platform", "arch"):
        validate.add_argument(f"--{name}")
    args = parser.parse_args()
    if args.command == "create":
        result = make_manifest(executable=args.executable, payload=args.payload, output=args.output,
                               version=args.version, commit=args.commit, platform=args.platform,
                               arch=args.arch, bun=args.bun, java=args.java, dirty=args.dirty)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    else:
        validate_manifest(json.loads(args.manifest.read_text()), executable=args.executable, payload=args.payload,
                          version=args.version, commit=args.commit, platform=args.platform, arch=args.arch, require_clean=args.require_clean)


if __name__ == "__main__":
    main()
