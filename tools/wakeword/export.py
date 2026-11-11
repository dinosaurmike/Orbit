# Prints which held-out names a checkpoint catches, then exports it to ONNX for the app.
# usage: export.py <step.pt> <orbit.onnx>
import sys, numpy as np, torch
from torch import nn
from collections import defaultdict
class Net(nn.Module):
    def __init__(self):
        super().__init__()
        self.layers = nn.Sequential(nn.Flatten(), nn.Dropout(0.1), nn.Linear(1536,128), nn.LayerNorm(128), nn.ReLU(),
            nn.Dropout(0.3), nn.Linear(128,128), nn.LayerNorm(128), nn.ReLU(), nn.Dropout(0.3), nn.Linear(128,1), nn.Sigmoid())
    def forward(self, x): return self.layers(x)
net=Net(); net.load_state_dict(torch.load(sys.argv[1])); net.eval()
ps=np.load('feats/posSteps.npy').astype(np.float32)
with torch.no_grad(): s=net(torch.from_numpy(ps))[:,0].numpy().reshape(-1,6)
texts=np.load('clips/posTest.npz',allow_pickle=True)['texts']
over=s>=0.5; caught=(over[:,1:]&over[:,:-1]).any(1)
d=defaultdict(list)
for t,c in zip(texts,caught): d[t.lower().strip('.,?! ').replace('hey, ','hey ')].append(c)
for k,v in sorted(d.items(), key=lambda kv: np.mean(kv[1])): print(f'{k:12s} {np.mean(v):.2f} ({len(v)})')
net.eval()
torch.onnx.export(net, torch.zeros(1,16,96), sys.argv[2], input_names=['input'], output_names=['score'], opset_version=17, dynamo=False)
print('exported', sys.argv[2])
