"""Exercise the shipped executable outside its checkout, with no developer tools on PATH."""
import argparse
import asyncio
import contextlib
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import threading

CANCEL = object()


class FixtureProvider(BaseHTTPRequestHandler):
    requests = []
    source = ""

    def log_message(self, *_):
        pass

    def do_GET(self):
        assert self.path == "/v1/models", self.path
        payload = json.dumps({"data": [{"id": "fixture-model", "context_length": 32768}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.requests.append(request)
        assert self.headers.get("Authorization") == "Bearer local-release-check"
        assert request["model"] == "fixture-model", request["model"]
        if any(message["role"] == "tool" and message.get("tool_call_id") == "repair" for message in request["messages"]):
            delta = {"content": "Verified the repository repair: 42."}
            finish = "stop"
        else:
            delta = {"tool_calls": [{"index": 0, "id": "repair", "type": "function", "function": {
                "name": "repl", "arguments": json.dumps({"source": self.source})}}]}
            finish = "tool_calls"
        events = [
            {"id": "local", "model": "fixture-model", "choices": [{"index": 0, "delta": delta, "finish_reason": None}]},
            {"id": "local", "model": "fixture-model", "choices": [{"index": 0, "delta": {}, "finish_reason": finish}]},
        ]
        payload = "".join("data: " + json.dumps(event) + "\n\n" for event in events) + "data: [DONE]\n\n"
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        self.wfile.write(payload.encode())


def isolated_environment(root):
    # Preserve only OS plumbing. No real credentials, SDK homes, or checkout overrides.
    environment = {key: value for key, value in os.environ.items()
                   if key.upper() in {"SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "LANG", "LC_ALL"}}
    user = root / "user"
    user.mkdir()
    temporary = root / "tmp"
    temporary.mkdir()
    tools = root / "system-tools"
    tools.mkdir()
    if os.name == "nt":
        # A normal Windows user profile already has its roaming-data directory.
        (user / "AppData" / "Roaming").mkdir(parents=True)
        system = Path(environment["SYSTEMROOT"]) / "System32"
        search = os.pathsep.join([str(system), str(system / "WindowsPowerShell" / "v1.0")])
    else:
        for name in ["sh", "bash", "cat"]:
            source = Path("/bin") / name
            if source.exists():
                (tools / name).symlink_to(source)
        search = str(tools)
    environment.update(HOME=str(user), USERPROFILE=str(user), PATH=search, TERM="xterm-256color",
                       TMPDIR=str(temporary), TMP=str(temporary), TEMP=str(temporary),
                       XDG_CONFIG_HOME=str(user / "config"), XDG_CACHE_HOME=str(user / "cache"))
    for command in ["java", "clojure", "bun"]:
        assert shutil.which(command, path=search) is None, command
    return environment


class Rpc:
    def __init__(self, executable, home, cwd, environment):
        self.arguments = [str(executable), "--rpc", "--cwd", str(cwd)]
        if home is not None:
            self.arguments += ["--home", str(home)]
        self.home, self.cwd, self.environment = home, cwd, environment
        self.events, self.diagnostics = [], []
        self.count = 0

    async def __aenter__(self):
        self.process = await asyncio.create_subprocess_exec(
            *self.arguments, cwd=self.cwd, env=self.environment,
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        self.stderr_task = asyncio.create_task(self.collect_stderr())
        try:
            hello = await self.packet()
            assert hello.get("type") == "hello", hello
            parameters = {"cwd": str(self.cwd)}
            if self.home is not None:
                parameters["home"] = str(self.home)
            await self.call("initialize", parameters)
            return self
        except BaseException:
            await self.__aexit__(None, None, None)
            raise

    async def collect_stderr(self):
        while line := await self.process.stderr.readline():
            self.diagnostics.append(line.decode(errors="replace"))

    async def packet(self):
        line = await asyncio.wait_for(self.process.stdout.readline(), 120)
        assert line, "RPC ended: " + "".join(self.diagnostics)
        return json.loads(line)

    async def send(self, packet):
        self.process.stdin.write((json.dumps(packet) + "\n").encode())
        await self.process.stdin.drain()

    async def call(self, method, parameters=None, host=None, expected_error=None):
        self.count += 1
        identifier = f"check-{self.count}"
        await self.send({"type": "request", "id": identifier, "method": method, "params": parameters or {}})
        while True:
            packet = await self.packet()
            kind = packet["type"]
            if kind == "event":
                self.events.append(packet["event"])
            elif kind == "host-request":
                assert host is not None, packet
                reply = host(packet["request"])
                await self.send({"type": "host-cancel", "id": packet["id"]} if reply is CANCEL else
                                {"type": "host-response", "id": packet["id"], "result": reply})
            elif kind == "host-cancel":
                continue
            elif kind == "response":
                assert packet["id"] == identifier, packet
                if expected_error is not None:
                    assert packet.get("error", {}).get("code") == expected_error, packet
                    return packet["error"]
                assert "error" not in packet, packet
                return packet.get("result")
            else:
                raise AssertionError(packet)

    async def __aexit__(self, *_):
        if self.process.returncode is None:
            try:
                await self.call("shutdown")
                await asyncio.wait_for(self.process.wait(), 30)
            finally:
                if self.process.returncode is None:
                    self.process.kill()
                    await self.process.wait()
        await self.stderr_task
        assert self.process.returncode == 0, "".join(self.diagnostics)


def setup_reply(request):
    kind = request["kind"]
    if kind == "notify":
        return None
    if kind == "confirm":
        return False  # Ordinary coding must work without executable project resources.
    if kind == "input":
        assert request.get("secret?"), request
        return "local-release-check"
    if kind == "select":
        items = request.get("items", request.get("options", []))
        values = [item.get("value", item.get("id")) if isinstance(item, dict) else item for item in items]
        for candidate in ["release-fixture", "api-key", "fixture-model", "none"]:
            if candidate in values:
                return candidate
        raise AssertionError(("Unexpected setup choices", values))
    raise AssertionError(request)


async def exercise(executable, home, project, environment):
    async with Rpc(executable, home, project, environment) as rpc:
        status = await rpc.call("setup.status")
        assert status["ready?"] is False, status
        await rpc.call("setup.run", host=lambda _: CANCEL, expected_error="cancelled")
        status = await rpc.call("setup.status")
        assert status["ready?"] is False, status
        await rpc.call("setup.run", {"provider": "release-fixture", "force?": True}, host=setup_reply)
        settings = (await rpc.call("settings.get"))["settings"]
        assert settings["provider"] == "release-fixture", settings
        assert settings["model"] == "fixture-model", settings
        info = await rpc.call("project.info")
        session = await rpc.call("session.create", {"name": "Installed release check"})
        sid = session["id"]
        native_platform = {"Darwin": "darwin", "Linux": "linux", "Windows": "win32"}[platform.system()]
        native_arch = "arm64" if platform.machine().lower() in {"arm64", "aarch64"} else "x64"
        library = {"Darwin": "libopentui.dylib", "Linux": "libopentui.so", "Windows": "opentui.dll"}[platform.system()]
        native_path = f"opentui/@opentui/core-{native_platform}-{native_arch}/{library}"
        native = await rpc.call("session.evaluate", {"session-id": sid, "source":
            '(with-open [library (com.sun.jna.NativeLibrary/getInstance '
            '(str (.getParent (java.io.File. (System/getProperty "java.home"))) '
            f'"/{native_path}"))] true)'})
        assert native["error?"] is False and native["result"]["value"] is True, native
        operation = await rpc.call("session.run", {"session-id": sid, "prompt": "Read answer.txt, repair 41 to 42, and run the result."})
        result = await rpc.call("operation.wait", {"operation-id": operation["id"], "timeout-ms": 90000})
        assert result["status"] == "completed", result
        failures = [event["data"] for event in rpc.events
                    if event["type"] in {"capability/completed", "evaluation/completed"}
                    and event["data"].get("error?")]
        assert not failures, failures
        assert (project / "answer.txt").read_text() == "42\n"
        names = {event["data"].get("name") for event in rpc.events if event["type"] == "capability/completed"}
        assert {"read", "edit", "powershell" if os.name == "nt" else "bash"} <= names, names
        value = await rpc.call("session.evaluate", {"session-id": sid, "source": "verified"})
        assert value["error?"] is False and value["result"].get("value", {}).get("answer") == 42, value
        result_id = value["result"]["id"]
        # Another repo must not contend on a global database lock.
        second = project.parent / "second repo"
        second.mkdir()
        async with Rpc(executable, home, second, environment) as other:
            other_info = await other.call("project.info")
            assert other_info != info, (info, other_info)
        assert not (project / ".arrodes").exists()
        assert not (second / ".arrodes").exists()
    subdirectory = project / "src"
    subdirectory.mkdir()
    async with Rpc(executable, home, subdirectory, environment) as rpc:
        resumed_info = await rpc.call("project.info")
        assert resumed_info["id"] == info["id"], (info, resumed_info)
        sessions = (await rpc.call("session.list"))["sessions"]
        assert any(item["id"] == sid for item in sessions), sessions
        retained = await rpc.call("result.inspect", {"session-id": sid, "result-id": result_id})
        assert retained["value"]["answer"] == 42, retained
        reset = await rpc.call("session.evaluate", {"session-id": sid, "source": "(resolve 'verified)"})
        assert reset["result"].get("value") is None, reset
        settings = (await rpc.call("settings.get"))["settings"]
        assert settings["model"] == "fixture-model", settings
    assert len(FixtureProvider.requests) == 2, FixtureProvider.requests
    # A dummy key may be in auth storage, never in ordinary settings/session artifacts.
    for path in home.rglob("*"):
        if path.is_file() and "auth" not in path.relative_to(home).parts and "runtime" not in path.relative_to(home).parts:
            assert b"local-release-check" not in path.read_bytes(), path
    assert not (project / ".arrodes").exists()


async def startup_checks(executable, root, environment):
    first, second = root / "parallel one", root / "parallel two"
    first.mkdir()
    second.mkdir()
    default_environment = {key: value for key, value in environment.items() if key != "ARRODES_HOME"}
    # Both launches must be safe when neither the default home nor payload exists.
    async with contextlib.AsyncExitStack() as stack:
        clients = await asyncio.gather(
            stack.enter_async_context(Rpc(executable, None, first, default_environment)),
            stack.enter_async_context(Rpc(executable, None, second, default_environment)))
        patches = await asyncio.gather(
            clients[0].call("settings.update", {"scope": "global", "changes": {"release/check-a": True}}),
            clients[1].call("settings.update", {"scope": "global", "changes": {"release/check-b": True}}))
        assert any(result["settings"].get("release/check-a") and result["settings"].get("release/check-b")
                   for result in patches), patches
        await asyncio.gather(*(client.call("project.trust", {"trusted?": True}) for client in clients))
        for client in clients:
            info = await client.call("project.info")
            assert Path(info["directory"]).is_relative_to(Path(environment["HOME"]) / ".arrodes")
            assert info["trust"]["trusted?"] is True, info
    override = root / "environment home"
    async with Rpc(executable, None, first, dict(environment, ARRODES_HOME=str(override))) as client:
        info = await client.call("project.info")
        assert Path(info["directory"]).is_relative_to(override)
    async with Rpc(executable, None, first, dict(environment, ARRODES_HOME="")) as client:
        info = await client.call("project.info")
        assert Path(info["directory"]).is_relative_to(Path(environment["HOME"]) / ".arrodes")


def terminal_smoke(executable, home, project, environment):
    if os.name == "nt":
        result = subprocess.run([str(executable), "--home", str(home)], cwd=project, env=environment,
                                input="", text=True, capture_output=True, timeout=60)
        assert result.returncode != 0 and "interactive terminal" in result.stderr.lower(), result.stderr
        return "packaged native library linked via RPC; UI non-TTY rejection (no Windows PTY)"
    import fcntl
    import pty
    import select
    import struct
    import termios
    import time
    import pyte
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 36, 120, 0, 0))
    process = subprocess.Popen([str(executable), "--home", str(home), "--provider", "release-fixture", "--no-mouse"],
                               cwd=project, env=environment, stdin=slave, stdout=slave, stderr=slave,
                               start_new_session=True)
    os.close(slave)
    capture = bytearray()
    screen = pyte.Screen(120, 36)
    stream = pyte.ByteStream(screen)

    def wait_for(text, timeout=60, absent=False):
        expected = text.decode().lower()
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if (expected in "\n".join(screen.display).lower()) != absent:
                return
            if process.poll() is not None:
                break
            if select.select([master], [], [], 0.1)[0]:
                try:
                    chunk = os.read(master, 65536)
                except OSError:
                    break
                capture.extend(chunk)
                stream.feed(chunk)
                if b"\x1b[6n" in chunk:
                    os.write(master, b"\x1b[1;1R")
        raise AssertionError("Terminal did not reach " + repr(text) + ":\n" + "\n".join(screen.display))

    try:
        wait_for(b"Welcome to Arrodes.")
        os.write(master, b"Release fixture")
        wait_for(b"Release fixture")
        os.write(master, b"\r")
        wait_for(b"Connect provider")
        os.write(master, b"\r")
        wait_for(b"Enter API key")
        os.write(master, b"\x1b")
        wait_for(b"Sign-in cancelled")
        os.write(master, b"\r")
        wait_for(b"Connect provider")
        os.write(master, b"\r")
        wait_for(b"Enter API key")
        os.write(master, b"local-release-check")
        wait_for("••".encode())
        assert b"local-release-check" not in capture, "API key appeared in terminal output"
        os.write(master, b"\r")
        wait_for(b"Provider connected")
        os.write(master, b"\r")
        wait_for(b"Browse models")
        os.write(master, b"\r")
        wait_for(b"fixture-model")
        wait_for(b"Discovering models", absent=True)
        os.write(master, b"\r")
        wait_for(b"Reasoning")
        os.write(master, b"\r")
        wait_for(b"Make default for new conversations")
        os.write(master, b"\r")
        wait_for(b"Make default for new conversations", absent=True)
        wait_for(b"fixture-model")
        wait_for(b"Idle")
        os.write(master, b"/eval\r")
        wait_for(b"Clojure")
        os.write(master, b"(+ 20 22)\r")
        wait_for(b"42")
        wait_for(b"Idle")
        os.write(master, b"\x04")
        deadline = time.monotonic() + 20
        while process.poll() is None and time.monotonic() < deadline:
            if select.select([master], [], [], 0.1)[0]:
                try:
                    chunk = os.read(master, 65536)
                except OSError:
                    break
                capture.extend(chunk)
                stream.feed(chunk)
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            raise AssertionError("Terminal did not exit:\n" + "\n".join(screen.display))
        assert process.returncode == 0, capture.decode(errors="replace")[-4000:]
        return "PTY providers, cancelled/secret login, model/default selection, evaluation 42, clean exit"
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        os.close(master)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("executable", nargs="?")
    parser.add_argument("--directory", type=Path)
    args = parser.parse_args()
    if args.directory:
        candidates = [p for p in args.directory.glob("arrodes-*") if p.suffix in ("", ".exe")]
        assert len(candidates) == 1, candidates
        source = candidates[0].resolve()
    else:
        source = Path(args.executable).resolve()
    checksum = source.with_name(source.name + ".sha256")
    with source.open("rb") as stream:
        assert hashlib.file_digest(stream, "sha256").hexdigest() == checksum.read_text().split()[0]
    with tempfile.TemporaryDirectory(prefix="arrodes installed ") as temporary:
        root = Path(temporary).resolve()
        environment = isolated_environment(root)
        baseline_profile = {str(path.relative_to(environment["HOME"]))
                            for path in Path(environment["HOME"]).rglob("*")}
        environment["ARRODES_HOME"] = str(root / "unused environment home")
        home = root / "private home"
        project = root / "repo with spaces"
        project.mkdir()
        (project / ".git").mkdir()  # Root identity is discoverable without Git installed.
        (project / "answer.txt").write_text("41\n")
        installed = root / ("arrodes.exe" if os.name == "nt" else "arrodes")
        shutil.copy2(source, installed)
        for flag in ["--help", "--version"]:
            result = subprocess.run([str(installed), flag, "--home", str(home)], cwd=project,
                                    env=environment, text=True, capture_output=True, timeout=30)
            assert result.returncode == 0 and "Arrodes" in result.stdout, result
            if flag == "--version":
                expected_version = json.loads((Path(__file__).resolve().parent.parent / "package.json").read_text())["version"]
                assert result.stdout.strip() == f"Arrodes {expected_version}", result.stdout
            assert not home.exists(), "Help/version created application state"
        server = ThreadingHTTPServer(("127.0.0.1", 0), FixtureProvider)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            (home / "config").mkdir(parents=True)
            fixture_settings = (
                '{:providers {:release-fixture {:type :openai-compatible :name "Release fixture" '
                f':base-url "http://127.0.0.1:{server.server_port}/v1" '
                ':auth-strategy :bearer :models [{:id "fixture-model"}]}} '
                ':session-config {:model "retired-model" :thinking :high}}')
            (home / "config" / "settings.edn").write_text(fixture_settings)
            shell = '(powershell {:command "Get-Content -LiteralPath answer.txt" :timeout 60})' if os.name == "nt" else '(bash {:command "cat answer.txt" :timeout 10})'
            FixtureProvider.source = (
                '(def original (read {:path "answer.txt"})) '
                '(edit {:path "answer.txt" :edits [{:oldText "41" :newText "42"}]}) '
                f'(def execution {shell}) '
                '(assert (zero? (:exit-code execution)) (pr-str execution)) '
                '(def verified {:answer (parse-long (clojure.string/trim (:stdout execution)))}) '
                '(assert (= 42 (:answer verified)) (pr-str execution)) verified')
            asyncio.run(exercise(installed, home, project, environment))
            terminal_home = root / "terminal home"
            (terminal_home / "config").mkdir(parents=True)
            (terminal_home / "config" / "settings.edn").write_text(fixture_settings)
            terminal = terminal_smoke(installed, terminal_home, project, environment)
            assert not Path(environment["ARRODES_HOME"]).exists(), "--home did not override ARRODES_HOME"
            profile = {str(path.relative_to(environment["HOME"])) for path in Path(environment["HOME"]).rglob("*")}
            assert profile == baseline_profile, ("Application changed the OS user profile", sorted(profile ^ baseline_profile))
            asyncio.run(startup_checks(installed, root, environment))
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=5)
        assert not (project / ".arrodes").exists()
        print(json.dumps({"release": "passed", "platform": platform.system(),
                          "developer_tools_on_path": False, "single_file_install": True,
                          "setup": "local provider authentication and model discovery",
                          "repo_work": "read/edit/shell via model continuation",
                          "resume": "durable result retained; live binding reset",
                          "separate_repos": "concurrent", "project_dotfolder": False, "terminal": terminal}))


if __name__ == "__main__":
    main()
