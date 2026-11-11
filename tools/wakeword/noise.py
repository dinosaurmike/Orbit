# Decodes one AudioSet shard (data/audioset00.parquet) into data/noise16k.npy: background sound at 16 kHz
import pyarrow.parquet as pq, io, numpy as np, soundfile as sf
from math import gcd
from scipy.signal import resample_poly
pf=pq.ParquetFile('data/audioset00.parquet'); print(pf.schema_arrow.names)
out=[]; n=0
for b in pf.iter_batches(batch_size=64, columns=['audio']):
    for a in b.column('audio').to_pylist():
        try:
            x,sr=sf.read(io.BytesIO(a['bytes']),dtype='float32')
        except Exception as e:
            continue
        if x.ndim>1: x=x.mean(1)
        if sr!=16000:
            g=gcd(16000,sr); x=resample_poly(x,16000//g,sr//g).astype(np.float32)
        m=np.abs(x).max()
        if m<1e-3: continue
        out.append((x/m*0.7).astype(np.float32)); n+=1
print(n, sum(len(x) for x in out)/16000/3600, 'hours')
np.save('data/noise16k.npy', np.concatenate(out))
