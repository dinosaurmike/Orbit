"""Photos and sounds for the behaviour bench, all made on the spot (nothing to download).

Photos need Pillow. Sounds are 16 kHz mono 16-bit PCM, the format Orbit's microphone sends.
Spanish background speech uses espeak-ng when it's installed (`sudo apt install espeak-ng`);
without it, a made-up babble is used instead and the report says so.
"""

from __future__ import annotations

import io
import math
import random
import shutil
import struct
import subprocess
import tempfile
import wave
from array import array
from pathlib import Path

SAMPLE_RATE = 16000


# ----- photos -----

def _pillow():
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError as e:  # pragma: no cover
        raise SystemExit("The behaviour bench needs Pillow: pip install -r tools/behavior/requirements.txt") from e
    return Image, ImageDraw, ImageFont


def _font(ImageFont, size: int):
    for path in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                 "/Library/Fonts/Arial Bold.ttf", "C:/Windows/Fonts/arialbd.ttf"):
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    try:
        return ImageFont.load_default(size=size)   # Pillow 10.1+
    except TypeError:  # pragma: no cover
        return ImageFont.load_default()


def _jpeg(img) -> bytes:
    out = io.BytesIO()
    img.save(out, "JPEG", quality=85)
    return out.getvalue()


def black_jpeg(width: int = 1280, height: int = 960) -> bytes:
    """A completely black photo: what the camera gives in a dark room or with the lens covered."""
    Image, _, _ = _pillow()
    return _jpeg(Image.new("RGB", (width, height), (0, 0, 0)))


