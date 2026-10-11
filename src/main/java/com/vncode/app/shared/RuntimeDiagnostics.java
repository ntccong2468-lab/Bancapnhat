package com.vncode.app.shared;

import javafx.animation.AnimationTimer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in aggregate counters only: no shop IDs, credentials, products or network payloads. */
public final class RuntimeDiagnostics {
 private static final Map<String,ArrayDeque<Long>> SAMPLES=new LinkedHashMap<>();
 private static final AtomicInteger WAITERS=new AtomicInteger();
 private static long stalls,maxPulseGap;private static AnimationTimer monitor;
 private RuntimeDiagnostics(){}
 public static boolean enabled(){return Boolean.getBoolean("vncode.runtime.diagnostics");}
 public static synchronized void record(String operation,long nanos){
  if(!enabled()||operation==null||!operation.matches("[a-z][a-z0-9-]{0,31}"))return;
  if(!SAMPLES.containsKey(operation)&&SAMPLES.size()>=16)SAMPLES.remove(SAMPLES.keySet().iterator().next());
  var rows=SAMPLES.computeIfAbsent(operation,k->new ArrayDeque<>());if(rows.size()==2048)rows.removeFirst();rows.addLast(Math.max(0,nanos));
 }
 public static void afterNextPulse(String operation,long started){
  if(!enabled())return;if(WAITERS.incrementAndGet()>64){WAITERS.decrementAndGet();return;}
  new AnimationTimer(){int frames;public void handle(long now){if(++frames<2)return;stop();WAITERS.decrementAndGet();record(operation,System.nanoTime()-started);}}.start();
 }
 public static void startPulseMonitor(){
  if(!enabled()||monitor!=null)return;
  monitor=new AnimationTimer(){long previous;public void handle(long now){if(previous!=0){long gap=now-previous;synchronized(RuntimeDiagnostics.class){maxPulseGap=Math.max(maxPulseGap,gap);if(gap>500_000_000)stalls++;}}previous=now;}};monitor.start();
 }
 public static void stopPulseMonitor(){if(monitor!=null){monitor.stop();monitor=null;}}
 public static synchronized void reset(){SAMPLES.clear();stalls=0;maxPulseGap=0;}
 public static synchronized Map<String,Object> snapshot(){
  if(!enabled())return Map.of();var report=new LinkedHashMap<String,Object>();
  for(var entry:SAMPLES.entrySet()){var rows=new ArrayList<>(entry.getValue());rows.sort(Long::compare);report.put(entry.getKey(),Map.of("samples",rows.size(),"medianMs",rows.get(rows.size()/2)/1e6,"p95Ms",rows.get(Math.max(0,(int)Math.ceil(rows.size()*.95)-1))/1e6));}
  report.put("fxStallsOver500ms",stalls);report.put("maxFxPulseGapMs",maxPulseGap/1e6);
  var runtime=Runtime.getRuntime();report.put("heapUsedBytes",runtime.totalMemory()-runtime.freeMemory());report.put("maxHeapBytes",runtime.maxMemory());report.put("logicalProcessors",runtime.availableProcessors());
  var reads=ShopReadCoordinator.shared();report.put("readWorkers",reads.workerLimit());report.put("readActive",reads.activeCount());report.put("readPending",reads.pendingCount());report.put("imageWorkers",ShopReadCoordinator.images().workerLimit());report.put("imagePending",ShopReadCoordinator.images().pendingCount());
  report.put("legacyTasks",AppTaskExecutor.getRunningTaskCount());report.put("mutationActive",SellerMutationExecutor.activeCount());report.put("mutationPending",SellerMutationExecutor.pendingCount());report.put("sellerHttpActive",MarketplaceRequestBudget.activeCount());report.put("screenCacheBytes",ThumbnailLoader.sharedRetainedBytes()+com.vncode.app.features.fbo.FboProductImageService.retainedBytes());report.put("screenCacheBudgetBytes",67108864L);return Collections.unmodifiableMap(report);
 }
}
