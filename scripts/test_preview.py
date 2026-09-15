import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class PreviewIsolation(unittest.TestCase):
    def test_launcher_uses_temporary_project_home_and_clears_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            launcher = root / "start"
            shutil.copy2(Path(__file__).with_name("preview-launcher.sh"), launcher)
            launcher.chmod(0o755)
            executable = root / "arrodes-preview"
            executable.write_text('#!/bin/sh\nprintf "%s\\n" "$PWD" "$HOME" "${OPENAI_API_KEY-unset}" "${AWS_PROFILE-unset}" "$@"\n')
            executable.chmod(0o755)
            result = subprocess.run([str(launcher)], text=True, capture_output=True,
                                    env={**os.environ, "OPENAI_API_KEY": "synthetic-key", "AWS_PROFILE": "synthetic-profile"})
            self.assertEqual(result.returncode, 0, result.stderr)
            lines = result.stdout.splitlines()
            self.assertNotEqual(Path(lines[0]).parent, root)
            self.assertEqual(lines[2:4], ["unset", "unset"])
            self.assertEqual(lines[4], "--home")
            self.assertEqual(Path(lines[0]).name, "project")
            self.assertFalse(Path(lines[0]).exists(), "Temporary state should be cleaned up")


if __name__ == "__main__":
    unittest.main()
