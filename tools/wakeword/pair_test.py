# Does the fast ear catch the name when a request follows straight on ("Hey Orby, what time is it")?
# usage: pair_test.py <orbit.onnx> <pairs.npz>
import os, sys
import numpy as np
sys.argv, MODEL, PAIRS = sys.argv[:2], sys.argv[1], sys.argv[2]
HERE = os.path.dirname(os.path.abspath(__file__))
src = open(HERE + "/stream_test.py").read()
exec(src[:src.index("# A stream:")])  # the Ear class (mirrors OrbitEar.kt), nothing else
d = np.load(PAIRS, allow_pickle=True)
noise = np.load(HERE + "/data/noise16k.npy", mmap_mode="r")
rng = np.random.default_rng(3)

def run(follow: bool):
    caught, delays = 0, []
    for n, q in zip(d["names"], d["reqs"]):
        i = rng.integers(0, len(noise) - 200000)
        lead = np.zeros(24000); gap = np.zeros(int(rng.uniform(0, 0.15) * 16000))
        tail = q.astype(np.float32) / 32768 if follow else np.zeros(len(q))
        x = np.concatenate([lead, n.astype(np.float32) / 32768, gap, tail, np.zeros(16000)])
        x = x * 0.6 + np.array(noise[i:i + len(x)]) * 0.04
        pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
        end = len(lead) + len(n)  # where the name ends
        ear = Ear(); prev = 0.0; total = 0; fired = None
        for k in range(0, len(pcm) - 800, 800):
            before = total // 1280
            s = ear.accept(pcm[k:k + 800]); total += 800
            if s is not None and total // 1280 > before:
                t = (total // 1280) * 1280
                if s >= 0.5 and prev >= 0.5 and fired is None and t >= len(lead):
                    fired = t
                prev = s
        if fired is not None and fired <= end + 9600:  # within 0.6 s of the name ending
            caught += 1; delays.append((fired - end) / 16)
    return caught, len(d["names"]), np.median(delays) if delays else float("nan")

for follow in (False, True):
    c, n, med = run(follow)
    print(f"{'name + request' if follow else 'name, then quiet':18s} caught {c}/{n} ({100*c/n:.0f}%), median {med:.0f} ms after the name ends")
