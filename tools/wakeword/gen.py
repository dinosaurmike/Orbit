# Makes synthetic speech clips with Piper's LibriTTS-R voice (904 speakers), ONNX only.
# usage: gen.py <texts.txt> <out.npz> <count> <seed> [first_speaker last_speaker]
import random, sys
import numpy as np
from scipy.signal import resample_poly

import os
HERE = os.path.dirname(os.path.abspath(__file__))
texts = [t.strip() for t in open(sys.argv[1]) if t.strip()]
out, count, seed = sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
lo, hi = (int(sys.argv[5]), int(sys.argv[6])) if len(sys.argv) > 6 else (0, 800)

voice = None


def init():
    global voice
    import onnxruntime
    from piper import PiperVoice
    opts = onnxruntime.SessionOptions()
    opts.intra_op_num_threads = 1
    voice = PiperVoice.load(HERE + "/voices/libritts_r.onnx")
    voice.session = onnxruntime.InferenceSession(HERE + "/voices/libritts_r.onnx", opts,
                                                 providers=["CPUExecutionProvider"])


def make(job):
    from piper import SynthesisConfig
    i, text = job
    r = random.Random(seed * 1_000_003 + i)
    cfg = SynthesisConfig(speaker_id=r.randrange(lo, hi),
                          length_scale=r.choice([0.8, 0.9, 1.0, 1.0, 1.1, 1.25, 1.4]),
                          noise_scale=r.uniform(0.4, 0.9), noise_w_scale=r.uniform(0.5, 1.0))
    a = np.concatenate([c.audio_float_array for c in voice.synthesize(text, cfg)])
    a = np.trim_zeros(np.where(np.abs(a) < 2e-3, 0, a))
    if len(a) < 2000:
        return None
    a = resample_poly(a, 320, 441)  # 22050 -> 16000
    a = a / (np.abs(a).max() + 1e-6) * 0.9
    return text, (a * 32767).astype(np.int16)


if __name__ == "__main__":
    init()
    r = random.Random(seed)
    jobs = [(i, r.choice(texts)) for i in range(int(count * 1.05))]
    res = [x for x in map(make, jobs) if x is not None][:count]
    np.savez(out, clips=np.array([c for _, c in res], dtype=object), texts=np.array([t for t, _ in res]))
    print(out, len(res))
