#!/usr/bin/env python3
"""Orbit Desk: connects Orbit, the desk companion, to this computer.

1. Orbit can look at this computer's screen when you ask it to ("what do you think of this?").
   It takes one picture through this program, over your home network. It never looks on its own.
2. Coding agents and scripts on this computer can get your attention through Orbit: it looks at
   you, reacts, and says the news when a task finishes or needs you. Works with any agent:
   through MCP (Antigravity, Cursor, Codex, Claude Code, ...), a command, or a local web request.

    pip install mss pillow cryptography
    python orbit_desk.py

Options:
    --monitor N    which screen to share: 1 = main screen (default), 2 = second..., 0 = all of them
    --new-code     make a new pairing code (for pairing another phone; paired phones keep working)
    --unpair       forget every paired phone (each will need to pair again)
    --port N       use a different port (default 47800)

Orbit pairs once with the 6-digit code; after that every request is signed and what the computer
sends back (screen pictures, agent news) is encrypted. See README.md, "Privacy and security".

Commands for agents and scripts (Orbit Desk must be running; these need no add-ons):
    python orbit_desk.py notify done "Tests pass"        kinds: done, needs_input, failed, info
    python orbit_desk.py notify needs_input "Which database?" --source Codex
    python orbit_desk.py express nod                     a reaction with no news
    python orbit_desk.py status                          is Orbit there, and are you at your desk?
    python orbit_desk.py mcp                             MCP server (stdio) for coding agents
    python orbit_desk.py hook                            for agents' hook settings (reads JSON)

While it runs: type  p  then Enter to pause or resume screen sharing,  a  then Enter to mute or
unmute agents,  q  then Enter to quit.
MIT licensed, part of the Orbit project.
"""
import argparse
import base64
import hashlib
import hmac
import io
import json
import os
import re
import secrets
import socket
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

VERSION = 3              # 3: one-time pairing, signed requests, encrypted answers
DEFAULT_PORT = 47800
DISCOVERY_PORT = 47801  # Orbit asks "ORBIT-DESK?" here to find computers on the network
CONFIG = Path.home() / ".orbit_desk.json"
MAX_SIDE = 1600         # longest side of the picture sent, in pixels (enough to read text)
JPEG_QUALITY = 70
MIN_SECONDS_BETWEEN = 2  # at most one picture every 2 seconds

# Agent news
KINDS = ("done", "needs_input", "failed", "info")
MOVES = ("nod", "shake_head", "happy", "celebrate", "surprised", "curious", "perk", "sad",
         "wink", "ponder", "love", "boing")
MAX_MESSAGE = 200        # characters; Orbit says it out loud, so keep it short
MAX_SOURCE = 40
MAX_EVENTS_PER_MINUTE = 12   # a looping agent can't make Orbit twitch nonstop
KEEP_EVENTS_SECONDS = 3600   # news Orbit hasn't collected after an hour is dropped
WAIT_SECONDS = 15            # how long Orbit's "anything new?" request waits for news
ORBIT_GONE_SECONDS = 45      # no request from Orbit for this long: it's not connected

# Pairing and signed requests
PAIR_SECONDS = 120           # a pairing that's been started must be finished within 2 minutes
MAX_PENDING = 20             # pairings started but not finished (older ones are dropped)
CLOCK_SKEW_SECONDS = 120     # a signed request must be this close to the computer's clock
NONCE_SECONDS = 600          # each request's random nonce is remembered this long (no replays)
PROTOCOL_INFO = b"orbit-desk v3"


class State:
    code = ""
    name = socket.gethostname()
    monitor = 1
    paused = False
    agents_muted = False
    port = DEFAULT_PORT
    last_picture = 0.0
    wrong_codes = []     # times of recent wrong codes
    locked_until = 0.0   # too many wrong codes: refuse everything for a minute
    lock = threading.Lock()
    # News for Orbit: [{"id", "kind", "message", "source", "move", "at"}], newest last
    events = []
    next_id = 1
    recent = []          # times of recent events (rate limit)
    news = threading.Condition()
    # What Orbit last said about itself (when it asked for news), and when
    orbit = {}
    orbit_at = 0.0
    boot = secrets.token_hex(4)  # changes each time Orbit Desk starts, so Orbit knows to start over
    # Paired phones: {device_id: {"auth": key, "enc": key}}, loaded from CONFIG
    devices = {}
    pending = {}         # pairings started but not finished: {device_id: {...}}
    nonces = {}          # {(device_id, nonce): time} of recent signed requests (no replays)


