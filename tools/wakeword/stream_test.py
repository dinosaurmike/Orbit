# Checks that OrbitEar.kt's streaming steps (mirrored here line for line) give the same scores as
# openWakeWord's own reference streaming code, and that a held-out "Orbit" wakes it in a stream.
# usage: stream_test.py <orbit.onnx>
import os, sys
import numpy as np
import onnxruntime as ort

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = sys.argv[1]
CHUNK, MEL_CONTEXT, MEL_BANDS, MEL_WINDOW, EMB, EMB_SIZE = 1280, 480, 32, 76, 16, 96


class Ear:  # mirrors OrbitEar.kt
    def __init__(self):
        o = ort.SessionOptions(); o.intra_op_num_threads = 1
        self.mel_m = ort.InferenceSession(HERE + "/oww/melspectrogram.onnx", o)
        self.emb_m = ort.InferenceSession(HERE + "/oww/embedding_model.onnx", o)
        self.wake_m = ort.InferenceSession(MODEL, o)
        self.reset()

    def reset(self):
        self.sound = np.zeros(CHUNK + MEL_CONTEXT, np.int16); self.filled = MEL_CONTEXT
        self.mel, self.emb = [], []

    def run(self, s, x, shape):
        return s.run(None, {s.get_inputs()[0].name: x.reshape(shape).astype(np.float32)})[0].ravel()

    def accept(self, pcm):
        score, i = None, 0
        while i < len(pcm):
            n = min(len(pcm) - i, len(self.sound) - self.filled)
            self.sound[self.filled:self.filled + n] = pcm[i:i + n]; self.filled += n; i += n
            if self.filled == len(self.sound):
                s = self.step()
                score = s if s is not None else score
                self.sound[:MEL_CONTEXT] = self.sound[CHUNK:]; self.filled = MEL_CONTEXT
        return score

    def step(self):
        frames = self.run(self.mel_m, self.sound.astype(np.float32), (1, -1))
        for f in range(len(frames) // MEL_BANDS):
            self.mel.append(frames[f * MEL_BANDS:(f + 1) * MEL_BANDS] / 10 + 2)
        self.mel = self.mel[-MEL_WINDOW:]
        if len(self.mel) < MEL_WINDOW: return None
        self.emb.append(self.run(self.emb_m, np.concatenate(self.mel), (1, MEL_WINDOW, MEL_BANDS, 1)))
        self.emb = self.emb[-EMB:]
        if len(self.emb) < EMB: return None
        return float(self.run(self.wake_m, np.concatenate(self.emb), (1, EMB, EMB_SIZE))[0])


# A stream: 3 s of background, then held-out "Orbit"s separated by background and other speech
rng = np.random.default_rng(7)
noise = np.load(HERE + "/data/noise16k.npy", mmap_mode="r")
pos = np.load(HERE + "/clips/posTest.npz", allow_pickle=True)["clips"]
neg = np.load(HERE + "/clips/negTest.npz", allow_pickle=True)["clips"]
parts, marks = [], []
def bg(sec):
    i = rng.integers(0, len(noise) - int(sec * 16000)); return np.array(noise[i:i + int(sec * 16000)]) * 0.05
parts.append(bg(3.0)); t = 3.0 * 16000
for k in range(60):
    c = (pos if k % 2 == 0 else neg)[rng.integers(0, 1000)].astype(np.float32) / 32768 * 0.6
    if k % 2 == 0: marks.append((t, t + len(c)))
    parts += [c + bg(len(c) / 16000), bg(2.0)]; t += len(c) + 32000
audio = (np.clip(np.concatenate(parts), -1, 1) * 32767).astype(np.int16)
audio.astype('<i2').tofile(HERE + '/stream.raw')

# Mine. Orbit's microphone gives 50 ms at a time: 5 x 50 ms = 4 steps of 80 ms, so feed it 400 ms
# pieces in 50 ms bits and time-stamp each step at the 80 ms boundary where it really happens.
ear = Ear(); mine = []; total = 0
for i in range(0, len(audio) - 800, 800):
    before = total // 1280
    s = ear.accept(audio[i:i + 800]); total += 800
    if s is not None and total // 1280 > before: mine.append(((total // 1280) * 1280, s))

# openWakeWord's reference streaming code, fed 80 ms at a time
from openwakeword.model import Model
ref_model = Model(wakeword_models=[MODEL], inference_framework="onnx",
                  melspec_model_path=HERE + "/oww/melspectrogram.onnx",
                  embedding_model_path=HERE + "/oww/embedding_model.onnx")
key = list(ref_model.models.keys())[0]
ref = []
for i in range(0, len(audio) - 1280, 1280):
    ref.append((i + 1280, ref_model.predict(audio[i:i + 1280])[key]))

m = dict(mine); r = dict(ref)
common = [k for k in r if k in m and k > 4 * 16000]
diff = np.abs(np.array([m[k] for k in common]) - np.array([r[k] for k in common]))
print(f"compared {len(common)} steps: max difference {diff.max():.4f}, mean {diff.mean():.5f}")

# Did each "Orbit" wake it (score >= 0.5 within 0.5 s of the word ending)? And how quickly?
hits, delays = 0, []
for a, b in marks:
    after = [(t, s) for t, s in mine if a <= t <= b + 8000 and s >= 0.5]
    if after: hits += 1; delays.append((after[0][0] - b) / 16)
false = sum(1 for t, s in mine if s >= 0.5 and not any(a <= t <= b + 8000 for a, b in marks))
print(f"woke for {hits}/{len(marks)} names; median delay after the name ends {np.median(delays):.0f} ms; "
      f"steps scoring >= 0.5 outside a name: {false}")

np.save(HERE + "/stream_mine.npy", np.array(mine))
kt = HERE + "/stream_kotlin.txt"
if os.path.exists(kt):
    k = {int(a): float(b) for a, b in (l.split() for l in open(kt) if l.strip())}
    both = [t for t in m if t in k]
    d = np.abs(np.array([m[t] for t in both]) - np.array([k[t] for t in both]))
    print(f"Kotlin OrbitEar vs this: {len(both)} steps, max difference {d.max():.6f}")
