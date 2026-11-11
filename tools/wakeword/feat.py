# Turns clean clips into openWakeWord features (N, 16, 96), with room echo and background noise
# usage: feat.py <clips.npz> <out.npy> <copies> <placement: end|any> [seed]
import glob, io, random, sys
import numpy as np
import soundfile as sf
from scipy.signal import fftconvolve, resample_poly

import os
HERE = os.path.dirname(os.path.abspath(__file__))
src, out, copies, placement = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
seed = int(sys.argv[5]) if len(sys.argv) > 5 else 0
rng = np.random.default_rng(seed); random.seed(seed)
WIN = 32000  # 2 s at 16 kHz -> exactly 16 embeddings

clips = np.load(src, allow_pickle=True)["clips"]
rirs = []
for f in sorted(glob.glob(HERE + "/data/rir/*.wav")):
    r, sr = sf.read(f, dtype="float32")
    if r.ndim > 1: r = r[:, 0]
    if sr != 16000: r = resample_poly(r, 16000, sr)
    rirs.append(r / (np.abs(r).max() + 1e-9))
noise = np.load(HERE + "/data/noise16k.npy", mmap_mode="r")  # long float32 stream of background audio


def background(n):
    i = rng.integers(0, len(noise) - n)
    return np.array(noise[i:i + n], dtype=np.float32)


def augment(x, shift=None):
    x = x.astype(np.float32) / 32768
    if rng.random() < 0.5:
        x = fftconvolve(x, random.choice(rirs))[: len(x) + 4000]
        x = x / (np.abs(x).max() + 1e-9) * 0.9
    x = x[:WIN]
    x = x * rng.uniform(0.1, 1.0)
    canvas = np.zeros(WIN, np.float32)
    if shift is not None:
        end = WIN - shift
    elif placement == "end":
        # The word finishes 0-250 ms before the end of the window, the way it does when the phone
        # checks the last 1.3 s of sound just after you say it
        end = WIN - int(rng.uniform(0, 0.25) * 16000)
    else:
        end = int(rng.uniform(min(len(x), WIN), WIN + 1))
    start = max(0, end - len(x))
    canvas[start:end] = x[len(x) - (end - start):]
    if rng.random() < (1.0 if np.abs(canvas).max() == 0 else 0.85):
        b = background(WIN)
        snr = rng.uniform(-2, 20)
        ps = np.mean(canvas ** 2) + 1e-9
        pb = np.mean(b ** 2) + 1e-9
        if ps < 1e-6:  # no speech at all: just the background, at any loudness
            canvas = b * rng.uniform(0.02, 1.0)
        else:
            canvas = canvas + b * np.sqrt(ps / pb / (10 ** (snr / 10)))
    else:
        canvas = canvas + rng.normal(0, rng.uniform(1e-4, 3e-3), WIN).astype(np.float32)
    canvas = np.clip(canvas, -1, 1)
    return (canvas * 32767).astype(np.int16)


from openwakeword.utils import AudioFeatures  # noqa: E402

af = AudioFeatures(inference_framework="onnx",
                   melspec_model_path=HERE + "/oww/melspectrogram.onnx",
                   embedding_model_path=HERE + "/oww/embedding_model.onnx", ncpu=4)
if placement == "steps":
    # The same noisy clip, as the phone hears it on 6 consecutive 80 ms steps after the name ends
    audio = []
    for c in clips:
        state = rng.bit_generator.state; rstate = random.getstate()
        for k in range(6):
            rng.bit_generator.state = state; random.setstate(rstate)
            audio.append(augment(c, shift=k * 1280))
    audio = np.stack(audio)
else:
    audio = np.stack([augment(c) for _ in range(copies) for c in clips])
feats = af.embed_clips(audio, batch_size=256)
print(feats.shape, flush=True)
np.save(out, feats.astype(np.float16))
