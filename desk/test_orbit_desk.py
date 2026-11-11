"""Tests for Orbit Desk's agent bridge: python -m unittest discover desk
(no add-ons needed; screen capture isn't tested here)."""
import json
import os
import socket
import subprocess
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import orbit_desk as desk  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "orbit_desk.py")
NO_PROXY = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class DeskTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.port = free_port()
        desk.State.code = "123456"
        desk.State.port = cls.port
        desk.WAIT_SECONDS = 1.0  # short waits keep the tests quick
        cls.server = desk.ThreadingHTTPServer(("0.0.0.0", cls.port), desk.Handler)
        cls.server.daemon_threads = True
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        with desk.State.news:
            desk.State.events = []
            desk.State.recent = []
        desk.State.agents_muted = False
        desk.State.orbit = {}
        desk.State.orbit_at = 0.0
        desk.State.wrong_codes = []
        desk.State.locked_until = 0.0

    # helpers
    def request(self, method, path, body=None, headers=None, host="127.0.0.1"):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(f"http://{host}:{self.port}{path}", data=data, method=method,
                                     headers=headers or {})
        try:
            with NO_PROXY.open(req, timeout=10) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def agent(self, method, path, body=None):
        return self.request(method, path, body, {"X-Orbit-Agent": "1", "Content-Type": "application/json"})

    def phone(self, since=0, boot="", **status):
        q = "&".join([f"since={since}", f"boot={boot}"] + [f"{k}={v}" for k, v in status.items()])
        return self.request("GET", f"/events?{q}", headers={"X-Orbit-Code": "123456"})

    # the phone side
    def test_news_needs_the_pairing_code(self):
        status, _ = self.request("GET", "/events?since=0")
        self.assertEqual(status, 401)
        status, _ = self.request("GET", "/events?since=0", headers={"X-Orbit-Code": "000000"})
        self.assertEqual(status, 401)

    def test_phone_gets_news_as_soon_as_it_arrives(self):
        got = {}

        def ask():
            got["answer"] = self.phone(0, "", present="1")
        t = threading.Thread(target=ask)
        start = time.time()
        t.start()
        time.sleep(0.2)
        self.agent("POST", "/agent/notify", {"kind": "done", "message": "Tests pass", "source": "Codex"})
        t.join()
        self.assertLess(time.time() - start, 0.9)  # didn't wait out the full second
        status, body = got["answer"]
        self.assertEqual(status, 200)
        self.assertEqual([(e["kind"], e["message"], e["source"]) for e in body["events"]],
                         [("done", "Tests pass", "Codex")])
        # Asking again from there: nothing new, after waiting
        status, again = self.phone(body["next"], body["boot"])
        self.assertEqual(again["events"], [])
        self.assertEqual(again["next"], body["next"])

    def test_wait_zero_answers_straight_away(self):
        start = time.time()
        status, body = self.request("GET", "/events?since=0&boot=&wait=0", headers={"X-Orbit-Code": "123456"})
        self.assertEqual(status, 200)
        self.assertLess(time.time() - start, 0.5)
        self.assertEqual(body["events"], [])
        self.assertTrue(body["boot"])

    def test_a_new_boot_starts_over(self):
        self.agent("POST", "/agent/notify", {"kind": "info", "message": "one"})
        _, first = self.phone(0, "")
        # Orbit remembers a since from an older Orbit Desk run: it gets this run's news again
        _, body = self.phone(first["next"] + 50, "old-boot")
        self.assertEqual([e["message"] for e in body["events"]], ["one"])

    def test_status_comes_from_the_phone(self):
        _, body = self.agent("GET", "/agent/status")
        self.assertFalse(body["orbit_connected"])
        self.phone(0, "", present="1", seen="3", asleep="0", talking="1", focus="0", waiting="2")
        _, body = self.agent("GET", "/agent/status")
        self.assertTrue(body["orbit_connected"])
        self.assertTrue(body["person_at_desk"])
        self.assertTrue(body["in_conversation"])
        self.assertFalse(body["focus_mode"])
        self.assertEqual(body["seconds_since_person_seen"], 3)
        self.assertEqual(body["news_waiting_for_person"], 2)

    # the agent side
    def test_agents_need_the_header_and_no_origin(self):
        status, _ = self.request("POST", "/agent/notify", {"kind": "done"})
        self.assertEqual(status, 403)
        status, _ = self.request("POST", "/agent/notify", {"kind": "done"},
                                 {"X-Orbit-Agent": "1", "Origin": "https://evil.example"})
        self.assertEqual(status, 403)
        status, _ = self.request("GET", "/agent/status", headers={"Origin": "null", "X-Orbit-Agent": "1"})
        self.assertEqual(status, 403)

    def test_agents_only_from_this_computer(self):
        address = desk.local_address()
        if address.startswith("127."):
            self.skipTest("no network address to test from")
        try:
            status, _ = self.request("POST", "/agent/notify", {"kind": "done"},
                                     {"X-Orbit-Agent": "1"}, host=address)
        except OSError:
            self.skipTest("can't reach our own network address here")
        self.assertEqual(status, 403)

    def test_bad_news_is_refused(self):
        status, body = self.agent("POST", "/agent/notify", {"kind": "explode"})
        self.assertEqual(status, 400)
        self.assertIn("unknown kind", body["error"])
        status, body = self.agent("POST", "/agent/express", {"move": "self_destruct"})
        self.assertEqual(status, 400)
        status, body = self.agent("POST", "/agent/notify", None)  # no body: an empty request
        self.assertEqual(status, 400)
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/agent/notify", data=b"[1,2]",
                                     method="POST", headers={"X-Orbit-Agent": "1"})
        with self.assertRaises(urllib.error.HTTPError) as caught:
            NO_PROXY.open(req, timeout=5)
        self.assertEqual(caught.exception.code, 400)

    def test_messages_are_cleaned_and_trimmed(self):
        long = "line one\nline two\x07 " + "x" * 400
        self.agent("POST", "/agent/notify", {"kind": "needs-input", "message": long, "source": "Cur\nsor"})
        e = desk.State.events[-1]
        self.assertEqual(e["kind"], "needs_input")
        self.assertNotIn("\n", e["message"])
        self.assertNotIn("\x07", e["message"])
        self.assertLessEqual(len(e["message"]), desk.MAX_MESSAGE)
        self.assertTrue(e["message"].endswith("…"))
        self.assertEqual(e["source"], "Cur sor")

    def test_the_same_news_twice_counts_once(self):
        a = desk.add_event("done", "Build finished", "Codex")
        b = desk.add_event("done", "Build finished", "Codex")
        self.assertEqual(a["id"], b["id"])
        self.assertTrue(b.get("duplicate"))
        self.assertEqual(len(desk.State.events), 1)

    def test_rate_limit(self):
        results = [desk.add_event("info", f"news {i}") for i in range(desk.MAX_EVENTS_PER_MINUTE + 3)]
        self.assertEqual(sum(r["ok"] for r in results), desk.MAX_EVENTS_PER_MINUTE)
        self.assertIn("too much news", results[-1]["error"])

    def test_muted(self):
        desk.State.agents_muted = True
        status, body = self.agent("POST", "/agent/notify", {"kind": "done"})
        self.assertEqual(status, 400)
        self.assertIn("muted", body["error"])

    def test_express(self):
        status, body = self.agent("POST", "/agent/express", {"move": "nod", "source": "Antigravity"})
        self.assertEqual(status, 200)
        e = desk.State.events[-1]
        self.assertEqual((e["kind"], e["move"], e["source"]), ("express", "nod", "Antigravity"))

    def test_notify_answer_says_what_happens_next(self):
        _, body = self.agent("POST", "/agent/notify", {"kind": "done", "message": "a"})
        self.assertIn("isn't connected", desk.describe(body))
        self.phone(0, "", present="0", seen="600")
        _, body = self.agent("POST", "/agent/notify", {"kind": "done", "message": "b"})
        self.assertIn("away from the desk", desk.describe(body))
        self.phone(0, "", present="1")
        _, body = self.agent("POST", "/agent/notify", {"kind": "done", "message": "c"})
        self.assertIn("telling them now", desk.describe(body))

    # commands
    def run_cmd(self, *args, stdin=None, port=None):
        return subprocess.run([sys.executable, SCRIPT, *args, "--port", str(port or self.port)],
                              input=stdin, capture_output=True, text=True, timeout=20)

    def test_notify_command(self):
        r = self.run_cmd("notify", "done", "Deploy finished", "--source", "Make")
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")
        self.assertIn("Queued", r.stderr)
        self.assertEqual(desk.State.events[-1]["message"], "Deploy finished")

    def test_commands_never_fail_when_orbit_desk_is_off(self):
        r = self.run_cmd("notify", "done", "x", port=free_port())
        self.assertEqual(r.returncode, 0)
        self.assertIn("isn't running", r.stderr)

    def test_status_command(self):
        r = self.run_cmd("status")
        self.assertEqual(r.returncode, 0)
        self.assertFalse(json.loads(r.stdout)["orbit_connected"])

    def test_hook_command_reads_json(self):
        r = self.run_cmd("hook", "--source", "Claude Code",
                         stdin=json.dumps({"hook_event_name": "Notification",
                                           "message": "Claude needs your permission to use Bash"}))
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout, "")  # never changes what the agent does
        e = desk.State.events[-1]
        self.assertEqual((e["kind"], e["source"]), ("needs_input", "Claude Code"))
        self.assertIn("permission", e["message"])

    def test_hook_command_takes_json_as_an_argument(self):
        # Codex's notify passes the JSON as the last argument
        r = subprocess.run([sys.executable, SCRIPT, "hook", "--source", "Codex", "--port", str(self.port),
                            json.dumps({"type": "agent-turn-complete",
                                        "last-assistant-message": "Refactor done, tests green."})],
                           capture_output=True, text=True, timeout=20)
        self.assertEqual(r.returncode, 0)
        e = desk.State.events[-1]
        self.assertEqual((e["kind"], e["source"], e["message"]), ("done", "Codex", "Refactor done, tests green."))

    def test_hook_shapes(self):
        f = desk.hook_event
        self.assertEqual(f({"hook_event_name": "Stop", "stop_hook_active": False}), ("done", ""))
        self.assertIsNone(f({"hook_event_name": "Stop", "stop_hook_active": True}))
        self.assertEqual(f({"hook_event_name": "Notification", "message": "m"}), ("needs_input", "m"))
        self.assertEqual(f({"type": "agent-turn-complete", "last-assistant-message": "ok"}), ("done", "ok"))
        self.assertEqual(f({"event": "stop", "status": "error"}), ("failed", ""))
        self.assertEqual(f({"hookEventName": "TaskComplete"}), ("done", ""))
        self.assertIsNone(f({"hook_event_name": "PreToolUse"}))  # not news
        self.assertEqual(f({}), ("done", ""))
        self.assertEqual(f("not json"), ("done", ""))

    # MCP
    def mcp(self, *messages):
        lines = "".join(json.dumps(m) + "\n" for m in messages)
        r = subprocess.run([sys.executable, SCRIPT, "mcp", "--source", "TestAgent", "--port", str(self.port)],
                           input=lines, capture_output=True, text=True, timeout=20)
        self.assertEqual(r.returncode, 0, r.stderr)
        return [json.loads(line) for line in r.stdout.splitlines() if line.strip()]

    def test_mcp_conversation(self):
        answers = self.mcp(
            {"jsonrpc": "2.0", "id": 1, "method": "initialize",
             "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                        "clientInfo": {"name": "test", "version": "1"}}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
             "params": {"name": "orbit_notify", "arguments": {"kind": "needs_input", "message": "Which port?"}}},
            {"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "orbit_status", "arguments": {}}},
            {"jsonrpc": "2.0", "id": 5, "method": "tools/call",
             "params": {"name": "orbit_express", "arguments": {"move": "dance"}}},
            {"jsonrpc": "2.0", "id": 6, "method": "nonsense"},
        )
        self.assertEqual([a["id"] for a in answers], [1, 2, 3, 4, 5, 6])  # nothing for the notification
        self.assertEqual(answers[0]["result"]["protocolVersion"], "2025-03-26")
        self.assertEqual(answers[0]["result"]["serverInfo"]["name"], "orbit")
        names = [t["name"] for t in answers[1]["result"]["tools"]]
        self.assertEqual(names, ["orbit_notify", "orbit_status", "orbit_express"])
        for tool in answers[1]["result"]["tools"]:
            self.assertEqual(tool["inputSchema"]["type"], "object")
        self.assertFalse(answers[2]["result"]["isError"])
        e = desk.State.events[-1]
        self.assertEqual((e["kind"], e["message"], e["source"]), ("needs_input", "Which port?", "TestAgent"))
        self.assertIn("orbit_connected", json.loads(answers[3]["result"]["content"][0]["text"]))
        self.assertTrue(answers[4]["result"]["isError"])
        self.assertEqual(answers[5]["error"]["code"], -32601)

    def test_mcp_unknown_protocol_version_gets_ours(self):
        answers = self.mcp({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                            "params": {"protocolVersion": "1999-01-01"}})
        self.assertEqual(answers[0]["result"]["protocolVersion"], desk.MCP_VERSIONS[0])

    def test_mcp_when_orbit_desk_is_off(self):
        lines = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                            "params": {"name": "orbit_notify", "arguments": {"kind": "done", "message": "x"}}})
        r = subprocess.run([sys.executable, SCRIPT, "mcp", "--port", str(free_port())],
                           input=lines + "\n", capture_output=True, text=True, timeout=20)
        answer = json.loads(r.stdout)
        self.assertTrue(answer["result"]["isError"])
        self.assertIn("isn't running", answer["result"]["content"][0]["text"])


if __name__ == "__main__":
    unittest.main()
