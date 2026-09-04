import json, math, zlib, binascii, time, collections, pathlib, statistics, functools
ROOT=pathlib.Path(__file__).resolve().parents[2]
# Supply the external JSON corpus explicitly; it is not checked into the repository.
ALPHABET='!"#$%&\'()*+-/0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_abcdefghijklmnopqrstuvwxyz{|}~'
COMMON=[(4,0,4),(4,3,4),(3,2,3)]
def zz(v): return 2*v if v>=0 else -2*v-1
def unzz(v): return v//2 if v%2==0 else -(v//2)-1
def uv(v):
 o=bytearray()
 while v>=128: o.append((v&127)|128);v>>=7
 o.append(v);return bytes(o)
def prefix(c): return bytes([42])+uv(len(c))+b''.join(uv(zz(v)) for v in c[0]) if c else bytes([42,0])
def ascii91(s):
 o=[];buf=0;n=0;base=len(ALPHABET);threshold=base*base-8192-1
 for c in s:
  buf|=c<<n;n+=8
  if n>13:
   v=buf&8191
   if v>threshold:buf>>=13;n-=13
   else:v=buf&16383;buf>>=14;n-=14
   o.extend([ALPHABET[v%base],ALPHABET[v//base]])
 if n:
  o.append(ALPHABET[buf%base])
  if n>7 or buf>=base:o.append(ALPHABET[buf//base])
 raw=''.join(o)
 return ''.join(c+('~' if i+1<len(raw) and ((c=='<' and raw[i+1] in '3~') or (c=='o' and raw[i+1] in '/~')) else '') for i,c in enumerate(raw))
def frame(sem, strategy=None):
 mode=int(strategy is not None);head=sem[0]|mode*128
 if mode:
  comp=zlib.compressobj(9,zlib.DEFLATED,-15,8,strategy);content=comp.compress(sem[1:])+comp.flush()
 else: content=sem[1:]
 crc=binascii.crc_hqx(bytes([head])+sem[1:],65535)
 pay=bytes([head])+content+crc.to_bytes(2,'big')
 return ('WP:'+ascii91(pay),mode,pay)
def compare(f):return len(f[0]),f[1],len(f[2]),f[2]
class Bits:
 def __init__(self):self.v=0;self.n=0
 def bit(self,v):self.v|=v<<self.n;self.n+=1
 def put(self,v,n):self.v|=v<<self.n;self.n+=n
 def rice(self,v,k):self.n+=v>>k;self.bit(1);self.put(v&((1<<k)-1),k)
 def gamma(self,v):
  w=v.bit_length();self.n+=w-1
  for i in range(w-1,-1,-1):self.bit((v>>i)&1)
 def msb(self,v,n):
  for i in range(n-1,-1,-1):self.bit((v>>i)&1)
 def bytes(self):return self.v.to_bytes((self.n+7)//8,'little')
def axes(c,mode=0):
 out=[]
 for a in range(3):
  ar=[]
  for i in range(1,len(c)):
   pred=c[i-1][a]
   if mode==1 and i>1:pred=2*c[i-1][a]-c[i-2][a]
   if mode==2 and i>1:pred=c[i-2][a]
   if mode==3 and i>2:pred=c[i-1][a]+c[i-2][a]-c[i-3][a]
   ar.append(zz(c[i][a]-pred))
  out.append(ar)
 return out
def choosek(v,overhead=False):
 if not any(v):return -1
 return min(range(31),key=lambda k:len(v)*(k+1)+sum(x>>k for x in v)+(3 if k<=6 else 8) if overhead else len(v)*(k+1)+sum(x>>k for x in v))
def param(b,k):
 if k==-1:b.put(7,3);b.put(31,5)
 elif k<=6:b.put(k,3)
 else:b.put(7,3);b.put(k,5)
def payloadcost(v,k):return 0 if k==-1 else len(v)*(k+1)+sum(x>>k for x in v)
def desc(b,ks):
 if tuple(ks) in COMMON:b.put(COMMON.index(tuple(ks)),2)
 else:
  b.put(3,2)
  for k in ks:param(b,k)
def rice(c):
 if len(c)<=1:return prefix(c)
 ax=axes(c);ks=[choosek(v) for v in ax];b=Bits();desc(b,ks)
 for v,k in zip(ax,ks):
  if k!=-1:
   for x in v:b.rice(x,k)
 return prefix(c)+b.bytes()
@functools.lru_cache(maxsize=20000)
def width(n,k):return (math.comb(n,k)-1).bit_length()
def chooseq(v):
 if not any(v):return -1
 def cost(k):
  q=[x>>k for x in v];m=min(sum(bool(x) for x in q),sum(not x for x in q))
  return 1+2*((m+1).bit_length()-1)+1+width(len(v),m)+sum(q)+k*len(v)
 return min(range(29),key=cost)
def qaxis(b,v,k):
 q=[x>>k for x in v];maj=sum(bool(x) for x in q)>len(v)/2
 minority=[i for i,x in enumerate(q) if bool(x)!=maj]
 b.bit(int(maj));b.gamma(len(minority)+1)
 rank=sum(math.comb(p,i+1) for i,p in enumerate(minority))
 b.msb(rank,width(len(v),len(minority)))
 for x in v:b.put(x&((1<<k)-1),k)
 for x in q:
  if x:b.n+=x-1;b.bit(1)
def quotient(c):
 if len(c)<=1:return prefix(c)
 ax=axes(c);ks=[chooseq(v) for v in ax];b=Bits();b.put(3,2);b.put(7,3);b.put(1,5)
 for k in ks:param(b,k)
 for v,k in zip(ax,ks):
  if k!=-1:qaxis(b,v,k)
 return prefix(c)+b.bytes()
def delta(c):return prefix(c)+b''.join(uv(zz(c[i][a]-c[i-1][a])) for i in range(1,len(c)) for a in range(3))
def baseline(c):
 candidates=[frame(rice(c))]
 if 1<len(c)<=1024:candidates.append(frame(quotient(c)))
 for s in [zlib.Z_DEFAULT_STRATEGY,zlib.Z_FILTERED]:candidates.append(frame(delta(c),s))
 return min(candidates,key=compare)
# Proposed versioned entropy descriptor: old direct count+first; 10-bit escape ext=2;
# each axis gets a 2-bit predictor followed by existing Rice parameter. Optional
# constant is encoded as the ordinary k=-1. The old envelope and CRC are retained.
def predictor(c):
 if len(c)<=1:return rice(c),[0,0,0]
 allaxes=[axes(c,m) for m in range(4)];modes=[];ks=[]
 for a in range(3):
  choices=[]
  for m in range(4):
   v=allaxes[m][a];k=choosek(v,True);cost=payloadcost(v,k)+(3 if 0<=k<=6 else 8)
   choices.append((cost,m,k))
  _,m,k=min(choices);modes.append(m);ks.append(k)
 b=Bits();b.put(3,2);b.put(7,3);b.put(2,5)
 for m,k in zip(modes,ks):b.put(m,2);param(b,k)
 for a,(m,k) in enumerate(zip(modes,ks)):
  if k!=-1:
   for x in allaxes[m][a]:b.rice(x,k)
 return prefix(c)+b.bytes(),modes
# Per-block first-order Rice with block length 16/32/64, 10-bit descriptor plus
# 2-bit block-size token. Each block/axis supplies the current Rice parameter.
def blocks(c,size):
 if len(c)<=1:return rice(c)
 ax=axes(c);b=Bits();b.put(3,2);b.put(7,3);b.put(3,5);b.put([16,32,64].index(size),2)
 for start in range(0,len(c)-1,size):
  vv=[v[start:start+size] for v in ax];ks=[choosek(v,True) for v in vv]
  for k in ks:param(b,k)
  for v,k in zip(vv,ks):
   if k!=-1:
    for x in v:b.rice(x,k)
 return prefix(c)+b.bytes()
# New escaped descriptor ext=4. Route-wide X/Z lifting predictor selected from
# [identity, z-x, z+x, x-z, x+z]. Coords are never changed, only residual basis.
def diagonal(c):
 if len(c)<=1:return rice(c),0
 av=axes(c);best=None
 for mode in range(1,5):
  ax=[v.copy() for v in av]
  dst,src=(2,0) if mode<=2 else (0,2);sign=-1 if mode%2 else 1
  ax[dst]=[zz((x//2 if not x%2 else -(x//2)-1)+sign*(y//2 if not y%2 else -(y//2)-1)) for x,y in zip(av[dst],av[src])]
  ks=[choosek(v,True) for v in ax];b=Bits();b.put(3,2);b.put(7,3);b.put(4,5);b.put(mode-1,2)
  for k in ks:param(b,k)
  for v,k in zip(ax,ks):
   if k!=-1:
    for x in v:b.rice(x,k)
  sem=prefix(c)+b.bytes();f=frame(sem)
  if best is None or compare(f)<compare(best[0]):best=f,sem,mode
 return best[1],best[2]

def validate_goldens():
 data=json.load(open(ROOT/'src/test/resources/fixtures/waypointer-v10-next-no-golomb-goldens.json'))
 for row in data['vectors']:
  semantic=bytes.fromhex(row['semanticHex']);pay=bytes.fromhex(row['modePayloadHex'])
  raw=('WP:'+ascii91(pay))
  field=next(k for k in row if ('wire' in k.lower() or 'code' in k.lower()) and isinstance(row[k],str) and row[k].startswith('WP:'))
  assert raw==row[field],(field,raw[:20],row[field][:20])
  assert (binascii.crc_hqx(bytes([pay[0]])+semantic[1:],65535))==int.from_bytes(pay[-2:],'big')
 print('Validated ASCII/envelope against',len(data['vectors']),'goldens')
def summarize(label,rows):
 base=sum(r['base'] for r in rows);print(label,len(rows),'base',base)
 for mode in ['pred','block16','block32','block64','diag','portfolio']:
  vals=[min(r['base'],r[mode]) for r in rows]
  gains=[r['base']-v for r,v in zip(rows,vals)]
  print(mode,'total',sum(vals),'save',sum(gains),'pct',round(sum(gains)/base*100,3),'wins',sum(g>0 for g in gains),'max',max(gains))
def main():
 import argparse
 parser=argparse.ArgumentParser(description='Size-only experimental coordinate bodies; no proposed decoder is implemented.')
 parser.add_argument('corpus',type=pathlib.Path)
 parser.add_argument('--output',type=pathlib.Path,help='Optional per-route sizing JSON output')
 args=parser.parse_args()
 validate_goldens();raw=json.load(open(args.corpus));coords=[[[int(w[a]) for a in 'xyz'] for w in r['waypoints']] for r in raw]
 print('routes',len(coords),'points',sum(map(len,coords)),'range',min(map(len,coords)),max(map(len,coords)))
 rows=[];start=time.monotonic()
 for i,c in enumerate(coords):
  row={'index':i,'n':len(c),'base':len(baseline(c)[0])};pred,mp=predictor(c);row['pred']=len(frame(pred)[0]);row['pred_modes']=mp
  for s in [16,32,64]:row['block'+str(s)]=len(frame(blocks(c,s))[0])
  d,dm=diagonal(c);row['diag']=len(frame(d)[0]);row['diag_mode']=dm;row['portfolio']=min(row[m] for m in ['pred','block16','block32','block64','diag']);rows.append(row)
 summarize('CORPUS',rows)
 for n in [2,3,5,10,20]:
  r=[]
  for i,c in enumerate(coords):
   c=c[:n];row={'base':len(baseline(c)[0])};pred,_=predictor(c);row['pred']=len(frame(pred)[0]);d,_=diagonal(c);row['diag']=len(frame(d)[0]);
   for s in [16,32,64]:row['block'+str(s)]=len(frame(blocks(c,s))[0])
   row['portfolio']=min(row[m] for m in ['pred','block16','block32','block64','diag']);r.append(row)
  summarize('PREFIX '+str(n),r)
 print('seconds',time.monotonic()-start)
 print('largest predictor wins', sorted(rows,key=lambda r:r['base']-r['pred'],reverse=True)[:15])
 if args.output: args.output.write_text(json.dumps(rows,indent=2))
if __name__=='__main__':main()
