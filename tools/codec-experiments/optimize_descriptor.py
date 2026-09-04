from explore import *

def optimized(c,version=11):
 if len(c)<=1:
  s=bytes([32+version])+prefix(c)[1:]
  return frame(s),[-1,-1,-1]
 ax=axes(c)
 ks=[]
 for v in ax:
  possible=list(range(31)) + ([-1] if not any(v) else [])
  ks.append(min(possible,key=lambda k:(payloadcost(v,k)+(3 if 0<=k<=6 else 8),k)))
 candidates=[]
 for tup in [tuple(ks)]+COMMON:
  b=Bits();desc(b,tup)
  for v,k in zip(ax,tup):
   if k!=-1:
    for x in v:b.rice(x,k)
  sem=bytes([32+version])+prefix(c)[1:]+b.bytes()
  candidates.append((frame(sem),tup))
 return min(candidates,key=lambda p:compare(p[0]))

def verify_reconstruction(c):
 for mode in range(4):
  aa=axes(c,mode);out=[c[0].copy()]
  for i in range(1,len(c)):
   v=[]
   for a in range(3):
    pred=out[i-1][a]
    if mode==1 and i>1:pred=2*out[i-1][a]-out[i-2][a]
    if mode==2 and i>1:pred=out[i-2][a]
    if mode==3 and i>2:pred=out[i-1][a]+out[i-2][a]-out[i-3][a]
    value=aa[a][i-1];residual=value//2 if value%2==0 else -(value//2)-1
    v.append(pred+residual)
   out.append(v)
  assert out==c

def main():
 import argparse
 parser=argparse.ArgumentParser(description='Size-only hypothetical version-11 Rice descriptor experiment.')
 parser.add_argument('corpus',type=pathlib.Path)
 args=parser.parse_args()
 raw=json.load(open(args.corpus));coords=[[[int(w[a]) for a in 'xyz'] for w in r['waypoints']] for r in raw]
 for c in coords:verify_reconstruction(c)
 for n in [1000000,2,3,5,10,20]:
  base=new=0;wins=0;gainhist=collections.Counter();baseks=collections.Counter();newks=collections.Counter()
  for c in coords:
   c=c[:n];baseframe=baseline(c);candidate,ks=optimized(c)
   bl=len(baseframe[0]);cl=min(bl,len(candidate[0]));base+=bl;new+=cl;wins+=cl<bl;gainhist[bl-cl]+=1
   if cl<bl:baseks[tuple(choosek(v) for v in axes(c))]+=1;newks[ks]+=1
  print('optimized descriptor',n,'base',base,'new',new,'save',base-new,'pct',round((base-new)/base*100,2),'wins',wins,'gains',gainhist,'newtuples',newks.most_common(5),'oldtuples',baseks.most_common(5))
if __name__=='__main__':main()
