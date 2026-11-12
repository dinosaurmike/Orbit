"""A small Gemini Live client for the behaviour bench.

It speaks the same JSON as GeminiLive.kt: the setup message, text turns (say()), photo turns
(showPhoto()), tool responses (answerTools()/answerLook()) and microphone audio (sendAudio()).
Replies are audio only (the native-audio model can't answer in text), so the bench reads what
Orbit said from outputAudioTranscription, the same text the app uses for captions.

Everything that arrives is kept as a list of events, so a scenario can look at exactly what
happened and when.
"""

from __future__ import annotations

import asyncio
import base64
import json
import time
from dataclasses import dataclass, field
from typing import Awaitable, Callable, Optional

try:
    from websockets.asyncio.client import connect as ws_connect
    from websockets.exceptions import ConnectionClosed
except ImportError as e:  # pragma: no cover
    raise SystemExit("The behaviour bench needs the websockets package (13 or newer): "
                     "pip install -r tools/behavior/requirements.txt") from e

from orbit_prompt import KEY_HEADER, LIVE_URL

SAMPLE_RATE = 16000          # what Orbit's microphone sends (audio/pcm;rate=16000)
CHUNK_MS = 50                # the app sends 50 ms of sound at a time
CHUNK_BYTES = SAMPLE_RATE * 2 * CHUNK_MS // 1000


class LiveError(RuntimeError):
    pass


@dataclass
class Event:
    kind: str        # setup_complete | output_text | input_text | tool_call | turn_complete |
                     # interrupted | audio | go_away | generation_complete | closed | other
    t: float         # seconds since the connection opened
    data: object = None


@dataclass
class Exchange:
    """What came back in one stretch of the conversation."""
    output_text: str = ""
    input_text: str = ""
    tool_calls: list = field(default_factory=list)   # [{"id", "name", "args"}]
    turn_completes: int = 0
    audio_bytes: int = 0
    timed_out: bool = False


ToolHandler = Callable[["LiveSession", dict], Awaitable[None]]


