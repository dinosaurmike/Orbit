# Trains Orbit's wake word model on openWakeWord features and exports it to ONNX.
# usage: train.py <out_dir> [steps] [max_neg_weight]
import os, sys, time
import numpy as np
import torch
from torch import nn

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
STEPS = int(sys.argv[2]) if len(sys.argv) > 2 else 20000
MAXW = float(sys.argv[3]) if len(sys.argv) > 3 else 10.0
NNEG = int(sys.argv[4]) if len(sys.argv) > 4 else 4096    # general-audio negatives per step
LR = float(sys.argv[5]) if len(sys.argv) > 5 else 5e-4
FA_MAX = 0.5  # false wakes per hour allowed for the saved model (two-in-a-row, as in the app)
torch.manual_seed(0); rng = np.random.default_rng(0)
torch.set_num_threads(4)
F = HERE + "/feats/"


def load(*names):
    return np.concatenate([np.load(F + n).astype(np.float32) for n in names])


pos = load("posA.npy", "posB.npy")
adv = load("negA.npy", "negB.npy")
noise = load("noise.npy")
pos_test = load("posTest.npy")
adv_test = load("negTest.npy")
# Held-out names as the phone hears them: 6 consecutive 80 ms steps each
pos_steps = load("posSteps.npy")
acav = np.array(np.memmap(HERE + "/data/acav_part.npy", dtype=np.float16, mode="r", offset=128).reshape(-1, 16, 96))
val = np.load(HERE + "/data/validation_set_features.npy")  # 10.7 hours, one embedding per 80 ms
all_windows = np.lib.stride_tricks.sliding_window_view(val, (16, 96))[:, 0]
# openWakeWord's validation recordings (real conversations, music, noise): the first half becomes
# extra "not Orbit" training data, the second half is kept apart to measure false wakes honestly
half = len(all_windows) // 2
real_neg = all_windows[:half - 16]
val_windows = all_windows[half:]
print("pos", pos.shape, "adv", adv.shape, "noise", noise.shape, "acav", acav.shape, "val", val_windows.shape, flush=True)


class Net(nn.Module):
    def __init__(self):
        super().__init__()
        self.layers = nn.Sequential(
            nn.Flatten(), nn.Dropout(0.1), nn.Linear(16 * 96, 128), nn.LayerNorm(128), nn.ReLU(),
            nn.Dropout(0.3), nn.Linear(128, 128), nn.LayerNorm(128), nn.ReLU(), nn.Dropout(0.3),
            nn.Linear(128, 1), nn.Sigmoid())

    def forward(self, x):
        return self.layers(x)


net = Net()
opt = torch.optim.AdamW(net.parameters(), lr=1e-3, weight_decay=1e-3)
sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=LR, total_steps=STEPS, pct_start=0.1)


def scores(x, bs=8192):
    net.eval(); out = []
    with torch.no_grad():
        for i in range(0, len(x), bs):
            out.append(net(torch.from_numpy(np.ascontiguousarray(x[i:i + bs], dtype=np.float32)))[:, 0].numpy())
    net.train()
    return np.concatenate(out)


def false_wakes_per_hour(s, thr):
    # Like the app: it wakes when two 80 ms steps in a row score over the threshold, then ignores
    # itself for 1.5 s (about 19 steps)
    over = s >= thr
    two = np.zeros_like(over); two[1:] = over[1:] & over[:-1]
    n, last = 0, -100
    for i in np.flatnonzero(two):
        if i - last > 19:
            n += 1; last = i
    return n / (len(s) * 0.08 / 3600)


def evaluate(tag):
    sv, ss, sa = scores(val_windows), scores(pos_steps).reshape(-1, 6), scores(adv_test)
    rows = []
    for thr in (0.3, 0.5, 0.7, 0.9):
        over = ss >= thr
        caught = (over[:, 1:] & over[:, :-1]).any(axis=1).mean()  # two steps in a row, like the app
        rows.append((thr, caught, (sa >= thr).mean(), false_wakes_per_hour(sv, thr)))
    print(tag, " ".join(f"[t{t}: caught {r:.3f} soundalike {a:.3f} fa/h {f:.2f}]" for t, r, a, f in rows), flush=True)
    return rows


bce = nn.BCELoss(reduction="none")
best = None
t0 = time.time()
for step in range(1, STEPS + 1):
    ip = rng.integers(0, len(pos), 256)
    ia = rng.integers(0, len(adv), 256)
    inn = rng.integers(0, len(noise), 64)
    ir = rng.integers(0, len(real_neg), 512)
    ic = rng.integers(0, len(acav), NNEG)
    x = np.concatenate([pos[ip], adv[ia], noise[inn], real_neg[ir], acav[ic].astype(np.float32)])
    y = np.concatenate([np.ones(256), np.zeros(256 + 64 + 512 + NNEG)]).astype(np.float32)
    x = torch.from_numpy(x); y = torch.from_numpy(y)
    p = net(x)[:, 0]
    # Negatives count for more and more as training goes on (false wakes are the worst failure)
    w = 1 + (MAXW - 1) * min(1.0, step / (STEPS * 0.3))
    # Average over the names, plus (weighted) average over everything else, so neither side can
    # win just by being more numerous. Easy negatives (already scored near 0) are left out.
    l = bce(p, y)
    isneg = (y == 0) & (p > 0.02)
    loss = l[y > 0].mean() + w * (l[isneg].sum() / max(1, int(isneg.sum())))
    opt.zero_grad(); loss.backward(); opt.step(); sched.step()
    if step % 1000 == 0 or step == STEPS:
        print(f"step {step} loss {loss.item():.4f} weight {w:.0f} {time.time() - t0:.0f}s", flush=True)
        rows = evaluate(f"  eval@{step}")
        r50 = rows[1]
        torch.save(net.state_dict(), f"{OUT}/step{step}.pt")
        # Keep the best model that wakes falsely less than once every 2 hours at the 0.5 threshold
        if step >= STEPS * 0.3 and r50[3] <= FA_MAX and (best is None or r50[1] > best[0]):
            best = (r50[1], step)
            torch.save(net.state_dict(), OUT + "/best.pt")
            print("  saved", best, flush=True)

torch.save(net.state_dict(), OUT + "/last.pt")
if best is not None:
    net.load_state_dict(torch.load(OUT + "/best.pt"))
net.eval()
torch.onnx.export(net, torch.zeros(1, 16, 96), OUT + "/orbit.onnx", input_names=["input"],
                  output_names=["score"], opset_version=17, dynamo=False)
print("exported", best, flush=True)
evaluate("final")