def read_config() -> dict:
    try:
        data = json.loads(CONFIG.read_text())
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def write_config(data: dict):
    """Saves the settings file so only you can read it (it holds the keys of paired phones).
    Written to a new file first, then swapped in, so a crash can't leave half a file."""
    temp = CONFIG.with_name(CONFIG.name + ".tmp")
    fd = os.open(str(temp), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(data, f, indent=1)
    try:
        os.chmod(str(temp), 0o600)  # in case the file was already there with looser permissions
    except OSError:
        pass  # Windows: files in your home folder are already private to you
    os.replace(str(temp), str(CONFIG))


def load_code(new: bool) -> str:
    """The pairing code, kept in a small file in your home folder so it stays the same."""
    data = read_config()
    if new or not str(data.get("code", "")).isdigit():
        data["code"] = f"{secrets.randbelow(10**6):06d}"
        write_config(data)
    return data["code"]


def load_devices() -> dict:
    """The paired phones' keys, from the settings file (anything unreadable is skipped)."""
    devices = {}
    for device_id, entry in (read_config().get("devices") or {}).items():
        try:
            secret = base64.b64decode(entry["secret"])
            if len(secret) == 32:
                devices[device_id] = device_keys(secret)
        except (KeyError, TypeError, ValueError):
            pass
    return devices


def save_device(device_id: str, secret: bytes):
    data = read_config()
    devices = data.get("devices") if isinstance(data.get("devices"), dict) else {}
    devices[device_id] = {"secret": base64.b64encode(secret).decode(),
                          "paired": datetime.now().strftime("%Y-%m-%d %H:%M")}
    data["devices"] = devices
    write_config(data)


def forget_devices() -> int:
    data = read_config()
    count = len(data.pop("devices", None) or {})
    write_config(data)
    return count


# ---- Pairing and encryption ----
#
# Pairing (once): Orbit and this computer each make a one-off key pair and swap the public halves
# (ECDH). Both then prove they know the 6-digit code by signing everything swapped with it, so a
# stranger on the network can't pair, and nobody watching can work out the shared secret. After
# that the code isn't sent or needed: every request carries a signature made with the secret, and
# answers are encrypted with it.
#
# The cryptography add-on is imported inside these functions, not at the top, so the agent
# commands (notify, status, hook, mcp) keep working on a computer that doesn't have it.

def have_crypto() -> bool:
    try:
        import cryptography.hazmat.primitives.asymmetric.ec  # noqa: F401
        import cryptography.hazmat.primitives.ciphers.aead  # noqa: F401
        import cryptography.hazmat.primitives.kdf.hkdf  # noqa: F401
        return True
    except ImportError:
        return False


def hkdf(key: bytes, salt, info: bytes) -> bytes:
    """32 bytes of key material from `key` (HKDF-SHA256). salt None = 32 zero bytes."""
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=info).derive(key)


def transcript(device_id: str, phone_pub: bytes, phone_nonce: bytes, desk_pub: bytes,
               desk_nonce: bytes) -> bytes:
    """Everything both sides swapped while pairing, in a fixed order. Each part is preceded by
    its length (4 bytes), so two different swaps can never produce the same bytes."""
    parts = [device_id.encode(), phone_pub, phone_nonce, desk_pub, desk_nonce]
    return b"".join(len(p).to_bytes(4, "big") + p for p in parts)


def pairing_proof(code: str, who: bytes, swapped: bytes) -> bytes:
    """Proof that `who` (b"phone" or b"desk") knows the code, tied to this one pairing."""
    return hmac.new(code.encode(), who + swapped, hashlib.sha256).digest()


def device_keys(secret: bytes) -> dict:
    """Two separate keys from a paired phone's secret: one signs requests, one encrypts answers."""
    return {"auth": hkdf(secret, None, b"auth"), "enc": hkdf(secret, None, b"enc")}


def signing_text(method: str, path: str, moment: str, nonce: str, body: bytes = b"") -> bytes:
    return "\n".join([method, path, moment, nonce, hashlib.sha256(body).hexdigest()]).encode()


def seal(enc_key: bytes, plain: bytes, aad: bytes) -> bytes:
    """AES-256-GCM: a random 12-byte IV, then the encrypted bytes and their 16-byte tag. `aad` is
    the request's signature, so an answer only fits the request it was made for."""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    iv = secrets.token_bytes(12)
    return iv + AESGCM(enc_key).encrypt(iv, plain, aad)


def unseal(enc_key: bytes, sealed: bytes, aad: bytes) -> bytes:
    """The other way (what Orbit does). Raises if anything was changed on the way."""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM(enc_key).decrypt(sealed[:12], sealed[12:], aad)


def start_pairing(device_id: str, phone_pub: bytes, phone_nonce: bytes) -> dict:
    """The computer's half of the key swap. Returns what to send back (no proof yet: the phone
    proves it knows the code first, so nobody can ask this computer for something to guess the
    code against)."""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    phone_key = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), phone_pub)  # checks it
    mine = ec.generate_private_key(ec.SECP256R1())
    desk_pub = mine.public_key().public_bytes(serialization.Encoding.X962,
                                              serialization.PublicFormat.UncompressedPoint)
    desk_nonce = secrets.token_bytes(16)
    swapped = transcript(device_id, phone_pub, phone_nonce, desk_pub, desk_nonce)
    shared = mine.exchange(ec.ECDH(), phone_key)
    secret = hkdf(shared, hashlib.sha256(swapped).digest(), PROTOCOL_INFO)
    return {"transcript": swapped, "secret": secret, "at": time.time(),
            "answer": {"desk_pub": base64.b64encode(desk_pub).decode(),
                       "desk_nonce": base64.b64encode(desk_nonce).decode()}}


