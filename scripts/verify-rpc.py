import asyncio
import json
from pathlib import Path
import tempfile


async def main():
    with tempfile.TemporaryDirectory(prefix="arrodes-rpc-") as temporary:
        root = Path(temporary)
        project = root / "project"
        extensions = project / ".arrodes-mono" / "extensions"
        extensions.mkdir(parents=True)
        (extensions / "probe.clj").write_text('''(println "LOAD_DIAGNOSTIC")
(fn [api]
  (println "INIT_DIAGNOSTIC")
  ((:register-command! api) {:name "noisy" :fn (fn [_] (println "COMMAND_DIAGNOSTIC") :ok)})
  ((:register-command! api)
   {:name "slow" :fn (fn [_]
                       (println "SLOW_STARTED")
                       (flush)
                       (let [deadline (+ (System/currentTimeMillis) 700)]
                         (loop []
                           (when (< (System/currentTimeMillis) deadline)
                             (try (Thread/sleep 10) (catch InterruptedException _ nil))
                             (recur))))
                       :done)})
  nil)
''')
        process = await asyncio.create_subprocess_exec(
            "clojure", "-Srepro", "-M:run", "--headless",
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE)
        diagnostics = []
        events = []
        slow_started = asyncio.Event()
        count = 0

        async def collect_diagnostics():
            while line := await process.stderr.readline():
                text = line.decode().rstrip()
                diagnostics.append(text)
                if "SLOW_STARTED" in text:
                    slow_started.set()

        diagnostic_task = asyncio.create_task(collect_diagnostics())

        async def send(packet):
            process.stdin.write((json.dumps(packet) + "\n").encode())
            await process.stdin.drain()

        async def envelope():
            line = await asyncio.wait_for(process.stdout.readline(), 30)
            if not line:
                raise AssertionError("RPC exited before its response: " + "\n".join(diagnostics[-10:]))
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                raise AssertionError("Non-JSON content reached protocol stdout: " + repr(line))

        async def response(identifier, host=None):
            while True:
                packet = await envelope()
                if packet.get("type") == "event":
                    events.append(packet["event"])
                elif packet.get("type") == "host-request":
                    assert host is not None, packet
                    await send({"type": "host-response", "id": packet["id"],
                                "result": host(packet["request"])})
                elif packet.get("type") == "response" and packet.get("id") == identifier:
                    return packet
                else:
                    raise AssertionError(packet)

        async def request(method, params=None, host=None, error=None):
            nonlocal count
            count += 1
            identifier = f"request-{count}"
            await send({"type": "request", "id": identifier, "method": method, "params": params or {}})
            packet = await response(identifier, host)
            if error is not None:
                assert packet.get("error", {}).get("code") == error, packet
                return packet["error"]
            assert "error" not in packet, packet
            return packet["result"]

        try:
            hello = await envelope()
            assert hello["type"] == "hello" and hello["protocol"] == 1, hello
            print("RPC-SMOKE ready", flush=True)
            await request("runtime.inspect", error="not-initialized")
            await request("initialize", {"cwd": str(project), "home": str(root / "home"),
                                          "memory?": True, "trust": True})
            session = await request("session.create", {"name": "Protocol verification"})
            sid = session["id"]
            evaluated = await request("session.evaluate", {"session-id": sid,
                                        "source": '(println "EVAL_CAPTURE") (def n 40) (+ n 2)'})
            assert evaluated["error?"] is False, evaluated
            assert evaluated["result"]["value"] == 42, evaluated
            assert "EVAL_CAPTURE" in evaluated["content"], evaluated
            await request("session.command", {"session-id": sid, "name": "noisy"})

            code = '''(defn add_values [{:keys [values]}] (reduce + values))
(register-tool! #'add_values {:name "add_values" :parameters {:type "object" :properties {:values {:type "array" :items {:type "integer"}}} :required ["values"] :additionalProperties false}})
(add_values {:values [20 22]})'''
            registered = await request("session.evaluate", {"session-id": sid, "source": code})
            assert registered["error?"] is False and registered["result"]["value"] == 42, registered
            invoked = await request("session.invoke", {"session-id": sid, "name": "add_values", "arguments": {"values": [40, 2]}})
            assert invoked["error?"] is False and invoked["result"]["value"] == 42, invoked
            invalid = await request("session.invoke", {"session-id": sid, "name": "add_values", "arguments": {"values": ["invalid"]}})
            assert invalid["error?"] is True, invalid

            await request("capability.attach", {"session-id": sid, "name": "host_echo",
                          "parameters": {"type": "object", "properties": {"value": {"type": "string"}}, "required": ["value"]}})
            def host(packet):
                assert packet["kind"] == "capability" and packet["name"] == "host_echo", packet
                return "host:" + packet["arguments"]["value"]
            echoed = await request("session.invoke", {"session-id": sid, "name": "host_echo", "arguments": {"value": "payload"}}, host=host)
            assert echoed["error?"] is False and echoed["result"]["value"] == "host:payload", echoed
            await request("capability.detach", {"session-id": sid, "name": "read"}, error="capability-not-owned")
            await request("capability.detach", {"session-id": sid, "name": "host_echo"})

            await send({"type": "request", "id": "reuse", "method": "session.command",
                        "params": {"session-id": sid, "name": "slow"}})
            await asyncio.wait_for(slow_started.wait(), 10)
            await send({"type": "cancel", "id": "reuse"})
            cancelled = await response("reuse")
            assert cancelled.get("error", {}).get("code") == "cancelled", cancelled
            await send({"type": "request", "id": "reuse", "method": "runtime.inspect", "params": {}})
            duplicate = await response("reuse")
            assert duplicate.get("error", {}).get("code") == "duplicate-id", duplicate
            await asyncio.sleep(0.8)
            await send({"type": "request", "id": "reuse", "method": "runtime.inspect", "params": {}})
            reused = await response("reuse")
            assert "result" in reused, reused

            replay = await request("event.replay", {"after": 0})
            sequences = [event["seq"] for event in replay["events"]]
            assert sequences == sorted(set(sequences)), replay
            await request("session.reload", {"session-id": sid})
            reset = await request("session.evaluate", {"session-id": sid, "source": "(resolve 'n)"})
            assert reset["error?"] is False and reset["result"].get("value") is None, reset
            await request("shutdown")
            await asyncio.wait_for(process.wait(), 30)
            await diagnostic_task
            assert process.returncode == 0, diagnostics
            diagnostic_text = "\n".join(diagnostics)
            for marker in ["LOAD_DIAGNOSTIC", "INIT_DIAGNOSTIC", "COMMAND_DIAGNOSTIC", "SLOW_STARTED"]:
                assert marker in diagnostic_text, (marker, diagnostics)
            assert "EVAL_CAPTURE" not in diagnostic_text, diagnostics
            print(json.dumps({"rpc": "passed", "commands": count, "durable_events": len(sequences),
                              "host_roundtrip": True, "cancel_id_ownership": True,
                              "stdout_jsonl_only": True, "diagnostics_on_stderr": True,
                              "registered_repl_tool": True, "namespace_reset": True}), flush=True)
        finally:
            if process.returncode is None:
                process.terminate()
                try:
                    await asyncio.wait_for(process.wait(), 5)
                except asyncio.TimeoutError:
                    process.kill()
                    await process.wait()
            if not diagnostic_task.done():
                diagnostic_task.cancel()


asyncio.run(main())
