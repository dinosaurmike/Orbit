"""Tests for Orbit Desk: pairing, signed and encrypted requests, and the agent bridge.
    pip install cryptography
    python -m unittest discover -s desk
(screen capture itself isn't tested here: a stand-in picture is encrypted and sent instead)."""
import base64
import hashlib
import hmac
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import orbit_desk as desk  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "orbit_desk.py")
NO_PROXY = urllib.request.build_opener(urllib.request.ProxyHandler({}))
CODE = "123456"  # a made-up pairing code, only for these tests

# Runs orbit_desk.py as if the cryptography add-on weren't installed
WITHOUT_CRYPTO = """
import runpy, sys
class NoCrypto:
    def find_spec(self, name, path=None, target=None):
        if name == "cryptography" or name.startswith("cryptography."):
            raise ImportError("No module named 'cryptography' (blocked for this test)")
sys.meta_path.insert(0, NoCrypto())
script = sys.argv[1]
sys.argv = sys.argv[1:]
runpy.run_path(script, run_name="__main__")
"""


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def hkdf(key, salt, info):
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=info).derive(key)


class Phone:
    """What Orbit does, written separately from orbit_desk.py from the protocol description, so the
    two are checked against each other (the real Kotlin code is checked end to end elsewhere)."""

    def __init__(self, test, device_id=None):
        self.test = test
        self.device_id = device_id or secrets.token_hex(16)
        self.auth = self.enc = None

    def pair(self, code=CODE):
        """Returns (status, answer) of the step that ended it; sets the keys when paired."""
        status, answer = self.begin()
        return self.confirm(code) if status == 200 else (status, answer)

    def begin(self):
        """Step 1: the key swap."""
        key = ec.generate_private_key(ec.SECP256R1())
        pub = key.public_key().public_bytes(serialization.Encoding.X962,
                                            serialization.PublicFormat.UncompressedPoint)
        nonce = secrets.token_bytes(16)
        status, answer = self.test.request("POST", "/pair", {
            "device_id": self.device_id, "phone_pub": base64.b64encode(pub).decode(),
            "phone_nonce": base64.b64encode(nonce).decode()})
        if status != 200:
            return status, answer
        self.test.assertEqual(sorted(answer), ["desk_nonce", "desk_pub"])  # no proof yet
        desk_pub = base64.b64decode(answer["desk_pub"])
        desk_nonce = base64.b64decode(answer["desk_nonce"])
        swapped = b"".join(len(p).to_bytes(4, "big") + p for p in
                           [self.device_id.encode(), pub, nonce, desk_pub, desk_nonce])
        shared = key.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), desk_pub))
        self.swapped = swapped
        self.secret = hkdf(shared, hashlib.sha256(swapped).digest(), b"orbit-desk v3")
        return status, answer

    def confirm(self, code=CODE):
        """Step 2: prove the code, then check the computer's proof."""
        swapped, secret = self.swapped, self.secret
        proof = hmac.new(code.encode(), b"phone" + swapped, hashlib.sha256).digest()
        status, answer = self.test.request("POST", "/pair/confirm", {
            "device_id": self.device_id, "phone_proof": base64.b64encode(proof).decode()})
        if status == 200:
            # The computer proves it knows the code too
            expected = hmac.new(code.encode(), b"desk" + swapped, hashlib.sha256).digest()
            self.test.assertEqual(base64.b64decode(answer["desk_proof"]), expected)
            self.auth, self.enc = hkdf(secret, None, b"auth"), hkdf(secret, None, b"enc")
        return status, answer

    def headers(self, path, method="GET", moment=None, nonce=None, body=b""):
        moment = str(int(time.time() if moment is None else moment))
        nonce = nonce or secrets.token_hex(16)
        text = "\n".join([method, path, moment, nonce, hashlib.sha256(body).hexdigest()]).encode()
        sig = hmac.new(self.auth, text, hashlib.sha256).hexdigest()
        return {"X-Orbit-Device": self.device_id, "X-Orbit-Time": moment, "X-Orbit-Nonce": nonce,
                "X-Orbit-Sig": sig}

    def get(self, path, headers=None):
        """(status, decrypted body or JSON error, raw answer headers)"""
        headers = headers or self.headers(path)
        status, raw, answer_headers = self.test.raw("GET", path, headers=headers)
        if status != 200:
            return status, json.loads(raw or b"{}"), answer_headers
        self.test.assertEqual(answer_headers.get("X-Orbit-Enc"), "1")
        self.test.assertEqual(answer_headers.get("Content-Type"), "application/octet-stream")
        plain = AESGCM(self.enc).decrypt(raw[:12], raw[12:], headers["X-Orbit-Sig"].encode())
        return status, plain, answer_headers

    def get_json(self, path):
        status, body, _ = self.get(path)
        return status, json.loads(body) if status == 200 else body


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class DeskTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.port = free_port()
        # A made-up home folder, so the real ~/.orbit_desk.json is never touched
        cls.home = tempfile.mkdtemp(prefix="orbit-desk-test-")
        desk.CONFIG = Path(cls.home) / ".orbit_desk.json"
        desk.State.code = CODE
        desk.State.port = cls.port
        desk.WAIT_SECONDS = 1.0  # short waits keep the tests quick
        desk.tolerant_console()  # so the suite passes on a computer whose window can't show "…"
        cls.server = desk.ThreadingHTTPServer(("0.0.0.0", cls.port), desk.Handler)
        cls.server.daemon_threads = True
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        shutil.rmtree(cls.home, ignore_errors=True)

    def setUp(self):
        with desk.State.news:
            desk.State.events = []
            desk.State.recent = []
        desk.State.agents_muted = False
        desk.State.orbit = {}
        desk.State.orbit_at = 0.0
        desk.State.wrong_codes = []
        desk.State.locked_until = 0.0
        desk.State.pending = {}
        if not hasattr(self, "orbit"):  # one paired phone for the tests that just need one
            type(self).orbit = Phone(self)
            self.assertEqual(self.orbit.pair()[0], 200)
        self.orbit.test = self

    # helpers
    def raw(self, method, path, body=None, headers=None, host="127.0.0.1"):
        """(status, body bytes, headers)"""
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(f"http://{host}:{self.port}{path}", data=data, method=method,
                                     headers=headers or {})
        try:
            with NO_PROXY.open(req, timeout=10) as r:
                return r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers

    def request(self, method, path, body=None, headers=None, host="127.0.0.1"):
        status, data, _ = self.raw(method, path, body, headers, host)
        return status, json.loads(data or b"{}")

    def agent(self, method, path, body=None):
        return self.request(method, path, body, {"X-Orbit-Agent": "1", "Content-Type": "application/json"})

    def phone(self, since=0, boot="", **status):
        q = "&".join([f"since={since}", f"boot={boot}"] + [f"{k}={v}" for k, v in status.items()])
        return self.orbit.get_json(f"/events?{q}")

    # pairing
    def test_hello_needs_nothing(self):
        status, body = self.request("GET", "/hello")
        self.assertEqual((status, body["app"], body["version"]), (200, "orbit-desk", 3))

    def test_pairing_then_check(self):
        phone = Phone(self)
        status, answer = phone.pair()
        self.assertEqual(status, 200, answer)
        self.assertEqual(answer["name"], desk.State.name)
        status, body = phone.get_json("/check")
        self.assertEqual(status, 200)
        self.assertEqual((body["ok"], body["version"]), (True, 3))
        # The computer keeps the phone's secret in its settings file, readable only by you
        saved = json.loads(desk.CONFIG.read_text())
        self.assertEqual(len(base64.b64decode(saved["devices"][phone.device_id]["secret"])), 32)
        if os.name == "posix":
            self.assertEqual(os.stat(desk.CONFIG).st_mode & 0o777, 0o600)
        # ...and still knows it after a restart
        self.assertEqual(desk.load_devices()[phone.device_id], desk.State.devices[phone.device_id])

    def test_settings_file_keeps_the_code_and_is_private(self):
        desk.CONFIG.write_text(json.dumps({"code": "654321"}))
        if os.name == "posix":
            os.chmod(desk.CONFIG, 0o644)
        self.assertEqual(desk.load_code(False), "654321")
        desk.save_device("ab" * 16, bytes(32))
        saved = json.loads(desk.CONFIG.read_text())
        self.assertEqual(saved["code"], "654321")
        if os.name == "posix":
            self.assertEqual(os.stat(desk.CONFIG).st_mode & 0o777, 0o600)
        self.assertEqual(desk.forget_devices(), 1)
        self.assertEqual(json.loads(desk.CONFIG.read_text()), {"code": "654321"})

    def test_wrong_code(self):
        phone = Phone(self)
        status, answer = phone.pair("000000")
        self.assertEqual(status, 401)
        self.assertIn("pairing code is wrong", answer["error"])
        self.assertNotIn(phone.device_id, desk.State.devices)
        self.assertNotIn("desk_proof", answer)  # nothing to guess the code against
        self.assertEqual(desk.State.wrong_codes and len(desk.State.wrong_codes), 1)
        self.assertEqual(phone.confirm()[0], 410)  # one try per started pairing... then start again:
        status, _ = phone.pair()
        self.assertEqual(status, 200)

    def test_wrong_codes_lock_pairing_for_a_minute(self):
        for _ in range(5):
            self.assertEqual(Phone(self).pair("111111")[0], 401)
        status, answer = Phone(self).pair()  # even the right code, for a minute
        self.assertEqual(status, 429)
        self.assertIn("too many wrong codes", answer["error"])
        # A phone that's already paired doesn't use the code, so it keeps working
        self.assertEqual(self.orbit.get_json("/check")[0], 200)
        desk.State.locked_until = time.time() - 1
        self.assertEqual(Phone(self).pair()[0], 200)

    def test_re_pairing_replaces_the_old_secret(self):
        phone = Phone(self)
        phone.pair()
        old_auth = phone.auth
        self.assertEqual(phone.pair()[0], 200)
        self.assertNotEqual(phone.auth, old_auth)
        phone.auth = old_auth
        self.assertEqual(phone.get("/check")[0], 401)

    def test_pairing_expires(self):
        phone = Phone(self)
        real = desk.PAIR_SECONDS
        desk.PAIR_SECONDS = 0  # every started pairing is already too old
        try:
            status, answer = phone.pair()
        finally:
            desk.PAIR_SECONDS = real
        self.assertEqual(status, 410)
        self.assertIn("took too long", answer["error"])
        self.assertEqual(desk.State.wrong_codes, [])  # not a wrong code

    def test_odd_pairing_requests(self):
        good = {"device_id": secrets.token_hex(16),
                "phone_pub": base64.b64encode(b"\x04" + bytes(64)).decode(),  # not on the curve
                "phone_nonce": base64.b64encode(secrets.token_bytes(16)).decode()}
        self.assertEqual(self.request("POST", "/pair", good)[0], 400)
        self.assertEqual(self.request("POST", "/pair", dict(good, device_id="../../x"))[0], 400)
        self.assertEqual(self.request("POST", "/pair", [1, 2])[0], 400)
        status, _ = self.request("POST", "/pair/confirm", {"device_id": "nobody", "phone_proof": "x"})
        self.assertEqual(status, 410)

    # signed requests
    def test_old_code_header_is_refused(self):
        status, answer = self.request("GET", "/check", headers={"X-Orbit-Code": CODE})
        self.assertEqual(status, 426)
        self.assertIn("Orbit Desk was updated", answer["error"])
        for path in ("/screen", "/events?since=0"):
            self.assertEqual(self.request("GET", path, headers={"X-Orbit-Code": CODE})[0], 426)
        status, answer = self.request("GET", "/check")
        self.assertEqual(status, 401)
        self.assertIn("not paired", answer["error"])

    def test_unknown_device(self):
        stranger = Phone(self)
        stranger.auth = secrets.token_bytes(32)
        status, answer, _ = stranger.get("/check")
        self.assertEqual(status, 401)
        self.assertEqual(answer["error"], "not paired: pair again")

    def test_tampered_signature(self):
        headers = self.orbit.headers("/check")
        bad = dict(headers, **{"X-Orbit-Sig": headers["X-Orbit-Sig"][:-1] +
                               ("0" if headers["X-Orbit-Sig"][-1] != "0" else "1")})
        status, answer, _ = self.orbit.get("/check", bad)
        self.assertEqual(status, 401)
        self.assertIn("signature is wrong", answer["error"])
        # A signature for one request doesn't work for another (path and query are signed)
        status, _, _ = self.orbit.get("/events?since=0&wait=0", self.orbit.headers("/events?since=0&wait=1"))
        self.assertEqual(status, 401)
        status, _, _ = self.orbit.get("/screen", self.orbit.headers("/check"))
        self.assertEqual(status, 401)
        # ...and the time is signed too
        moved = dict(headers, **{"X-Orbit-Time": str(int(headers["X-Orbit-Time"]) + 1)})
        self.assertEqual(self.orbit.get("/check", moved)[0], 401)

    def test_replay(self):
        headers = self.orbit.headers("/check")
        self.assertEqual(self.orbit.get("/check", headers)[0], 200)
        status, answer, _ = self.orbit.get("/check", headers)
        self.assertEqual(status, 401)
        self.assertIn("already used", answer["error"])

    def test_clock_skew(self):
        status, answer, _ = self.orbit.get("/check", self.orbit.headers("/check", moment=time.time() - 600))
        self.assertEqual(status, 401)
        self.assertIn("10 minutes behind", answer["error"])
        self.assertIn("automatic date and time", answer["error"])
        status, answer, _ = self.orbit.get("/check", self.orbit.headers("/check", moment=time.time() + 300))
        self.assertIn("ahead of", answer["error"])
        # A minute off is fine
        self.assertEqual(self.orbit.get("/check", self.orbit.headers("/check", moment=time.time() - 60))[0], 200)

    # encrypted answers
    def test_screen_picture_is_encrypted(self):
        fake = b"\xff\xd8\xff\xe0 a stand-in JPEG " + secrets.token_bytes(2000)
        real_capture, real_gap = desk.capture, desk.MIN_SECONDS_BETWEEN
        desk.capture, desk.MIN_SECONDS_BETWEEN = (lambda monitor: fake), 0
        try:
            headers = self.orbit.headers("/screen")
            status, raw, answer_headers = self.raw("GET", "/screen", headers=headers)
            self.assertEqual(status, 200)
            self.assertNotIn(fake[20:60], raw)  # not readable on the way
            self.assertEqual(self.orbit.get("/screen")[1], fake)
            # Only the phone's key opens it, only for the request it answers, and only unchanged
            sig = headers["X-Orbit-Sig"].encode()
            self.assertEqual(desk.unseal(self.orbit.enc, raw, sig), fake)
            with self.assertRaises(InvalidTag):
                desk.unseal(self.orbit.enc, raw, self.orbit.headers("/screen")["X-Orbit-Sig"].encode())
            with self.assertRaises(InvalidTag):
                desk.unseal(secrets.token_bytes(32), raw, sig)
            changed = bytearray(raw)
            changed[40] ^= 1
            with self.assertRaises(InvalidTag):
                desk.unseal(self.orbit.enc, bytes(changed), sig)
            desk.State.paused = True
            self.assertEqual(self.orbit.get("/screen")[0], 423)
        finally:
            desk.capture, desk.MIN_SECONDS_BETWEEN = real_capture, real_gap
            desk.State.paused = False

    def test_news_is_encrypted(self):
        self.agent("POST", "/agent/notify", {"kind": "done", "message": "Secret project shipped"})
        headers = self.orbit.headers("/events?since=0&boot=&wait=0")
        status, raw, _ = self.raw("GET", "/events?since=0&boot=&wait=0", headers=headers)
        self.assertEqual(status, 200)
        self.assertNotIn(b"Secret project", raw)
        news = json.loads(desk.unseal(self.orbit.enc, raw, headers["X-Orbit-Sig"].encode()))
        self.assertEqual(news["events"][0]["message"], "Secret project shipped")

    # the phone side
    def test_news_needs_pairing(self):
        status, _ = self.request("GET", "/events?since=0")
        self.assertEqual(status, 401)
        status, _ = self.request("GET", "/events?since=0", headers={"X-Orbit-Code": "000000"})
        self.assertEqual(status, 426)

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
        status, body = self.orbit.get_json("/events?since=0&boot=&wait=0")
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

    def run_without_crypto(self, *args, stdin=None):
        env = dict(os.environ, HOME=self.home, USERPROFILE=self.home)
        return subprocess.run([sys.executable, "-c", WITHOUT_CRYPTO, SCRIPT, *args],
                              input=stdin, capture_output=True, text=True, timeout=20, env=env)

    def test_agent_commands_work_without_the_cryptography_add_on(self):
        port = str(self.port)
        r = self.run_without_crypto("notify", "done", "No add-ons here", "--source", "Make", "--port", port)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("Queued", r.stderr)
        self.assertEqual(desk.State.events[-1]["message"], "No add-ons here")
        r = self.run_without_crypto("express", "nod", "--port", port)
        self.assertIn("Queued", r.stderr)
        r = self.run_without_crypto("status", "--port", port)
        self.assertIn("orbit_connected", json.loads(r.stdout))
        r = self.run_without_crypto("hook", "--source", "Claude Code", "--port", port,
                                    stdin=json.dumps({"hook_event_name": "Stop"}))
        self.assertEqual((r.returncode, r.stdout), (0, ""))
        self.assertEqual(desk.State.events[-1]["source"], "Claude Code")
        r = self.run_without_crypto("mcp", "--port", port, stdin=json.dumps(
            {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
             "params": {"name": "orbit_notify", "arguments": {"kind": "info", "message": "via MCP"}}}) + "\n")
        self.assertFalse(json.loads(r.stdout)["result"]["isError"], r.stdout)
        self.assertEqual(desk.State.events[-1]["message"], "via MCP")
        # The block really works: the server itself says what to install
        r = self.run_without_crypto("--port", str(free_port()))
        self.assertEqual(r.returncode, 1)
        self.assertIn("pip install cryptography", r.stdout)
        self.assertFalse(os.path.exists(os.path.join(self.home, ".orbit_desk.json.tmp")))

    def test_agents_can_send_any_language_and_emoji(self):
        # Like Windows: its own code page for the console, while agents send UTF-8
        env = dict(os.environ, PYTHONIOENCODING="cp1252")
        text = "Café build ✅ — 完成"
        line = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {
            "name": "orbit_notify", "arguments": {"kind": "done", "message": text}}}, ensure_ascii=False)
        r = subprocess.run([sys.executable, SCRIPT, "mcp", "--port", str(self.port)],
                           input=(line + "\n").encode("utf-8"), capture_output=True, timeout=20, env=env)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(desk.State.events[-1]["message"], text)
        hook = json.dumps({"hook_event_name": "Notification", "message": "Ça marche ✅"}, ensure_ascii=False)
        r = subprocess.run([sys.executable, SCRIPT, "hook", "--source", "Claude Code", "--port", str(self.port)],
                           input=hook.encode("utf-8"), capture_output=True, timeout=20, env=env)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("Ça marche ✅", desk.State.events[-1]["message"])

    def test_the_window_never_crashes_on_an_emoji(self):
        env = dict(os.environ, PYTHONIOENCODING="ascii")
        r = subprocess.run([sys.executable, "-c", "import sys; sys.path.insert(0, sys.argv[1]); "
                            "import orbit_desk; orbit_desk.tolerant_console(); print('Build \\u2705 done\\u2026')", HERE],
                           capture_output=True, timeout=20, env=env)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.strip(), b"Build ? done?")

    def test_loading_orbit_desk_doesnt_load_cryptography(self):
        r = subprocess.run([sys.executable, "-c", "import sys; sys.path.insert(0, sys.argv[1]); "
                            "import orbit_desk; print('cryptography' in sys.modules)", HERE],
                           capture_output=True, text=True, timeout=20)
        self.assertEqual(r.stdout.strip(), "False", r.stderr)

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