def capture(monitor: int) -> bytes:
    """One picture of the screen, shrunk and saved as a JPEG."""
    import mss  # imported here so the rest works (and shows a clear message) without it
    from PIL import Image

    with mss.mss() as screens:
        if monitor >= len(screens.monitors):
            monitor = 1
        shot = screens.grab(screens.monitors[monitor])
        picture = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
    picture.thumbnail((MAX_SIDE, MAX_SIDE))
    out = io.BytesIO()
    picture.save(out, "JPEG", quality=JPEG_QUALITY)
    return out.getvalue()


def local_address() -> str:
    """This computer's address on the home network (no data is actually sent)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.168.0.1", 9))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


def now() -> str:
    return datetime.now().strftime("%H:%M:%S")


def clean(text, limit: int) -> str:
    """One short line of plain text: no control characters, no runs of spaces."""
    text = re.sub(r"[\x00-\x1f\x7f]+", " ", str(text or ""))
    text = re.sub(r"\s+", " ", text).strip()
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


# ---- News for Orbit ----

def add_event(kind: str, message: str = "", source: str = "", move: str = "") -> dict:
    """Queues news for Orbit. Returns {"ok": True, "id": n} or {"ok": False, "error": ...}."""
    kind = str(kind or "").strip().lower().replace("-", "_")
    if kind == "needs_you":
        kind = "needs_input"
    move = str(move or "").strip().lower()
    if kind == "express":
        if move not in MOVES:
            return {"ok": False, "error": f"unknown move; use one of: {', '.join(MOVES)}"}
    elif kind not in KINDS:
        return {"ok": False, "error": f"unknown kind; use one of: {', '.join(KINDS)}"}
    if State.agents_muted:
        return {"ok": False, "error": "agents are muted on this computer (type  a  in Orbit Desk)"}
    message = clean(message, MAX_MESSAGE)
    source = clean(source, MAX_SOURCE)
    moment = time.time()
    with State.news:
        State.recent = [t for t in State.recent if moment - t < 60]
        if len(State.recent) >= MAX_EVENTS_PER_MINUTE:
            return {"ok": False, "error": "too much news this minute; try again shortly"}
        # The same news twice within 10 seconds (a hook and the agent both telling): once is enough
        for e in reversed(State.events):
            if moment - e["at"] > 10:
                break
            if (e["kind"], e["message"], e["source"], e["move"]) == (kind, message, source, move):
                return {"ok": True, "id": e["id"], "duplicate": True}
        State.recent.append(moment)
        event = {"id": State.next_id, "kind": kind, "message": message, "source": source,
                 "move": move, "at": moment}
        State.next_id += 1
        State.events = [e for e in State.events if moment - e["at"] < KEEP_EVENTS_SECONDS][-50:]
        State.events.append(event)
        State.news.notify_all()
    who = source or "An agent"
    what = move if kind == "express" else kind.replace("_", " ")
    print(f"[{now()}] {who}: {what}" + (f": {message}" if message else ""))
    return {"ok": True, "id": event["id"]}


def wait_for_events(since: int, seconds: float) -> tuple:
    """(news newer than `since`, the id to ask from next time), waiting up to `seconds`."""
    until = time.time() + seconds
    with State.news:
        since = max(0, min(since, State.next_id - 1))
        while True:
            fresh = [e for e in State.events if e["id"] > since]
            left = until - time.time()
            if fresh or left <= 0:
                return fresh, max([e["id"] for e in fresh], default=since)
            State.news.wait(left)


def orbit_status() -> dict:
    """What Orbit last said about itself, for agents deciding whether to wait."""
    age = time.time() - State.orbit_at
    if State.orbit_at == 0 or age > ORBIT_GONE_SECONDS:
        return {"orbit_connected": False,
                "note": "Orbit isn't connected (switched off, asleep in privacy mode, on another "
                        "network, or agents are off in its settings). News waits here for an hour."}
    o = State.orbit
    status = {
        "orbit_connected": True,
        "person_at_desk": o.get("present") == "1",
        "seconds_since_person_seen": int(o["seen"]) if str(o.get("seen", "")).isdigit() else None,
        "orbit_asleep": o.get("asleep") == "1",
        "in_conversation": o.get("talking") == "1",
        "focus_mode": o.get("focus") == "1",
        "news_waiting_for_person": int(o["waiting"]) if str(o.get("waiting", "")).isdigit() else 0,
        "updated_seconds_ago": int(age),
    }
    if State.agents_muted:
        status["agents_muted"] = True
    return status


# ---- The web server (Orbit on the network, agents on this computer) ----

LOCAL = ("127.0.0.1", "::1", "::ffff:127.0.0.1")


DEVICE_ID = re.compile(r"[0-9a-f]{16,64}")
NONCE = re.compile(r"[0-9a-f]{32}")
SIGNATURE = re.compile(r"[0-9a-f]{64}")
UNIX_TIME = re.compile(r"[0-9]{1,12}")


class Handler(BaseHTTPRequestHandler):
    server_version = "OrbitDesk/3"

    def log_message(self, *args):  # keep the window quiet; we print our own lines
        pass

    def send(self, status: int, body: bytes, kind: str = "application/json", headers=None):
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, status: int, data: dict):
        self.send(status, json.dumps(data).encode())

    def send_sealed(self, device: dict, body: bytes):
        """An encrypted answer for a paired phone (only it can read it, and only for this request)."""
        sealed = seal(device["enc"], body, self.headers.get("X-Orbit-Sig", "").encode())
        self.send(200, sealed, "application/octet-stream", {"X-Orbit-Enc": "1"})

    def read_body(self) -> bytes:
        try:
            length = min(max(int(self.headers.get("Content-Length", "0")), 0), 16_000)
        except ValueError:
            length = 0
        return self.rfile.read(length) if length else b""

    def read_json(self, body: bytes):
        """The request's JSON object, or None (after answering) if it isn't one."""
        try:
            data = json.loads(body or b"{}")
            if isinstance(data, dict):
                return data
        except ValueError:
            pass
        self.send_json(400, {"error": "send a JSON object"})
        return None

    def pairing_locked(self) -> bool:
        """Too many wrong codes lately: refuse to pair for a minute (answering if so). Phones that
        are already paired keep working; they don't use the code."""
        if time.time() < State.locked_until:
            self.send_json(429, {"error": "too many wrong codes; try again in a minute"})
            return True
        return False

    def wrong_code(self):
        """Counts a wrong pairing code, and slows down anyone guessing."""
        moment = time.time()
        State.wrong_codes = [t for t in State.wrong_codes if moment - t < 60] + [moment]
        if len(State.wrong_codes) >= 5:
            State.locked_until = moment + 60
            print(f"[{now()}] Several wrong pairing codes from {self.client_address[0]}: "
                  "refusing to pair for a minute.")

    def signed(self, body: bytes = b""):
        """Checks a paired phone's signature (answering if it's missing or wrong). Returns that
        phone's keys, or None. A copied request is no use to anyone: each has its own time and
        nonce, and the computer refuses one it has seen before or that's more than 2 minutes off."""
        sig = self.headers.get("X-Orbit-Sig", "").strip().lower()
        if not sig:
            if self.headers.get("X-Orbit-Code"):  # an Orbit app from before pairing existed
                self.send_json(426, {"error": "Orbit Desk was updated: update the Orbit app, then "
                                              "tap Pair (Device > Looking at your computer screen)"})
            else:
                self.send_json(401, {"error": "not paired: pair again"})
            return None
        device_id = self.headers.get("X-Orbit-Device", "").strip()
        moment = self.headers.get("X-Orbit-Time", "").strip()
        nonce = self.headers.get("X-Orbit-Nonce", "").strip().lower()
        with State.lock:
            device = State.devices.get(device_id)
        if device is None:
            self.send_json(401, {"error": "not paired: pair again"})
            return None
        expected = hmac.new(device["auth"], signing_text(self.command, self.path, moment, nonce, body),
                            hashlib.sha256).hexdigest()
        if not (UNIX_TIME.fullmatch(moment) and NONCE.fullmatch(nonce) and SIGNATURE.fullmatch(sig)
                and hmac.compare_digest(sig, expected)):
            self.send_json(401, {"error": "the request's signature is wrong: pair again"})
            return None
        off = int(moment) - time.time()
        if abs(off) > CLOCK_SKEW_SECONDS:
            self.send_json(401, {"error": f"the phone's clock is {abs(off) / 60:.0f} minutes "
                                          f"{'ahead of' if off > 0 else 'behind'} the computer's: "
                                          "turn on automatic date and time on both"})
            return None
        with State.lock:
            cutoff = time.time() - NONCE_SECONDS
            State.nonces = {k: t for k, t in State.nonces.items() if t > cutoff}
            if (device_id, nonce) in State.nonces:
                self.send_json(401, {"error": "this request was already used once (replayed)"})
                return None
            State.nonces[(device_id, nonce)] = time.time()
        return device

    def query(self) -> dict:
        from urllib.parse import parse_qs, urlparse
        return {k: v[-1] for k, v in parse_qs(urlparse(self.path).query).items()}

    def agent_ok(self) -> bool:
        """Agent requests: only from this computer, and never from a web page. (A page in your
        browser can send simple requests to this computer; it can't add a custom header without
        asking first, and it always sends Origin.)"""
        if self.client_address[0] not in LOCAL:
            self.send_json(403, {"error": "agents can only use Orbit Desk from this computer"})
            return False
        if self.headers.get("Origin") or self.headers.get("X-Orbit-Agent") != "1":
            self.send_json(403, {"error": "send the header  X-Orbit-Agent: 1  (and no Origin)"})
            return False
        return True

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/hello":  # anyone may ask what this is (no code needed, nothing private)
            self.send_json(200, {"app": "orbit-desk", "name": State.name, "version": VERSION})
        elif path == "/check":
            device = self.signed(self.read_body())
            if device:
                self.send_sealed(device, json.dumps({"ok": True, "name": State.name,
                                                     "paused": State.paused, "version": VERSION}).encode())
        elif path == "/screen":
            device = self.signed(self.read_body())
            if not device:
                return
            if State.paused:
                self.send_json(423, {"error": "sharing is paused on the computer"})
                return
            with State.lock:
                wait = State.last_picture + MIN_SECONDS_BETWEEN - time.time()
                if wait > 0:
                    time.sleep(wait)
                State.last_picture = time.time()
            try:
                picture = capture(State.monitor)
            except ImportError:
                print(f"[{now()}] Can't take a picture: run  pip install mss pillow  first.")
                self.send_json(500, {"error": "Orbit Desk is missing mss/pillow"})
                return
            except Exception as e:  # e.g. no display
                print(f"[{now()}] Couldn't take a picture: {e}")
                self.send_json(500, {"error": f"couldn't take a picture: {e}"})
                return
            print(f"[{now()}] Orbit looked at your screen ({len(picture) // 1024} KB).")
            self.send_sealed(device, picture)
        elif path == "/events":
            # Orbit asking for news. It says how it's doing (is someone there, is it asleep...)
            # and waits here until there's news or WAIT_SECONDS pass.
            device = self.signed(self.read_body())
            if not device:
                return
            q = self.query()
            was_connected = time.time() - State.orbit_at < ORBIT_GONE_SECONDS
            State.orbit = {k: clean(q.get(k, ""), 12) for k in
                           ("present", "seen", "asleep", "talking", "focus", "waiting")}
            State.orbit_at = time.time()
            if not was_connected:
                print(f"[{now()}] Orbit connected for agent news.")
            try:
                since = int(q.get("since", "0"))
            except ValueError:
                since = 0
            if q.get("boot") != State.boot:  # first ask since Orbit Desk (or Orbit) started
                since = 0
            try:  # Orbit asks with wait=0 when it (re)connects, to know straight away it works
                wait = max(0.0, min(float(q.get("wait", WAIT_SECONDS)), WAIT_SECONDS))
            except ValueError:
                wait = WAIT_SECONDS
            fresh, next_since = wait_for_events(since, wait)
            State.orbit_at = time.time()
            news = {"events": [{k: e[k] for k in ("id", "kind", "message", "source", "move")}
                               for e in fresh],
                    "next": next_since, "boot": State.boot}
            self.send_sealed(device, json.dumps(news).encode())
        elif path == "/agent/status":
            if self.agent_ok():
                self.send_json(200, orbit_status())
        else:
            self.send_json(404, {"error": "not found"})

    def do_POST(self):
        path = self.path.split("?")[0]
        if path == "/pair":
            self.pair()
        elif path == "/pair/confirm":
            self.pair_confirm()
        elif path in ("/agent/notify", "/agent/express"):
            self.agent_post(path)
        else:
            self.send_json(404, {"error": "not found"})

    def pair(self):
        """Step 1 of pairing: the phone sends its half of the key swap, we answer with ours."""
        with State.lock:
            if self.pairing_locked():
                return
        body = self.read_json(self.read_body())
        if body is None:
            return
        try:
            device_id = str(body.get("device_id", ""))
            phone_pub = base64.b64decode(str(body.get("phone_pub", "")), validate=True)
            phone_nonce = base64.b64decode(str(body.get("phone_nonce", "")), validate=True)
            if not DEVICE_ID.fullmatch(device_id) or len(phone_pub) != 65 or \
                    not 16 <= len(phone_nonce) <= 64:
                raise ValueError
            started = start_pairing(device_id, phone_pub, phone_nonce)
        except ValueError:  # includes a public key that isn't a point on the curve
            self.send_json(400, {"error": "odd pairing request; update the Orbit app"})
            return
        with State.lock:
            moment = time.time()
            State.pending = {k: p for k, p in State.pending.items() if moment - p["at"] < PAIR_SECONDS}
            while len(State.pending) >= MAX_PENDING:
                State.pending.pop(min(State.pending, key=lambda k: State.pending[k]["at"]))
            State.pending[device_id] = started
        self.send_json(200, started["answer"])

    def pair_confirm(self):
        """Step 2: the phone proves it knows the code. Only then does the computer prove it too
        (so nobody can get something from this computer to guess the code against), and the
        phone is paired. Each started pairing gets one try."""
        body = self.read_json(self.read_body())
        if body is None:
            return
        device_id = str(body.get("device_id", ""))
        try:
            given = base64.b64decode(str(body.get("phone_proof", "")), validate=True)
        except ValueError:
            given = b""
        with State.lock:
            if self.pairing_locked():
                return
            started = State.pending.pop(device_id, None)
            if started is None or time.time() - started["at"] >= PAIR_SECONDS:
                self.send_json(410, {"error": "pairing took too long: tap Pair again"})
                return
            if not hmac.compare_digest(given, pairing_proof(State.code, b"phone", started["transcript"])):
                self.wrong_code()
                self.send_json(401, {"error": "the pairing code is wrong"})
                return
            save_device(device_id, started["secret"])
            State.devices[device_id] = device_keys(started["secret"])
        print(f"[{now()}] A phone paired with this computer ({self.client_address[0]}, "
              f"device {device_id[:6]}). Not you? Run with  --unpair .")
        self.send_json(200, {"ok": True, "name": State.name, "version": VERSION, "desk_proof":
                             base64.b64encode(pairing_proof(State.code, b"desk", started["transcript"])).decode()})

    def agent_post(self, path: str):
        if not self.agent_ok():
            return
        body = self.read_body()
        try:
            body = json.loads(body or b"{}")
            if not isinstance(body, dict):
                raise ValueError
        except ValueError:
            self.send_json(400, {"error": "send a JSON object"})
            return
        if path == "/agent/express":
            result = add_event("express", source=body.get("source", ""), move=body.get("move", ""))
        else:
            result = add_event(body.get("kind", ""), body.get("message", ""), body.get("source", ""))
        if result.get("ok"):
            result["orbit"] = orbit_status()
        self.send_json(200 if result.get("ok") else 400, result)


