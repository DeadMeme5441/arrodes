"""Exercise the real CLI's line-terminal command semantics without provider calls."""
import subprocess
import tempfile
from pathlib import Path

with tempfile.TemporaryDirectory(prefix="arrodes-terminal-") as temporary:
    root = Path(temporary)
    project = root / "project"
    project.mkdir()
    commands = '''/eval (def retained 41) (inc retained)
/eval (resolve 'retained)
/settings set terminal.editor "editor with spaces" global
/reload
/eval (resolve 'retained)
/quit
'''
    result = subprocess.run(
        ["clojure", "-Srepro", "-M:run", "--cli", "--cwd", str(project),
         "--home", str(root / "home"), "--no-session"],
        input=commands, text=True, capture_output=True, timeout=45)
    transcript = result.stdout + result.stderr
    assert result.returncode == 0, transcript
    assert "=> 42" in transcript, transcript
    assert "=> #'arrodes.session." in transcript, transcript
    assert "=> nil" in transcript, transcript
    assert "Unclosed quote" not in transcript, transcript
    assert "error [" not in transcript, transcript
    settings = (root / "home" / "settings.edn").read_text()
    assert '"editor with spaces"' in settings, settings
    print("Terminal command smoke passed: Clojure quoting, EDN strings, reload, and explicit quit.")
