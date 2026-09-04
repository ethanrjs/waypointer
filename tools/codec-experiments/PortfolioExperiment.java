package com.babbur.waypointer.codec;
import com.babbur.waypointer.core.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public class PortfolioExperiment {
 static final WaypointCodec.Options BARE=WaypointCodec.Options.BARE_COORDINATES;
 static final WaypointCodec.Options SPARSE=WaypointCodec.Options.builder().includeNames(false).includeColors(false).includeRadii(false).includeWaypointFlags(true).includeGroupMeta(false).includeZone(false).build();
 static WaypointGroup group(JsonArray points,int limit) {
  WaypointGroup g=WaypointGroup.create("", "unknown");
  g.setGradientMode(WaypointGroup.GradientMode.MANUAL);g.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
  for(int i=0;i<Math.min(points.size(),limit);i++) { JsonObject w=points.get(i).getAsJsonObject();g.add(Waypoint.at(w.get("x").getAsInt(),w.get("y").getAsInt(),w.get("z").getAsInt())); }
  return g;
 }
 static void assertEqual(WaypointGroup a,WaypointGroup b) {
  if(!a.name().equals(b.name()) || !a.zoneId().equals(b.zoneId()) || a.gradientMode()!=b.gradientMode() || a.loadMode()!=b.loadMode() || a.routeKind()!=b.routeKind() || a.defaultRadius()!=b.defaultRadius() || a.skipAheadEnabled()!=b.skipAheadEnabled() || !a.waypoints().equals(b.waypoints()))throw new AssertionError("projection mismatch");
 }
 static void general(JsonArray data,int prefix) throws Exception {
  long base=0,best=0,candidate=0,baseNs=0,candidateNs=0;int wins=0,maxGain=0;List<String> winIds=new ArrayList<>();
  for(int i=0;i<data.size();i++) {
   WaypointGroup g=group(data.get(i).getAsJsonObject().getAsJsonArray("waypoints"),prefix);
   long start=System.nanoTime();var b=V10BareRouteCodec.encodeCandidate(g);baseNs+=System.nanoTime()-start;
   start=System.nanoTime();var c=V10GeneralRouteCodec.encodeCandidate(List.of(g),BARE);candidateNs+=System.nanoTime()-start;
   assertEqual(V10BareRouteCodec.decode(V10Transport.probe(b.transport())),V10GeneralRouteCodec.decode(V10Transport.probe(c.transport())).groups().getFirst());
   int bl=b.transport().length()+3,cl=c.transport().length()+3;base+=bl;candidate+=cl;best+=Math.min(bl,cl);
   if(cl<bl){wins++;maxGain=Math.max(maxGain,bl-cl);winIds.add(i+":"+bl+">"+cl);}
  }
  System.out.printf("GENERAL prefix=%d baseline=%d candidate=%d selected=%d saved=%d wins=%d max=%d baselineMs=%.3f candidateMs=%.3f winIds=%s%n",prefix,base,candidate,best,base-best,wins,maxGain,baseNs/1e6,candidateNs/1e6,winIds);
 }
 static void sparse(JsonArray data,int prefix,int variant) throws Exception {
  long base=0,best=0,sparseBase=0,sparseBest=0,baseNs=0,candidateNs=0;int wins=0,sparseWins=0,maxGain=0;List<String> winIds=new ArrayList<>();
  for(int i=0;i<data.size();i++) {
   WaypointGroup g=group(data.get(i).getAsJsonObject().getAsJsonArray("waypoints"),prefix);
   int index=Math.max(1,g.size()/2);Waypoint w=g.get(index);
   if(variant==0)g.set(index,w.withFlags(Waypoint.FLAG_HIDE_BEACON));
   if(variant==1)g.set(index,w.withFlags(Waypoint.FLAG_SUBWAYPOINT));
   if(variant==2)g.set(index,w.withPreciseSixteenths(w.preciseX()+1,w.preciseY()+2,w.preciseZ()+3));
   if(variant==3)for(int p=1;p<g.size();p+=7)g.set(p,g.get(p).withFlags(Waypoint.FLAG_SUBWAYPOINT));
   long start=System.nanoTime();String b=WaypointCodec.encode(List.of(g),SPARSE);baseNs+=System.nanoTime()-start;
   var sb=V10SparseRouteCodec.encodeCandidate(g,SPARSE);
   start=System.nanoTime();var c=SparseQuotientExperiment.encodeCandidate(g,SPARSE);candidateNs+=System.nanoTime()-start;
   assertEqual(g,V10SparseRouteCodec.decode(V10Transport.probe(c.transport())));
   int bl=b.length(),cl=c.transport().length()+3,sl=sb.transport().length()+3;base+=bl;best+=Math.min(bl,cl);sparseBase+=sl;sparseBest+=Math.min(sl,cl);
   if(cl<sl)sparseWins++;
   if(cl<bl){wins++;maxGain=Math.max(maxGain,bl-cl);winIds.add(i+":"+bl+">"+cl);}
  }
  System.out.printf("SPARSE prefix=%d variant=%d publicBase=%d publicBest=%d saved=%d wins=%d max=%d sparseBase=%d sparseBest=%d sparseWins=%d publicBaseMs=%.3f candidateMs=%.3f winIds=%s%n",prefix,variant,base,best,base-best,wins,maxGain,sparseBase,sparseBest,sparseWins,baseNs/1e6,candidateNs/1e6,winIds);
 }
 public static void main(String[]args)throws Exception {
  JsonArray data=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
  for(int n:new int[]{Integer.MAX_VALUE,2,3,5,10,20})general(data,n);
  for(int v=0;v<4;v++)sparse(data,Integer.MAX_VALUE,v);
  for(int n:new int[]{2,3,5,10,20})sparse(data,n,0);
 }
}
