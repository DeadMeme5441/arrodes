#!/usr/bin/env python3
"""Exercise platform selection and fail-closed updates without network access."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
PAYLOAD = b"#!/bin/sh\nprintf 'installed\\n'\n"

with tempfile.TemporaryDirectory(prefix="arrodes-installer-") as temporary:
    root = Path(temporary)
    tools = root / "tools"
    tools.mkdir()
    (tools / "uname").write_text('#!/bin/sh\ncase "$1" in -s) echo "$TEST_OS";; -m) echo "$TEST_ARCH";; esac\n')
    (tools / "curl").write_text(f"#!{sys.executable}\n" + '''import os, pathlib, sys
args = sys.argv[1:]
if '-w' in args:
    print('https://github.com/DeadMeme5441/arrodes/releases/tag/v1.2.3', end='')
else:
    url = args[1]
    pathlib.Path(os.environ['TEST_REQUEST']).write_text(url)
    output = pathlib.Path(args[args.index('-o') + 1])
    if url.endswith('.sha256'):
        digest = '0' * 64 if os.environ.get('TEST_CORRUPT') else os.environ['TEST_DIGEST']
        output.write_text(digest + '  ' + url.rsplit('/', 1)[1][:-7] + '\\n')
    else:
        output.write_bytes(pathlib.Path(os.environ['TEST_PAYLOAD']).read_bytes())
''')
    for executable in tools.iterdir():
        executable.chmod(0o755)
    payload = root / "payload"
    payload.write_bytes(PAYLOAD)
    cases = [("Darwin", "arm64", "darwin-arm64"), ("Darwin", "x86_64", "darwin-x64"),
             ("Linux", "aarch64", "linux-arm64"), ("Linux", "x86_64", "linux-x64")]
    for index, (system, architecture, target) in enumerate([*cases, cases[0]]):
        home = root / f"home with spaces {index}"
        installed = home / ".local/bin/arrodes"
        installed.parent.mkdir(parents=True)
        installed.write_bytes(b"existing installation")
        request = root / "request"
        environment = {**os.environ, "HOME": str(home), "PATH": f"{tools}{os.pathsep}{os.environ['PATH']}",
                       "TEST_OS": system, "TEST_ARCH": architecture, "TEST_REQUEST": str(request),
                       "TEST_PAYLOAD": str(payload), "TEST_DIGEST": hashlib.sha256(PAYLOAD).hexdigest()}
        environment.pop("TEST_CORRUPT", None)
        if index == len(cases):
            environment["TEST_CORRUPT"] = "1"
        result = subprocess.run(["sh", str(ROOT / "install.sh")], env=environment, capture_output=True, text=True)
        assert request.read_text().endswith(f"/v1.2.3/arrodes-{target}.sha256")
        if index == len(cases):
            assert result.returncode != 0
            assert installed.read_bytes() == b"existing installation"
        else:
            assert result.returncode == 0, result.stderr
            assert subprocess.check_output([str(installed)], text=True) == "installed\n"
        assert not list(installed.parent.glob(".arrodes-install.*"))
    print(json.dumps({"installer": "passed", "platforms": 4, "checksum_failure_preserves_install": True,
                      "space_in_home": True, "temporary_files_removed": True}))
