#!/usr/bin/env python3
"""Orbit Desk: lets Orbit, the desk companion, look at this computer's screen when you ask it to.

Ask Orbit "what do you think of this?" or "can you help with this error?" and it takes one
picture of your screen through this program, over your home network. It never looks on its own.

    pip install mss pillow
    python orbit_desk.py

Options:
    --monitor N    which screen to share: 1 = main screen (default), 2 = second..., 0 = all of them
    --new-code     make a new pairing code (Orbit will need the new one)
    --port N       use a different port (default 47800)

While it runs: type  p  then Enter to pause or resume sharing,  q  then Enter to quit.
MIT licensed, part of the Orbit project.
"""
import argparse
import io
import json
import secrets
import socket
import sys
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

VERSION = 1
DISCOVERY_PORT = 47801  # Orbit asks "ORBIT-DESK?" here to find computers on the network
CONFIG = Path.home() / ".orbit_desk.json"
MAX_SIDE = 1600         # longest side of the picture sent, in pixels (enough to read text)
JPEG_QUALITY = 70
MIN_SECONDS_BETWEEN = 2  # at most one picture every 2 seconds


class State:
    code = ""
    name = socket.gethostname()
    monitor = 1
    paused = False
    port = 47800
    last_picture = 0.0
    wrong_codes = []     # times of recent wrong codes
    locked_until = 0.0   # too many wrong codes: refuse everything for a minute
    lock = threading.Lock()


def load_code(new: bool) -> str:
    """The pairing code, kept in a small file in your home folder so it stays the same."""
    data = {}
    try:
        data = json.loads(CONFIG.read_text())
    except (OSError, ValueError):
        pass
    if new or not str(data.get("code", "")).isdigit():
        data["code"] = f"{secrets.randbelow(10**6):06d}"
        CONFIG.write_text(json.dumps(data))
    return data["code"]


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


class Handler(BaseHTTPRequestHandler):
    server_version = "OrbitDesk/1"

    def log_message(self, *args):  # keep the window quiet; we print our own lines
        pass

    def send(self, status: int, body: bytes, kind: str = "application/json"):
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, status: int, data: dict):
        self.send(status, json.dumps(data).encode())

    def code_ok(self) -> bool:
        """Checks the pairing code (answering if it's wrong), and slows down anyone guessing."""
        with State.lock:
            moment = time.time()
            if moment < State.locked_until:
                self.send_json(429, {"error": "too many wrong codes; try again in a minute"})
                return False
            given = self.headers.get("X-Orbit-Code", "").strip()
            if secrets.compare_digest(given.encode(), State.code.encode()):
                return True
            State.wrong_codes = [t for t in State.wrong_codes if moment - t < 60] + [moment]
            if len(State.wrong_codes) >= 5:
                State.locked_until = moment + 60
                print(f"[{now()}] Several wrong pairing codes from {self.client_address[0]}: "
                      "refusing everyone for a minute.")
        self.send_json(401, {"error": "wrong pairing code"})
        return False

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/hello":  # anyone may ask what this is (no code needed, nothing private)
            self.send_json(200, {"app": "orbit-desk", "name": State.name, "version": VERSION})
        elif path == "/check":
            if self.code_ok():
                self.send_json(200, {"ok": True, "name": State.name, "paused": State.paused})
        elif path == "/screen":
            if not self.code_ok():
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
            self.send(200, picture, "image/jpeg")
        else:
            self.send_json(404, {"error": "not found"})


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
    """p = pause/resume, q = quit (typed in this window, then Enter)."""
    for line in sys.stdin:
        key = line.strip().lower()
        if key == "p":
            State.paused = not State.paused
            print(f"[{now()}] Sharing {'PAUSED' if State.paused else 'resumed'}.")
        elif key == "q":
            print("Bye!")
            server.shutdown()
            return


def main():
    parser = argparse.ArgumentParser(description="Let Orbit look at this screen when you ask.")
    parser.add_argument("--monitor", type=int, default=1, help="1 = main screen, 0 = all screens")
    parser.add_argument("--new-code", action="store_true", help="make a new pairing code")
    parser.add_argument("--port", type=int, default=47800)
    args = parser.parse_args()

    State.code = load_code(args.new_code)
    State.monitor = args.monitor
    State.port = args.port

    try:
        import mss  # noqa: F401
        import PIL  # noqa: F401
    except ImportError:
        print("Orbit Desk needs two small add-ons. Install them with:\n\n"
              "    pip install mss pillow\n\nthen run this again.")
        sys.exit(1)

    server = ThreadingHTTPServer(("0.0.0.0", State.port), Handler)
    threading.Thread(target=answer_discovery, daemon=True).start()
    threading.Thread(target=read_keys, args=(server,), daemon=True).start()

    print("Orbit Desk is running.\n")
    print(f"  This computer:  {State.name}   ({local_address()}:{State.port})")
    print(f"  Pairing code:   {State.code}\n")
    print("In Orbit: long-press the eyes > Computer > Find my computer, then enter the code.")
    print("Orbit only looks when you ask it to; each look is listed here.")
    print("Type  p  + Enter to pause/resume sharing,  q  + Enter to quit.\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("Bye!")


if __name__ == "__main__":
    main()
