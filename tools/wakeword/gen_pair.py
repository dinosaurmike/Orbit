# Name + request in one breath, same voice: clips of the name and the request, joined with a short gap.
# usage: gen_pair.py <names.txt> <requests.txt> <out.npz> <count> <seed> <first_speaker> <last_speaker>
import os, random, sys
import numpy as np
from scipy.signal import resample_poly
HERE = os.path.dirname(os.path.abspath(__file__))
names = [t.strip() for t in open(sys.argv[1]) if t.strip()]
reqs = [t.strip() for t in open(sys.argv[2]) if t.strip()]
out, count, seed, lo, hi = sys.argv[3], int(sys.argv[4]), int(sys.argv[5]), int(sys.argv[6]), int(sys.argv[7])
from piper import PiperVoice, SynthesisConfig
voice = PiperVoice.load(HERE + "/voices/libritts_r.onnx")
r = random.Random(seed)
def say(text, spk, ls, ns, nw):
    a = np.concatenate([c.audio_float_array for c in voice.synthesize(text, SynthesisConfig(speaker_id=spk, length_scale=ls, noise_scale=ns, noise_w_scale=nw))])
    a = np.trim_zeros(np.where(np.abs(a) < 2e-3, 0, a))
    return resample_poly(a, 320, 441)
names_out, reqs_out = [], []
while len(names_out) < count:
    spk = r.randrange(lo, hi); ls = r.choice([0.8, 0.9, 1.0, 1.1, 1.25]); ns = r.uniform(0.4, 0.9); nw = r.uniform(0.5, 1.0)
    n = say(r.choice(names).rstrip('.?!,') + ",", spk, ls, ns, nw); q = say(r.choice(reqs), spk, ls, ns, nw)
    if len(n) < 2000 or len(q) < 2000: continue
    peak = max(np.abs(n).max(), np.abs(q).max()) + 1e-6
    names_out.append((n / peak * 0.9 * 32767).astype(np.int16)); reqs_out.append((q / peak * 0.9 * 32767).astype(np.int16))
np.savez(out, names=np.array(names_out, dtype=object), reqs=np.array(reqs_out, dtype=object))
print(out, len(names_out))