def answer_discovery():
    """Replies when Orbit looks for computers on the network."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("", DISCOVERY_PORT))
        except OSError as e:
            print(f"(Finding this computer automatically won't work: {e}. "
                  "Type its address into Orbit instead.)")
            return
        while True:
            data, sender = s.recvfrom(256)
            if data.strip() == b"ORBIT-DESK?":
                reply = f"ORBIT-DESK {State.port} {State.name}".encode()
                s.sendto(reply, sender)


def read_keys(server):
    """p = pause/resume screen sharing, a = mute/unmute agents, q = quit (then Enter)."""
    for line in sys.stdin:
        key = line.strip().lower()
        if key == "p":
            State.paused = not State.paused
            print(f"[{now()}] Screen sharing {'PAUSED' if State.paused else 'resumed'}.")
        elif key == "a":
            State.agents_muted = not State.agents_muted
            print(f"[{now()}] Agents {'MUTED' if State.agents_muted else 'can reach Orbit again'}.")
        elif key == "q":
            print("Bye!")
            server.shutdown()
            return


def serve(argv):
    parser = argparse.ArgumentParser(description="Connect Orbit to this computer.")
    parser.add_argument("--monitor", type=int, default=1, help="1 = main screen, 0 = all screens")
    parser.add_argument("--new-code", action="store_true", help="make a new pairing code")
    parser.add_argument("--unpair", action="store_true", help="forget every paired phone")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    args = parser.parse_args(argv)

    if not have_crypto():
        print("Orbit Desk needs one more add-on for its encrypted connection to Orbit. Install it with\n\n"
              "    pip install cryptography\n\n"
              "(or  pip install -r requirements.txt  in this folder; on Windows, run_orbit_desk.bat does\n"
              "it for you), then start Orbit Desk again. The agent commands (notify, express, status,\n"
              "hook, mcp) work without it, but they need Orbit Desk running.")
        return 1
    if args.unpair:
        print(f"Forgot {forget_devices()} paired phone(s). Pair Orbit again with the code below.\n")
    State.code = load_code(args.new_code)
    State.devices = load_devices()
    State.monitor = args.monitor
    State.port = args.port

    try:
        import mss  # noqa: F401
        import PIL  # noqa: F401
        can_share = True
    except ImportError:
        can_share = False

    server = ThreadingHTTPServer(("0.0.0.0", State.port), Handler)
    server.daemon_threads = True
    threading.Thread(target=answer_discovery, daemon=True).start()
    threading.Thread(target=read_keys, args=(server,), daemon=True).start()

    print("Orbit Desk is running.\n")
    print(f"  This computer:  {State.name}   ({local_address()}:{State.port})")
    print(f"  Pairing code:   {State.code}")
    print(f"  Paired phones:  {len(State.devices)}\n")
    print("In Orbit: long-press the eyes > Device > Looking at your computer screen > Find my computer,\n"
          "then type the code and tap Pair (once). After that the code isn't sent again.")
    if can_share:
        print("Orbit only looks at your screen when you ask it to; each look is listed here.")
    else:
        print("(Screen sharing needs two add-ons:  pip install mss pillow . Agent news works without.)")
    print("Agents on this computer can reach Orbit: see  python orbit_desk.py --help .")
    print("Type  p  + Enter to pause/resume sharing,  a  + Enter to mute/unmute agents,  q  + Enter to quit.\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("Bye!")
    return 0


# ---- Commands for agents and scripts (they talk to the running Orbit Desk) ----

def call_desk(method: str, path: str, body=None, port: int = DEFAULT_PORT) -> dict:
    """One request to the Orbit Desk running on this computer. Never raises: problems come back
    as {"ok": False, "error": ...}, so a hook never breaks the agent that called it."""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data, method=method,
                                 headers={"X-Orbit-Agent": "1", "Content-Type": "application/json"})
    # Straight to this computer, never through a proxy
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=5) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            answer = json.loads(e.read() or b"{}")
        except ValueError:
            answer = {}
        return {"ok": False, "error": answer.get("error", f"Orbit Desk answered {e.code}")}
    except (urllib.error.URLError, OSError, ValueError):
        return {"ok": False, "error": "Orbit Desk isn't running on this computer "
                                      "(start it with  python orbit_desk.py )"}


def describe(result: dict) -> str:
    """A one-line summary of a notify/express result, for people and agents."""
    if not result.get("ok"):
        return "Not sent: " + result.get("error", "unknown problem")
    o = result.get("orbit", {})
    if not o.get("orbit_connected"):
        return "Queued. Orbit isn't connected right now; it gets the news when it reconnects (within an hour)."
    if o.get("orbit_asleep"):
        return "Sent. Orbit is asleep; it will tell them when it wakes and sees them."
    if o.get("person_at_desk"):
        return "Sent. They're at their desk, so Orbit is telling them now."
    return "Sent. They're away from the desk; Orbit will tell them when it sees them come back."


def cmd_notify(argv) -> int:
    p = argparse.ArgumentParser(prog="orbit_desk.py notify", description="Send news to Orbit.")
    p.add_argument("kind", choices=KINDS)
    p.add_argument("message", nargs="?", default="")
    p.add_argument("--source", default="", help="who it's from, e.g. Codex, Cursor, Antigravity")
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    a = p.parse_args(argv)
    result = call_desk("POST", "/agent/notify",
                       {"kind": a.kind, "message": a.message, "source": a.source}, a.port)
    print(describe(result), file=sys.stderr)
    return 0  # never fail the agent or script that called us


def cmd_express(argv) -> int:
    p = argparse.ArgumentParser(prog="orbit_desk.py express", description="Make Orbit react.")
    p.add_argument("move", choices=MOVES)
    p.add_argument("--source", default="")
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    a = p.parse_args(argv)
    print(describe(call_desk("POST", "/agent/express", {"move": a.move, "source": a.source}, a.port)),
          file=sys.stderr)
    return 0


def cmd_status(argv) -> int:
    p = argparse.ArgumentParser(prog="orbit_desk.py status")
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    a = p.parse_args(argv)
    print(json.dumps(call_desk("GET", "/agent/status", port=a.port), indent=2))
    return 0


def hook_event(payload) -> tuple:
    """Turns what an agent's hook passes in (JSON) into (kind, message), or None to stay quiet.
    Known shapes: Claude Code hooks ("hook_event_name"), Codex's notify ("type"). Anything else
    with an event name containing stop/finish/complete/done is news that it finished."""
    if not isinstance(payload, dict):
        return ("done", "")
    event = str(payload.get("hook_event_name") or payload.get("type") or payload.get("event")
                or payload.get("hookEventName") or "").lower()
    message = str(payload.get("message") or payload.get("last-assistant-message")
                  or payload.get("summary") or "")
    if event == "notification":  # Claude Code: it needs permission, or has waited for you
        return ("needs_input", message or "It needs you.")
    if event in ("stop", "agent-turn-complete", "subagentstop") or \
            any(w in event for w in ("stop", "finish", "complete", "done")):
        if payload.get("stop_hook_active"):  # Claude Code: it's continuing after a stop hook
            return None
        status = str(payload.get("status") or "").lower()
        if status in ("error", "failed", "failure"):
            return ("failed", message)
        return ("done", message if event == "agent-turn-complete" else "")
    if not event:
        return ("done", message)
    return None


def cmd_hook(argv) -> int:
    """For agents' hook settings. Reads the hook's JSON from stdin (or the last argument, as
    Codex's notify passes it), then tells Orbit. Always exits 0 and prints nothing to stdout, so
    it never changes what the agent does."""
    p = argparse.ArgumentParser(prog="orbit_desk.py hook")
    p.add_argument("--source", default="", help="who it's from, e.g. Claude Code, Codex, Cursor")
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    p.add_argument("json", nargs="?", default=None)
    a = p.parse_args(argv)
    raw = a.json
    if raw is None and not sys.stdin.isatty():
        raw = sys.stdin.read(100_000)
    try:
        payload = json.loads(raw) if raw and raw.strip() else {}
    except ValueError:
        payload = {}
    event = hook_event(payload)
    if event:
        kind, message = event
        result = call_desk("POST", "/agent/notify",
                           {"kind": kind, "message": message, "source": a.source}, a.port)
        print(describe(result), file=sys.stderr)
    return 0


# ---- MCP server (stdio): any MCP-capable agent can use Orbit ----

MCP_VERSIONS = ("2025-06-18", "2025-03-26", "2024-11-05")

MCP_TOOLS = [
    {
        "name": "orbit_notify",
        "description": (
            "Tell the person something through Orbit, their desk robot companion: it looks at them, "
            "reacts and says the message out loud in a few words. Use it when you finish a long task "
            "(kind 'done'), when you're blocked and need their decision or input ('needs_input'), "
            "when something failed that they should know about ('failed'), or for other short news "
            "('info'). If they're away from their desk, Orbit tells them when they come back. Keep "
            "the message short and spoken-friendly, under 15 words, no code or file paths. Don't "
            "use it for every small step: once per finished task or real blocker."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "kind": {"type": "string", "enum": list(KINDS)},
                "message": {"type": "string", "description": "Short and spoken-friendly, e.g. 'All 42 tests pass.'"},
                "source": {"type": "string", "description": "Your name as the person knows you, e.g. 'Codex' or 'Cursor'."},
            },
            "required": ["kind", "message"],
        },
    },
    {
        "name": "orbit_status",
        "description": (
            "Check whether the person is at their desk (Orbit sees them), whether Orbit is asleep, "
            "in a conversation or in focus mode, before deciding to wait or notify. Says nothing out loud."
        ),
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "orbit_express",
        "description": (
            "Make Orbit react without words, e.g. nod when they confirm, celebrate when a hard bug "
            "is fixed. Use sparingly."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "move": {"type": "string", "enum": list(MOVES)},
                "source": {"type": "string"},
            },
            "required": ["move"],
        },
    },
]


def mcp_call(name: str, args: dict, port: int, source: str) -> tuple:
    """Runs one MCP tool. Returns (text, is_error)."""
    if not isinstance(args, dict):
        args = {}
    if name == "orbit_notify":
        result = call_desk("POST", "/agent/notify", {
            "kind": args.get("kind", ""), "message": args.get("message", ""),
            "source": args.get("source") or source}, port)
        return describe(result), not result.get("ok")
    if name == "orbit_express":
        result = call_desk("POST", "/agent/express", {
            "move": args.get("move", ""), "source": args.get("source") or source}, port)
        return describe(result), not result.get("ok")
    if name == "orbit_status":
        result = call_desk("GET", "/agent/status", port=port)
        if result.get("ok") is False:
            return result.get("error", "unknown problem"), True
        return json.dumps(result), False
    return f"Unknown tool: {name}", True


def mcp_handle(msg: dict, port: int, source: str):
    """One JSON-RPC message in, the answer out (None for notifications)."""
    method = msg.get("method")
    rid = msg.get("id")
    if rid is None:  # a notification (e.g. notifications/initialized): no answer
        return None

    def ok(result):
        return {"jsonrpc": "2.0", "id": rid, "result": result}

    if method == "initialize":
        asked = (msg.get("params") or {}).get("protocolVersion")
        return ok({
            "protocolVersion": asked if asked in MCP_VERSIONS else MCP_VERSIONS[0],
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "orbit", "version": str(VERSION)},
            "instructions": (
                "Orbit is the person's desk robot companion. Use orbit_notify when you finish a "
                "long task or need their input, especially if they may have stepped away."
            ),
        })
    if method == "ping":
        return ok({})
    if method == "tools/list":
        return ok({"tools": MCP_TOOLS})
    if method == "tools/call":
        params = msg.get("params") or {}
        text, is_error = mcp_call(params.get("name", ""), params.get("arguments") or {}, port, source)
        return ok({"content": [{"type": "text", "text": text}], "isError": is_error})
    if method in ("resources/list", "prompts/list"):
        return ok({method.split("/")[0]: []})
    return {"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": f"Method not found: {method}"}}


def cmd_mcp(argv) -> int:
    """An MCP server on stdin/stdout (one JSON-RPC message per line). Agents start it themselves;
    it passes their calls to the running Orbit Desk."""
    p = argparse.ArgumentParser(prog="orbit_desk.py mcp")
    p.add_argument("--source", default="", help="name used when the agent doesn't give one")
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    a = p.parse_args(argv)
    out = sys.stdout
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except ValueError:
            answer = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}}
        else:
            batch = msg if isinstance(msg, list) else [msg]
            answers = [r for r in (mcp_handle(m, a.port, a.source) for m in batch if isinstance(m, dict)) if r]
            if not answers:
                continue
            answer = answers if isinstance(msg, list) else answers[0]
        out.write(json.dumps(answer) + "\n")
        out.flush()
    return 0


COMMANDS = {"notify": cmd_notify, "express": cmd_express, "status": cmd_status,
            "hook": cmd_hook, "mcp": cmd_mcp}


def main():
    if len(sys.argv) > 1 and sys.argv[1] in COMMANDS:
        sys.exit(COMMANDS[sys.argv[1]](sys.argv[2:]))
    sys.exit(serve(sys.argv[1:]))


if __name__ == "__main__":
    main()
