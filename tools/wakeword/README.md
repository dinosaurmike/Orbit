# Orbit's wake word model

How `app/src/main/assets/wake/orbit.onnx` (the "fast ear" for *Orbit*, *Orby* and *Or-bee*) was
made. It uses the [openWakeWord](https://github.com/dscripka/openWakeWord) method: a frozen,
general-purpose speech embedding model, plus a tiny classifier trained only on synthetic speech.

```mermaid
flowchart LR
    A[Piper TTS<br/>800 voices] -->|"Orbit", "Hey Orby"…| P[positive clips]
    A -->|"orange", "Robbie", "a bit"…| N[sound-alike clips]
    P & N --> X[room echo + background noise]
    X --> F[openWakeWord features<br/>16 × 96 per 1.28 s]
    H[openWakeWord's public<br/>negative features] --> T
    F --> T[train a small network]
    T --> O[orbit.onnx]
```

On the phone (`OrbitEar.kt`), every 80 ms:
sound → `melspectrogram.onnx` → `embedding_model.onnx` → `orbit.onnx` → a score from 0 to 1.
Above 0.5 it wakes; above 0.15 the music drops and the eyes perk up.

## Steps

Needs Python 3, about 25 GB of disk and a few hours of CPU (no GPU needed).

```sh
python -m venv venv
venv/bin/pip install --index-url https://download.pytorch.org/whl/cpu torch
venv/bin/pip install --no-deps openwakeword==0.6.0 piper-tts==1.3.0
venv/bin/pip install onnxruntime onnx scipy soundfile tqdm requests pyarrow
```

Downloads (into `data/`, `voices/`, `oww/`):

| What | From |
|---|---|
| Piper voice `en_US-libritts_r-medium.onnx` + `.json` | huggingface.co/rhasspy/piper-voices |
| `melspectrogram.onnx`, `embedding_model.onnx` | openWakeWord GitHub release v0.5.1 |
| `validation_set_features.npy` and the start of `openwakeword_features_ACAV100M_2000_hrs_16bit.npy` (first ~1M windows) | huggingface.co/datasets/davidscripka/openwakeword_features |
| Room impulse responses (`data/rir/*.wav`) | huggingface.co/datasets/davidscripka/MIT_environmental_impulse_responses |
| Background audio: one AudioSet shard, decoded to `data/noise16k.npy` | huggingface.co/datasets/agkphysics/AudioSet |

Then:

```sh
# Clips: training voices 0-799, test voices 800-903 (never heard in training)
venv/bin/python gen.py pos.txt clips/posA.npz 5000 1 0 800
venv/bin/python gen.py pos.txt clips/posB.npz 5000 2 0 800
venv/bin/python gen.py neg.txt clips/negA.npz 6000 4 0 904
venv/bin/python gen.py neg.txt clips/negB.npz 6000 5 0 904
venv/bin/python gen.py pos.txt clips/posTest.npz 1200 3 800 904
venv/bin/python gen.py neg.txt clips/negTest.npz 1200 6 800 904
# Features (3 noisy copies of each name, 2 of each sound-alike)
venv/bin/python feat.py clips/posA.npz feats/posA.npy 3 end 11
venv/bin/python feat.py clips/posB.npz feats/posB.npy 3 end 12
venv/bin/python feat.py clips/negA.npz feats/negA.npy 2 end 13
venv/bin/python feat.py clips/negB.npz feats/negB.npy 2 any 14
venv/bin/python feat.py clips/negTest.npz feats/negTest.npy 1 end 16
venv/bin/python feat.py clips/silence.npz feats/noise.npy 1 any 9      # 6000 empty clips: noise only
# Held-out names as the phone hears them: 6 consecutive 80 ms steps after each name
venv/bin/python feat.py clips/posTest.npz feats/posSteps.npy 1 steps 17
# Train (15,000 steps, about 25 minutes on 4 CPU cores), then export the last checkpoint
venv/bin/python train.py out 15000 10 4096 5e-4
venv/bin/python export.py out/step15000.pt out/orbit.onnx
# Check the phone's streaming code gives the same scores as openWakeWord's own
venv/bin/python stream_test.py out/orbit.onnx
```

`train.py` prints, every 1,000 steps and for each threshold: how many held-out names it catches
(two 80 ms steps in a row over the threshold, as the app requires), how often sound-alikes fool
it, and false wakes per hour. openWakeWord's validation recordings (11 hours of real
conversation, music and noise) are split in two: the first half is extra "not Orbit" training
data, the second half is only ever used to count false wakes.

## Results (the model in the app)

| | |
|---|---|
| Names caught, 100 voices never heard in training (noisy, echoey) | 79% ("Hey Orbit" 84%, "Hi Orbit" 92%, "Or-bee" 60%) |
| Names caught in a 3-minute continuous stream | 29 of 30, about 40 ms after the name ends |
| False wakes, 5.3 hours of held-out conversation and music | 1 (0.19 per hour) |
| Sound-alikes that fool it ("orange", "Robbie", "a bit"…) | 0.6% |
| Phone code (`OrbitEar.kt`) vs openWakeWord's reference | same scores (difference under 0.000001) |

What didn't work, for next time: training only on openWakeWord's general negatives looked fine
early, then false wakes climbed from 4 to 20 per hour as training went on (it learned shortcuts
that don't hold on real conversation). Adding real conversation recordings as negatives, plus
dropout, fixed it.

## Credits

openWakeWord by David Scripka (Apache 2.0 code). The speech embedding model is Google's
(Apache 2.0). Piper by Michael Hansen / Rhasspy. LibriTTS-R voice data (CC BY 4.0).
The negative features come from openWakeWord's public research dataset.