def desk_jpeg(width: int = 1280, height: int = 960) -> bytes:
    """A simple, clearly drawn desk: a yellow sticky note reading "ORBIT 42" and a red cube on
    a plain wooden surface. Nothing else."""
    Image, ImageDraw, ImageFont = _pillow()
    img = Image.new("RGB", (width, height), (176, 128, 84))
    d = ImageDraw.Draw(img)
    for y in range(0, height, 48):  # faint wood grain
        d.line([(0, y + 17), (width, y + 9)], fill=(165, 118, 76), width=3)
    # sticky note, slightly turned, with a soft shadow
    note = Image.new("RGBA", (420, 420), (0, 0, 0, 0))
    nd = ImageDraw.Draw(note)
    nd.rectangle([0, 0, 419, 419], fill=(255, 226, 74, 255))
    font = _font(ImageFont, 92)
    for line, y in (("ORBIT", 110), ("42", 230)):
        w = nd.textlength(line, font=font)
        nd.text(((420 - w) / 2, y), line, fill=(20, 20, 30, 255), font=font)
    note = note.rotate(-6, expand=True, resample=Image.BICUBIC)
    shadow = Image.new("RGBA", note.size, (0, 0, 0, 0))
    shadow.paste((60, 40, 25, 110), mask=note.split()[3])
    img.paste(shadow, (150, 230), shadow)
    img.paste(note, (135, 215), note)
    # red cube: three faces, isometric
    cx, cy, s = 920, 560, 150
    top = [(cx, cy - s), (cx + s, cy - s // 2), (cx, cy), (cx - s, cy - s // 2)]
    left = [(cx - s, cy - s // 2), (cx, cy), (cx, cy + s), (cx - s, cy + s // 2)]
    right = [(cx, cy), (cx + s, cy - s // 2), (cx + s, cy + s // 2), (cx, cy + s)]
    d.polygon([(x + 18, y + 18) for x, y in left + right], fill=(120, 84, 52))
    d.polygon(top, fill=(235, 70, 60))
    d.polygon(left, fill=(200, 30, 30))
    d.polygon(right, fill=(150, 15, 20))
    return _jpeg(img)


# ----- sounds -----

def _to_bytes(samples) -> bytes:
    a = array("h", (max(-32767, min(32767, int(round(v)))) for v in samples))
    if a.itemsize != 2:  # pragma: no cover
        raise RuntimeError("unexpected sample size")
    import sys
    if sys.byteorder == "big":  # pragma: no cover
        a.byteswap()
    return a.tobytes()


def silence(seconds: float, level: float = 30.0, seed: int = 1) -> bytes:
    """Room tone: very quiet noise, like a real microphone in a quiet room (not digital zero)."""
    rng = random.Random(seed)
    return _to_bytes(rng.gauss(0, level) for _ in range(int(seconds * SAMPLE_RATE)))


def babble(seconds: float = 2.0, seed: int = 7, peak: float = 6000.0) -> bytes:
    """Mumbling: a few voice-like hums with a syllable rhythm, muffled, with no real words."""
    rng = random.Random(seed)
    n = int(seconds * SAMPLE_RATE)
    out = [0.0] * n
    for voice in range(3):
        f0 = rng.uniform(100, 210)
        phase = 0.0
        # syllables: random lengths with short gaps
        envelope, t = [0.0] * n, 0
        while t < n:
            length = int(rng.uniform(0.12, 0.3) * SAMPLE_RATE)
            gap = int(rng.uniform(0.02, 0.15) * SAMPLE_RATE)
            for k in range(min(length, n - t)):
                envelope[t + k] = math.sin(math.pi * k / length) ** 2
            t += length + gap
        harmonics = [rng.uniform(0.2, 1.0) / (h ** 0.8) for h in range(1, 7)]
        for i in range(n):
            f = f0 * (1 + 0.08 * math.sin(2 * math.pi * 0.7 * i / SAMPLE_RATE + voice))
            phase += 2 * math.pi * f / SAMPLE_RATE
            if envelope[i]:
                s = sum(a * math.sin((h + 1) * phase) for h, a in enumerate(harmonics))
                out[i] += envelope[i] * s
    # muffle (a short moving average) and add a little noise
    smooth, acc, width = [0.0] * n, 0.0, 6
    for i in range(n):
        acc += out[i] - (out[i - width] if i >= width else 0.0)
        smooth[i] = acc / width + rng.gauss(0, 0.02)
    top = max(abs(v) for v in smooth) or 1.0
    return _to_bytes(v / top * peak for v in smooth)


def read_wav_16k(path: Path) -> bytes:
    """Any 16-bit WAV, as 16 kHz mono 16-bit PCM (simple linear resampling)."""
    with wave.open(str(path), "rb") as w:
        rate, channels, width = w.getframerate(), w.getnchannels(), w.getsampwidth()
        frames = w.readframes(w.getnframes())
    if width != 2:
        raise ValueError(f"{path}: expected 16-bit audio, got {8 * width}-bit")
    samples = struct.unpack(f"<{len(frames) // 2}h", frames)
    if channels > 1:
        samples = [sum(samples[i:i + channels]) / channels for i in range(0, len(samples), channels)]
    if rate == SAMPLE_RATE:
        return _to_bytes(samples)
    n_out = int(len(samples) * SAMPLE_RATE / rate)
    out = []
    for j in range(n_out):
        x = j * rate / SAMPLE_RATE
        i = int(x)
        a = samples[i]
        b = samples[i + 1] if i + 1 < len(samples) else a
        out.append(a + (b - a) * (x - i))
    return _to_bytes(out)


def espeak_available() -> bool:
    return shutil.which("espeak-ng") is not None


def espeak(text: str, voice: str = "es", speed: int = 160) -> bytes:
    """Speech from espeak-ng, as 16 kHz mono PCM. Raises if espeak-ng isn't installed."""
    exe = shutil.which("espeak-ng")
    if not exe:
        raise FileNotFoundError("espeak-ng isn't installed")
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "out.wav"
        subprocess.run([exe, "-v", voice, "-s", str(speed), "-w", str(out), text],
                       check=True, capture_output=True, timeout=60)
        return read_wav_16k(out)


def scale(pcm: bytes, gain: float) -> bytes:
    a = array("h")
    a.frombytes(pcm)
    return _to_bytes(v * gain for v in a)


def fit(pcm: bytes, seconds: float) -> bytes:
    """Trims or pads (with room tone) to exactly this long."""
    want = int(seconds * SAMPLE_RATE) * 2
    if len(pcm) >= want:
        return pcm[:want]
    return pcm + silence((want - len(pcm)) / 2 / SAMPLE_RATE)


SPANISH_BACKGROUND = ("Oye, ¿has visto mis llaves? Creo que las dejé en la cocina, "
                      "al lado de la ventana.")


def background_spanish(seconds: float = 4.0) -> tuple:
    """Someone in the room talking Spanish, not to Orbit: (pcm, how it was made)."""
    if espeak_available():
        speech = espeak(SPANISH_BACKGROUND, "es", speed=220)
        # quieter than someone talking straight at the phone: they're across the room
        return fit(scale(speech, 0.45), seconds), "espeak-ng Spanish"
    return babble(seconds, seed=11, peak=5000.0), "synthetic babble (espeak-ng not installed)"


def duration(pcm: bytes) -> float:
    return len(pcm) / 2 / SAMPLE_RATE