class LiveSession:
    """One conversation. Use as `async with LiveSession(key, setup) as s:`."""

    def __init__(self, key: str, setup: dict, url: str = LIVE_URL, setup_timeout: float = 20.0):
        self.key, self.setup, self.url, self.setup_timeout = key, setup, url, setup_timeout
        self.events: list = []
        self.sent: list = []          # (t, short description) of everything sent, for reports
        self._queue: asyncio.Queue = asyncio.Queue()
        self._ws = None
        self._reader = None
        self._t0 = 0.0
        self.close_reason: Optional[str] = None

    # ----- connection -----

    async def __aenter__(self):
        await self.open()
        return self

    async def __aexit__(self, *exc):
        await self.close()

    def _now(self) -> float:
        return time.monotonic() - self._t0

    async def open(self):
        self._t0 = time.monotonic()
        kwargs = dict(additional_headers={KEY_HEADER: self.key}, max_size=None, open_timeout=20)
        try:
            self._ws = await ws_connect(self.url, **kwargs)
        except Exception as e:
            raise LiveError(f"Couldn't connect to {self.url.split('?')[0]}: {e}") from e
        self._reader = asyncio.create_task(self._read())
        await self._send(self.setup, "setup")
        ev = await self.wait_for("setup_complete", timeout=self.setup_timeout)
        if ev is None:
            if self.close_reason:
                raise LiveError(f"Google closed the connection before setup finished: {self.close_reason}")
            raise LiveError(f"No setupComplete within {self.setup_timeout:.0f} s")

    async def close(self):
        if self._ws is not None:
            try:
                await self._ws.close(1000, "bye")
            except Exception:
                pass
        if self._reader is not None:
            self._reader.cancel()
            try:
                await self._reader
            except (asyncio.CancelledError, Exception):
                pass

    async def _read(self):
        try:
            async for raw in self._ws:
                text = raw.decode("utf-8") if isinstance(raw, (bytes, bytearray)) else raw
                for ev in self._parse(json.loads(text)):
                    self.events.append(ev)
                    await self._queue.put(ev)
        except ConnectionClosed as e:
            rcv = getattr(e, "rcvd", None)
            self.close_reason = f"{rcv.code} {rcv.reason}".strip() if rcv else str(e)
        except Exception as e:  # malformed message etc.
            self.close_reason = f"reader error: {e}"
        ev = Event("closed", self._now(), self.close_reason or "closed")
        self.events.append(ev)
        await self._queue.put(ev)

    def _parse(self, message: dict) -> list:
        """Turns one server message into events, reading the same fields as GeminiLive.handle()."""
        now, out = self._now(), []
        if "setupComplete" in message:
            out.append(Event("setup_complete", now))
        if "goAway" in message:
            out.append(Event("go_away", now, message["goAway"]))
        tool_call = message.get("toolCall")
        if tool_call:
            calls = [{"id": c.get("id"), "name": c.get("name"), "args": c.get("args") or {}}
                     for c in tool_call.get("functionCalls") or []]
            out.append(Event("tool_call", now, calls))
        content = message.get("serverContent")
        if content:
            if content.get("interrupted"):
                out.append(Event("interrupted", now))
            words = (content.get("inputTranscription") or {}).get("text")
            if words:
                out.append(Event("input_text", now, words))
            words = (content.get("outputTranscription") or {}).get("text")
            if words:
                out.append(Event("output_text", now, words))
            for part in (content.get("modelTurn") or {}).get("parts") or []:
                data = (part.get("inlineData") or {}).get("data")
                if data:
                    out.append(Event("audio", now, len(base64.b64decode(data))))
            if content.get("generationComplete"):
                out.append(Event("generation_complete", now))
            if content.get("turnComplete"):
                out.append(Event("turn_complete", now))
        if not out:
            out.append(Event("other", now, list(message.keys())))
        return out

    # ----- sending (same shapes as GeminiLive.kt) -----

    async def _send(self, obj: dict, note: str):
        if self._ws is None:
            raise LiveError("not connected")
        self.sent.append((round(self._now(), 2), note))
        try:
            await self._ws.send(json.dumps(obj))
        except ConnectionClosed as e:
            raise LiveError(f"The connection closed while sending {note}: {self.close_reason or e}") from e

    async def say(self, text: str):
        """A typed message as if spoken (GeminiLive.say(): used for the arrival greeting)."""
        turn = {"role": "user", "parts": [{"text": text}]}
        await self._send({"clientContent": {"turns": [turn], "turnComplete": True}}, f"text: {text[:60]}")

    async def show_photo(self, jpeg: bytes, caption: str):
        """A photo turn (GeminiLive.showPhoto()): turnComplete false, sent before the tool answer.
        `caption` is sent as is (the app wraps it in brackets)."""
        turn = {"role": "user", "parts": [
            {"text": caption},
            {"inlineData": {"mimeType": "image/jpeg", "data": base64.b64encode(jpeg).decode("ascii")}},
        ]}
        await self._send({"clientContent": {"turns": [turn], "turnComplete": False}},
                         f"photo ({len(jpeg)} bytes): {caption[:50]}")

    async def tool_response(self, call: dict, response: dict):
        """One functionResponse (answerTools()/answerLook()), with the call's id when it had one."""
        answer = {"name": call["name"], "response": response}
        if call.get("id"):
            answer["id"] = call["id"]
        await self._send({"toolResponse": {"functionResponses": [answer]}},
                         f"tool response {call['name']}: {json.dumps(response)[:60]}")

    async def silent_ok(self, call: dict):
        """How the app answers express and the other quick tools: result ok, SILENT."""
        await self.tool_response(call, {"result": "ok", "scheduling": "SILENT"})

    async def send_audio(self, pcm: bytes, pace: float = 1.0, note: str = "audio"):
        """Streams 16 kHz mono 16-bit PCM in 50 ms chunks (GeminiLive.sendAudio()). pace=1 sends
        in real time like a microphone; 0 sends as fast as possible (for the mock)."""
        self.sent.append((round(self._now(), 2), f"{note}: {len(pcm) / (SAMPLE_RATE * 2):.1f} s"))
        start = time.monotonic()
        for n, i in enumerate(range(0, len(pcm), CHUNK_BYTES)):
            chunk = pcm[i:i + CHUNK_BYTES]
            msg = {"realtimeInput": {"audio": {"mimeType": f"audio/pcm;rate={SAMPLE_RATE}",
                                               "data": base64.b64encode(chunk).decode("ascii")}}}
            try:
                await self._ws.send(json.dumps(msg))
            except ConnectionClosed as e:
                raise LiveError(f"The connection closed while streaming audio: {self.close_reason or e}") from e
            if pace > 0:
                due = start + (n + 1) * CHUNK_MS / 1000 / pace
                delay = due - time.monotonic()
                if delay > 0:
                    await asyncio.sleep(delay)
            else:
                await asyncio.sleep(0)

    # ----- receiving -----

    async def next_event(self, timeout: float) -> Optional[Event]:
        try:
            return await asyncio.wait_for(self._queue.get(), timeout=max(0.0, timeout))
        except asyncio.TimeoutError:
            return None

    async def wait_for(self, kind: str, timeout: float) -> Optional[Event]:
        deadline = time.monotonic() + timeout
        while True:
            ev = await self.next_event(deadline - time.monotonic())
            if ev is None or ev.kind == kind:
                return ev
            if ev.kind == "closed":
                return None

    async def run(self, on_tool: Optional[ToolHandler] = None, timeout: float = 40.0,
                  idle: float = 3.0, until_turn_complete: bool = True,
                  background: Optional[Awaitable] = None,
                  blocking_tools: frozenset = frozenset({"look"})) -> Exchange:
        """Collects what arrives until the reply is over: a turnComplete, no tool call still being
        answered, then `idle` seconds of nothing. With until_turn_complete=False it just listens
        for `timeout` seconds (for checking that Orbit stays quiet). Tool calls are handed to
        `on_tool` (default: answered "ok, SILENT" like the app's quick tools). `background` (e.g.
        audio being streamed) runs alongside and is awaited before returning. A call to one of
        `blocking_tools` means Gemini is waiting for an answer, so a reply (and another
        turnComplete) is still to come; NON_BLOCKING ones like express don't."""
        on_tool = on_tool or (lambda s, call: s.silent_ok(call))
        ex = Exchange()
        deadline = time.monotonic() + timeout
        bg = asyncio.ensure_future(background) if background is not None else None
        pending: set = set()
        done = False
        new_part = False
        try:
            while True:
                now = time.monotonic()
                if now >= deadline:
                    ex.timed_out = until_turn_complete and not done
                    break
                wait = deadline - now
                if until_turn_complete and done and not pending and (bg is None or bg.done()):
                    wait = min(wait, idle)
                ev = await self.next_event(wait)
                for task in [t for t in pending if t.done()]:
                    pending.discard(task)
                    if task.exception():
                        raise task.exception()
                if ev is None:
                    if until_turn_complete and done and not pending and (bg is None or bg.done()):
                        break
                    continue
                if ev.kind == "output_text":
                    if new_part and ex.output_text and not ex.output_text[-1].isspace():
                        ex.output_text += " "  # a new reply (e.g. after a look): keep words apart
                    new_part = False
                    ex.output_text += ev.data
                elif ev.kind == "input_text":
                    ex.input_text += ev.data
                elif ev.kind == "audio":
                    ex.audio_bytes += ev.data
                elif ev.kind == "turn_complete":
                    ex.turn_completes += 1
                    done = True
                    new_part = True
                elif ev.kind == "tool_call":
                    new_part = True
                    for call in ev.data:
                        if call["name"] in blocking_tools:
                            done = False  # Gemini waits for the answer, then carries on
                        ex.tool_calls.append(call)
                        pending.add(asyncio.ensure_future(on_tool(self, call)))
                elif ev.kind == "closed":
                    # mid-conversation: an error, not "Orbit stayed quiet"
                    raise LiveError(f"Google closed the conversation: {ev.data}")
            for task in pending:
                await task
            if bg is not None:
                await bg
        finally:
            if bg is not None and not bg.done():
                bg.cancel()
        return ex
