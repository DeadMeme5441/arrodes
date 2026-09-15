import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import ci_changes
import version


class ProjectContracts(unittest.TestCase):
    def test_docs_only_classification_keeps_workflows_and_runtime_changes_native(self):
        self.assertFalse(ci_changes.native_required(["docs/ARCHITECTURE.md", "AGENTS.md"]))
        for name in [".github/workflows/ci.yml", "scripts/dev.py", "deps.edn", "src/cljc/arrodes/run.cljc", "THIRD_PARTY_NOTICES.txt"]:
            self.assertTrue(ci_changes.native_required(["README.md", name]))

    def test_semver_accepts_prereleases_and_rejects_malformed_versions(self):
        for value in ["0.1.1", "1.2.3-rc.1", "1.2.3+build.4"]:
            self.assertIsNotNone(version.VERSION.fullmatch(value), value)
        for value in ["01.2.3", "1.2.3-..", "1.2.3-01", "1.2", "1.2.3-"]:
            self.assertIsNone(version.VERSION.fullmatch(value), value)

    def test_version_update_rejects_drift_before_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "package.json").write_text(json.dumps({"version": "1.2.3"}))
            for name in version.MIRRORS:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("missing declaration")
            with self.assertRaises(ValueError):
                version.set_version("1.2.4", root)
            self.assertEqual(version.version(root), "1.2.3")
            with self.assertRaises(ValueError):
                version.set_version("not-a-version", root)


if __name__ == "__main__":
    unittest.main()
