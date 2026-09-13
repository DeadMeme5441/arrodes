"""Check relative paths and explicit new-session export through the actual launcher."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

repository = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="arrodes-launcher-") as temporary:
    launch = Path(temporary) / "launch with spaces"
    project = launch / "project"
    project.mkdir(parents=True)
    launcher = (["pwsh", "-NoProfile", "-File", str(repository / "bin" / "arrodes.ps1")]
                if os.name == "nt" else [str(repository / "bin" / "arrodes")])
    result = subprocess.run(
        launcher + ["--cwd", "project", "--home", "home", "--new", "--no-session", "--export", "-"],
        cwd=launch, capture_output=True, text=True, timeout=60)
    assert result.returncode == 0, result.stderr
    header = json.loads(result.stdout.splitlines()[0])
    directories = re.findall(r':cwd\s+("(?:[^"\\]|\\.)*")', header["data"])
    assert any(Path(json.loads(value)).resolve() == project.resolve()
               for value in directories), "Exported session did not retain the requested launch-relative project"
    assert (launch / "home").is_dir(), "Relative home was not resolved from the launch directory"
    print("Launcher smoke passed: external directory, spaces, relative cwd/home, and explicit new-session export.")
