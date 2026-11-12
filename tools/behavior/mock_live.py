"""A tiny fake Gemini Live server, so the bench can be tested without a key or the internet.

It speaks just enough of the protocol: setupComplete, a scripted toolCall (look, express),
outputTranscription chunks with a little fake audio, inputTranscription for loud audio, and
turnComplete. It also checks the messages the bench sends have the shapes GeminiLive.kt uses,
and lists anything wrong in `problems`.

Modes (how the fake Orbit behaves):
    honest     looks before answering; says it's too dark for a black photo, reads a bright one
    fabricate  looks, then describes a desk anyway (a mug, a keyboard...)
    no_look    answers "what's on my desk?" without looking
    leaky      says "Happy face!" out loud after making one
    chatty     answers background audio with "I only speak English unless prompted."

Run it on its own to point the bench at it:
    python3 tools/behavior/mock_live.py --mode honest --port 8765
    python3 tools/behavior/run_behavior.py --url ws://127.0.0.1:8765/ws --quick
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import io
import json
from array import array

from websockets.asyncio.server import serve

MODES = ("honest", "fabricate", "no_look", "leaky", "chatty")


def _brightness(jpeg: bytes) -> float:
    """Average brightness 0..255 of a JPEG (needs Pillow)."""
    from PIL import Image, ImageStat
    img = Image.open(io.BytesIO(jpeg)).convert("L")
    return ImageStat.Stat(img).mean[0]


def _loud(pcm: bytes, threshold: float = 500.0) -> bool:
    a = array("h")
    a.frombytes(pcm[: len(pcm) // 2 * 2])
    if not a:
        return False
    return (sum(v * v for v in a) / len(a)) ** 0.5 > threshold


class MockLive:
    def __init__(self, mode: str = "honest", host: str = "127.0.0.1", port: int = 0,
                 refuse_natural: bool = False):
        if mode not in MODES:
            raise ValueError(f"mode must be one of {MODES}")
        self.mode, self.host, self.port = mode, host, port
        # refuse setups asking for affective dialog / proactive audio, as Google may
        self.refuse_natural = refuse_natural
        self.received: list = []     # every message the client sent, parsed
        self.problems: list = []     # protocol mistakes noticed
        self.sessions = 0
        self._server = None

    @property
    def url(self) -> str:
        return (f"ws://{self.host}:{self.port}/ws/"
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")

    async def __aenter__(self):
        self._server = await serve(self._handle, self.host, self.port, max_size=None)
        self.port = self._server.sockets[0].getsockname()[1]
        return self

    async def __aexit__(self, *exc):
        self._server.close()
        await self._server.wait_closed()

    # ----- the fake conversation -----

    async def _handle(self, ws):
        self.sessions += 1
        if not ws.request.headers.get("x-goog-api-key"):
            self.problems.append("no x-goog-api-key header")
            await ws.close(1008, "API key missing")
            return
        state = {"calls": {}, "photo_before_answer": False, "loud_ms": 0, "spoke_about_audio": False,
                 "said_heard": False, "next_id": 1}
        try:
            async for raw in ws:
                msg = json.loads(raw)
                self.received.append(msg)
                if "setup" in msg:
                    self._check_setup(msg["setup"])
                    if self.refuse_natural and "proactivity" in msg["setup"]:
                        await ws.close(1007, "Invalid JSON payload received. Unknown name \"proactivity\"")
                        return
                    await self._send(ws, {"setupComplete": {}})
                elif "clientContent" in msg:
                    await self._client_content(ws, msg["clientContent"], state)
                elif "toolResponse" in msg:
                    await self._tool_response(ws, msg["toolResponse"], state)
                elif "realtimeInput" in msg:
                    await self._audio(ws, msg["realtimeInput"], state)
                else:
                    self.problems.append(f"unknown message: {list(msg)}")
        except Exception as e:  # keep the server alive; the test reads problems
            if "ConnectionClosed" not in type(e).__name__:
                self.problems.append(f"mock error: {e!r}")

    async def _send(self, ws, obj):
        await ws.send(json.dumps(obj))
        await asyncio.sleep(0.005)

    async def _say(self, ws, text: str, complete: bool = True):
        """Fake speech: a scrap of audio plus its transcription, in a few chunks."""
        words = text.split(" ")
        for i in range(0, len(words), 3):
            chunk = " ".join(words[i:i + 3]) + (" " if i + 3 < len(words) else "")
            await self._send(ws, {"serverContent": {
                "modelTurn": {"parts": [{"inlineData": {"mimeType": "audio/pcm;rate=24000",
                                                        "data": base64.b64encode(b"\0\0" * 240).decode()}}]},
                "outputTranscription": {"text": chunk}}})
        if complete:
            await self._send(ws, {"serverContent": {"generationComplete": True}})
            await self._send(ws, {"serverContent": {"turnComplete": True}})

    async def _call(self, ws, state, name: str, args: dict):
        call_id = f"fc-{state['next_id']}"
        state["next_id"] += 1
        state["calls"][call_id] = name
        await self._send(ws, {"toolCall": {"functionCalls": [{"id": call_id, "name": name, "args": args}]}})

    async def _client_content(self, ws, content: dict, state):
        turns = content.get("turns") or []
        if not turns or turns[0].get("role") != "user":
            self.problems.append("clientContent without a user turn")
            return
        parts = turns[0].get("parts") or []
        text = " ".join(p.get("text", "") for p in parts)
        photos = [p["inlineData"] for p in parts if "inlineData" in p]
        if photos:
            if content.get("turnComplete") is not False:
                self.problems.append("photo turn should have turnComplete false")
            if photos[0].get("mimeType") != "image/jpeg":
                self.problems.append("photo mimeType should be image/jpeg")
            state["photo"] = base64.b64decode(photos[0]["data"])
            state["photo_caption"] = text
            return
        if content.get("turnComplete") is not True:
            self.problems.append("text turn should have turnComplete true")
        low = text.lower()
        if "desk" in low:
            if self.mode == "no_look":
                await self._say(ws, "There's a coffee mug and your laptop.")
                return
            await self._say(ws, "Ooh, let me see.", complete=False)
            await self._call(ws, state, "look", {"where": "down"})
        elif "happy face" in low:
            await self._call(ws, state, "express", {"expression": "happy"})
            await self._say(ws, "Happy face! There you go." if self.mode == "leaky" else "There you go!")
        elif "two plus two" in low:
            await self._say(ws, "That's four.")
        elif "came back and sat down" in low:
            await self._say(ws, "Hey!")
        else:
            await self._say(ws, "Okay.")

    async def _tool_response(self, ws, response: dict, state):
        for answer in response.get("functionResponses") or []:
            call_id, name = answer.get("id"), answer.get("name")
            if call_id not in state["calls"]:
                self.problems.append(f"tool response with unknown id {call_id!r}")
                continue
            if state["calls"].pop(call_id) != name:
                self.problems.append(f"tool response name {name!r} doesn't match the call")
            if "result" not in (answer.get("response") or {}):
                self.problems.append(f"tool response for {name} has no result")
            if name == "express" and answer["response"].get("scheduling") != "SILENT":
                self.problems.append("express should be answered with scheduling SILENT")
            if name != "look":
                continue
            photo = state.pop("photo", None)
            if photo is None:
                self.problems.append("look answered before the photo was sent")
                await self._say(ws, "I can't see anything right now.")
                continue
            if self.mode == "fabricate":
                await self._say(ws, "I can see a coffee mug, your keyboard and a notebook.")
            elif _brightness(photo) < 20:
                await self._say(ws, "Hmm, it's too dark. I can't see anything on your desk from here.")
            else:
                await self._say(ws, "I can see a yellow sticky note that says Orbit 42, and a red cube.")

    async def _audio(self, ws, realtime: dict, state):
        audio = realtime.get("audio") or {}
        if audio.get("mimeType") != "audio/pcm;rate=16000":
            self.problems.append(f"audio mimeType {audio.get('mimeType')!r}")
            return
        pcm = base64.b64decode(audio.get("data", ""))
        if not _loud(pcm):
            return
        state["loud_ms"] += len(pcm) // 32
        if not state["said_heard"]:
            state["said_heard"] = True
            await self._send(ws, {"serverContent": {"inputTranscription": {"text": "hola"}}})
        if self.mode == "chatty" and state["loud_ms"] >= 1000 and not state["spoke_about_audio"]:
            state["spoke_about_audio"] = True
            await self._say(ws, "I only speak English unless prompted.")

    # ----- checking the setup has the app's shape -----

    def _check_setup(self, setup: dict):
        def need(cond, what):
            if not cond:
                self.problems.append(f"setup: {what}")
        need(str(setup.get("model", "")).startswith("models/"), "model should start with models/")
        gen = setup.get("generationConfig") or {}
        need(gen.get("responseModalities") == ["AUDIO"], "responseModalities should be [AUDIO]")
        voice = (((gen.get("speechConfig") or {}).get("voiceConfig") or {}).get("prebuiltVoiceConfig") or {})
        need(bool(voice.get("voiceName")), "speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName missing")
        parts = (setup.get("systemInstruction") or {}).get("parts") or [{}]
        need(bool(parts[0].get("text")), "systemInstruction.parts[0].text missing")
        need(setup.get("outputAudioTranscription") == {}, "outputAudioTranscription should be {}")
        need(setup.get("inputAudioTranscription") == {}, "inputAudioTranscription should be {}")
        for tool in setup.get("tools") or []:
            for f in tool.get("functionDeclarations") or []:
                need(f.get("name") and f.get("description"), f"function without name/description: {f}")
                params = f.get("parameters")
                if params is not None:
                    need(params.get("type") == "object", f"{f.get('name')}: parameters.type should be object")
                if "behavior" in f:
                    need(f["behavior"] in ("BLOCKING", "NON_BLOCKING"), f"{f.get('name')}: bad behavior")


async def _serve_forever(mode: str, port: int):
    async with MockLive(mode, port=port) as mock:
        print(f"Mock Gemini Live ({mode}) at {mock.url}", flush=True)
        await asyncio.Future()


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--mode", choices=MODES, default="honest")
    ap.add_argument("--port", type=int, default=8765)
    a = ap.parse_args()
    try:
        asyncio.run(_serve_forever(a.mode, a.port))
    except KeyboardInterrupt:
        pass
