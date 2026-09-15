import json
import sys
import subprocess
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from release_manifest import make_manifest, validate_manifest


class ReleaseManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.exe = root / "arrodes-linux-x64"
        self.payload = root / "payload.tar.gz"
        self.exe.write_bytes(b"executable")
        self.payload.write_bytes(b"payload")
        self.kwargs = dict(executable=self.exe, payload=self.payload, output=root / "manifest.json",
                           version="0.1.1", commit="a" * 40, platform="linux", arch="x64",
                           bun="1.3.14", java="21.0.12")

    def tearDown(self):
        self.temp.cleanup()

    def test_manifest_round_trip(self):
        manifest = make_manifest(**self.kwargs)
        validate_manifest(manifest, executable=self.exe, payload=self.payload, version="0.1.1",
                          commit="a" * 40, platform="linux", arch="x64")

    def test_cli_checks_expected_revision_and_target(self):
        manifest = make_manifest(**self.kwargs)
        path = self.kwargs["output"]
        path.write_text(json.dumps(manifest))
        command = [sys.executable, str(Path(__file__).with_name("release_manifest.py")),
                   "validate", str(path), "--executable", str(self.exe),
                   "--payload", str(self.payload), "--version", "0.1.1", "--platform", "linux", "--arch", "x64"]
        result = subprocess.run(command + ["--commit", "a" * 40], capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        result = subprocess.run(command + ["--commit", "b" * 40], capture_output=True)
        self.assertNotEqual(result.returncode, 0)

    def test_dirty_preview_cannot_qualify_for_publication(self):
        manifest = make_manifest(**self.kwargs, dirty=True)
        validate_manifest(manifest)
        with self.assertRaises(ValueError):
            validate_manifest(manifest, require_clean=True)

    def test_mismatches_are_rejected(self):
        manifest = make_manifest(**self.kwargs)
        for field, value in (("platform", "darwin"), ("arch", "arm64"), ("version", "9.9.9"), ("commit", "b" * 40)):
            with self.subTest(field=field):
                with self.assertRaises(ValueError):
                    validate_manifest(manifest, executable=self.exe, payload=self.payload,
                                      version=value if field == "version" else "0.1.1",
                                      commit=value if field == "commit" else "a" * 40,
                                      platform=value if field == "platform" else "linux",
                                      arch=value if field == "arch" else "x64")

    def test_missing_invalid_manifest_and_checksum_are_rejected(self):
        manifest = make_manifest(**self.kwargs)
        for broken in ({}, {**manifest, "schema_version": 99}, {**manifest, "git_commit": "bad"}):
            with self.subTest(broken=broken):
                with self.assertRaises(ValueError):
                    validate_manifest(broken)
        corrupted = dict(manifest)
        corrupted["executable"] = {**manifest["executable"], "sha256": "0" * 64}
        with self.assertRaises(ValueError):
            validate_manifest(corrupted, executable=self.exe, payload=self.payload)

    def test_invalid_types_and_path_names_are_rejected(self):
        manifest = make_manifest(**self.kwargs)
        for field, value in (("sha256", None), ("sha256", 7), ("platform", ["linux"]),
                             ("name", "../other")):
            broken = json.loads(json.dumps(manifest))
            if field in ("sha256", "name"):
                broken["executable"][field] = value
            else:
                broken[field] = value
            with self.subTest(field=field, value=value):
                with self.assertRaises(ValueError):
                    validate_manifest(broken)


if __name__ == "__main__":
    unittest.main()
