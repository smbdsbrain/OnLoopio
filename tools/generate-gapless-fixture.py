"""Generate original PCM continuity fixtures. No private music; outputs stay outside Git."""
import argparse,math,wave,struct,json,hashlib,random,collections
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('output',type=Path);p.add_argument('--signal',choices=['chirp','noise','music'],default='noise');a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
rate=48000
samples=[4096*math.sin(2*math.pi*(500*n/rate+750*(n/rate)**2)) for n in range(rate*2)]
if a.signal=='noise':
    source=random.Random(17017);first=collections.deque([0.0]*6,maxlen=6);second=collections.deque([0.0]*6,maxlen=6);slow=0;samples=[]
    for n in range(rate*2):
        first.append(source.uniform(-1,1));second.append(sum(first)/6);smooth=sum(second)/6;slow+=.025*(smooth-slow);samples.append(smooth-slow)
    scale=8192/max(abs(v) for v in samples);samples=[v*scale for v in samples]
if a.signal=='music':
    source=random.Random(17017);samples=[]
    for n in range(rate*2):
        t=n/rate;note=int(t*4);phase=t-note/4;frequency=[220,261.625565,329.627557,293.664768][note%4]
        pluck=sum(math.sin(2*math.pi*frequency*k*phase)/k for k in range(1,5))*math.exp(-phase*12)
        percussion=source.uniform(-1,1)*math.exp(-phase*70)
        samples.append(pluck+.3*percussion)
    scale=8192/max(abs(v) for v in samples);samples=[v*scale for v in samples]
samples=[round(v) for v in samples]
for name,data in [('reference',samples),('first',samples[:rate]),('second',samples[rate:])]:
    path=a.output/(name+'.wav')
    with wave.open(str(path),'wb') as f:f.setnchannels(1);f.setsampwidth(2);f.setframerate(rate);f.writeframes(struct.pack('<'+'h'*len(data),*data))
manifest={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in a.output.glob('*.wav')}
(a.output/'manifest.json').write_text(json.dumps({'rate':rate,'signal':a.signal,'boundaries_required':50,'hashes':manifest},indent=2)+'\n',encoding='utf-8')
print('Generated 48 kHz reference + consecutive halves; external capture required for qualification.')
