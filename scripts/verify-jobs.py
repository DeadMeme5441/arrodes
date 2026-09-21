"""Exercise jobs over real RPC, including abrupt JVM exit and restart. No provider calls. Optional argv selects a packaged command, e.g. /path/arrodes --rpc."""
import asyncio
import json
from pathlib import Path
import tempfile
import sys


class Core:
    def __init__(self, root):
        self.root = root
        self.events = []
        self.sequence = 0
        self.diagnostics = []

    async def start(self):
        self.process = await asyncio.create_subprocess_exec(
            *(sys.argv[1:] or ["clojure", "-Srepro", "-M:host"]), stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        self.stderr_task = asyncio.create_task(self.stderr())
        assert (await self.packet())["type"] == "hello"
        await self.call("initialize", {"cwd": str(self.root), "home": str(self.root / "home"),
                                       "data-dir": str(self.root / "data"), "trust": False})
        return self

    async def stderr(self):
        while line := await self.process.stderr.readline():
            self.diagnostics.append(line.decode(errors="replace"))

    async def packet(self):
        line = await asyncio.wait_for(self.process.stdout.readline(), 60)
        assert line, "RPC ended: " + "".join(self.diagnostics[-10:])
        return json.loads(line)

    async def call(self, method, params=None):
        self.sequence += 1
        identifier = str(self.sequence)
        self.process.stdin.write((json.dumps({"type": "request", "id": identifier,
                                             "method": method, "params": params or {}}) + "\n").encode())
        await self.process.stdin.drain()
        while True:
            packet = await self.packet()
            if packet["type"] == "event":
                self.events.append(packet["event"])
                continue
            assert packet["type"] == "response" and packet["id"] == identifier, packet
            assert "error" not in packet, packet
            return packet["result"]

    async def close(self, crash=False):
        if self.process.returncode is None:
            if crash:
                self.process.kill()
            else:
                await self.call("shutdown")
            await asyncio.wait_for(self.process.wait(), 30)
        await self.stderr_task


async def main():
    with tempfile.TemporaryDirectory(prefix="arrodes-job-crash-") as directory:
        root = Path(directory)
        core = await Core(root).start()
        try:
            sid = (await core.call("session.create", {"name": "Job recovery fixture"}))["id"]
            async def evaluate(source):
                result = await core.call("session.evaluate", {"session-id": sid, "source": source})
                assert not result["error?"], result
                return result["result"]["value"]
            completed = await evaluate('(jobs/start! {:name "Saved"} #(do (println "saved output") {:answer 42}))')
            completed_params = {"session-id": sid, "job-id": completed["id"]}
            assert (await core.call("job.wait", dict(completed_params, **{"timeout-ms": 10000})))["status"] == "completed"
            cancelled = await evaluate('(jobs/start! {:name "Cancelled"} #(Thread/sleep 60000))')
            cancelled_params = {"session-id": sid, "job-id": cancelled["id"]}
            await core.call("job.cancel", cancelled_params)
            assert (await core.call("job.wait", dict(cancelled_params, **{"timeout-ms": 10000})))["status"] == "cancelled"
            interrupted = await evaluate('(def entered (promise)) (def crash-job (jobs/start! {:name "Crash"} #(do (spit (str cwd "/effect.txt") "once\\n" :append true) (deliver entered true) (Thread/sleep 60000)))) @entered crash-job')
            await core.close(crash=True)
            core = await Core(root).start()
            recovered = await core.call("job.inspect", {"session-id": sid, "job-id": interrupted["id"]})
            assert recovered["status"] == "interrupted", recovered
            assert (root / "effect.txt").read_text() == "once\n", "External effect was repeated"
            saved_output = await core.call("job.output", completed_params)
            assert saved_output["text"] == "saved output\n"
            assert (await core.call("job.output", dict(completed_params, after=saved_output["cursor"])))["text"] == ""
            tail = await core.call("job.output", dict(completed_params, **{"tail?": True, "limit": 7}))
            assert tail["text"] == "output\n" and tail["eof?"], tail
            cancelled_record = await core.call("job.inspect", cancelled_params)
            assert cancelled_record["error"]["code"] == "cancelled", cancelled_record
            result = await core.call("session.evaluate", {"session-id": sid,
                                     "source": '(jobs/result "' + completed["id"] + '")'})
            assert result["result"]["value"] == {"answer": 42}, result
            snapshot = await core.call("session.view", {"session-id": sid})
            assert len(snapshot["state"]["jobs"]) == 3
            assert snapshot["state"]["phase"] == "idle"
            other = (await core.call("session.create", {"name": "Other"}))["id"]
            assert (await core.call("job.list", {"session-id": other}))["jobs"] == []
            print("Jobs RPC passed: start/wait/cancel, saved output/value, abrupt JVM crash, restart interruption without replay, session hydration and scope.")
        finally:
            await core.close()


if __name__ == "__main__":
    asyncio.run(main())
