package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import java.nio.file.Path;
import java.util.*;

public class CompactProjectionExperiment {
 static final WaypointCodec.Options COMMON=WaypointCodec.Options.builder().includeNames(true).includeColors(true).includeZone(true).includeRadii(false).includeWaypointFlags(false).includeGroupMeta(false).build();
 static boolean unchangedFullProjection(WaypointGroup g,WaypointCodec.Options o){
  if(!o.includeNames || !o.includeColors || !o.includeZone || !o.label.isEmpty() || g.routeKind()!=WaypointGroup.RouteKind.REGULAR)return false;
  if(!o.includeGroupMeta && (g.gradientMode()!=WaypointGroup.GradientMode.MANUAL || g.loadMode()!=WaypointGroup.LoadMode.SEQUENCE || g.defaultRadius()!=Waypoint.DEFAULT_REACH_RADIUS || !g.skipAheadEnabled() || g.staticColor()!=Waypoint.DEFAULT_COLOR || g.gradientStartColor()!=0x00BFFF || g.gradientEndColor()!=0xFF3040))return false;
  for(Waypoint w:g.waypoints()){
   if(!o.includeRadii && w.customRadius()!=0.0)return false;
   if((w.color() & 0xFF000000)!=0)return false;
   if(w.flags()!=WaypointCodec.exportedWaypointFlags(w,o))return false;
   if(w.hasCustomPrecisePosition()!=WaypointCodec.shouldExportPrecisePosition(w,o))return false;
  }
  return V10CompactRouteCodec.canEncode(g,WaypointCodec.Options.FULL_FIDELITY);
 }
 static void same(WaypointGroup a,WaypointGroup b){
  if(!a.name().equals(b.name()) || !a.zoneId().equals(b.zoneId()) || a.routeKind()!=b.routeKind() || a.gradientMode()!=b.gradientMode() || a.loadMode()!=b.loadMode() || a.defaultRadius()!=b.defaultRadius() || a.skipAheadEnabled()!=b.skipAheadEnabled() || a.staticColor()!=b.staticColor() || a.gradientStartColor()!=b.gradientStartColor() || a.gradientEndColor()!=b.gradientEndColor() || !a.waypoints().equals(b.waypoints()))throw new AssertionError("Common/full semantic mismatch: "+a.name()+" fields="+a.waypoints().equals(b.waypoints()));
 }
 static String compact(WaypointGroup g)throws Exception{return "WP:"+V10CompactRouteCodec.encodeCandidate(g,WaypointCodec.Options.FULL_FIDELITY).transport();}
 // Explicit old selector path: before the COMMON gate, only general kind 0 was eligible.
 static String baseline(WaypointGroup g)throws Exception{return "WP:"+V10GeneralRouteCodec.encodeCandidate(List.of(g),COMMON).transport();}
 static List<WaypointGroup> prefixes(List<WaypointGroup>groups,int n){
  List<WaypointGroup>out=new ArrayList<>();
  for(WaypointGroup g:groups){var p=g.exportSnapshot();p.replaceWaypoints(g.waypoints().subList(0,Math.min(n,g.size())));out.add(p);}
  return out;
 }
 static void measure(List<WaypointGroup>groups,String label)throws Exception{
  long base=0,best=0,cand=0;int wins=0,equal=0,worse=0,eligible=0,max=0;List<String>exceptions=new ArrayList<>();
  for(int i=0;i<groups.size();i++){
   var g=groups.get(i);String b=baseline(g);base+=b.length();
   if(!unchangedFullProjection(g,COMMON)){best+=b.length();exceptions.add("ineligible:"+i);continue;}
   eligible++;String c=compact(g);cand+=c.length();best+=Math.min(c.length(),b.length());
   if(c.length()<b.length()){wins++;max=Math.max(max,b.length()-c.length());}else if(c.length()==b.length())equal++;else worse++;
   same(WaypointCodec.decode(b).getFirst(),WaypointCodec.decode(c).getFirst());
  }
  System.out.printf(Locale.ROOT,"COMPACT_COMMON %s routes=%d eligible=%d baseline=%d candidate=%d selected=%d saved=%d percent=%.3f wins=%d equal=%d worse=%d maximum=%d exceptions=%s%n",label,groups.size(),eligible,base,cand,best,base-best,100.0*(base-best)/base,wins,equal,worse,max,exceptions);
 }
 static long timer(List<WaypointGroup>groups,boolean addCandidate)throws Exception{
  long start=System.nanoTime();int sum=0;
  for(var g:groups){String b=baseline(g);if(addCandidate&&unchangedFullProjection(g,COMMON)){String c=compact(g);sum+=Math.min(b.length(),c.length());}else sum+=b.length();}
  if(sum==0)throw new AssertionError();return System.nanoTime()-start;
 }
 public static void main(String[]args)throws Exception{
  var corpus=CodecRouteCorpus.load(Path.of(args[0]));List<WaypointGroup>groups=new ArrayList<>();
  for(var route:corpus.routes())groups.add(CodecRouteBenchmarkTest.commonProjection(route.group(),WaypointExportCodec.Target.SKYBLOCKER));
  measure(groups,"all");for(int n:new int[]{1,2,3,5,10,20})measure(prefixes(groups,n),"prefix"+n);
  for(int i=0;i<4;i++){timer(groups,false);timer(groups,true);}
  long[]oldTimes=new long[7],newTimes=new long[7];
  for(int i=0;i<7;i++){if((i&1)==0){oldTimes[i]=timer(groups,false);newTimes[i]=timer(groups,true);}else{newTimes[i]=timer(groups,true);oldTimes[i]=timer(groups,false);}}
  System.out.println("EXPLORATORY oldNs="+Arrays.toString(oldTimes)+" addedCompactNs="+Arrays.toString(newTimes));
  Arrays.sort(oldTimes);Arrays.sort(newTimes);System.out.printf(Locale.ROOT,"EXPLORATORY medianOldMs=%.3f medianAddedCompactMs=%.3f overhead=%.3f%%%n",oldTimes[3]/1e6,newTimes[3]/1e6,(newTimes[3]/(double)oldTimes[3]-1)*100);
 }
}
