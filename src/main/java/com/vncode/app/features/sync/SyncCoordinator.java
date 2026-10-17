package com.vncode.app.features.sync;

import com.vncode.app.models.Shop;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Application-scoped, bounded scheduling. Controllers subscribe; they do not own polling. */
public final class SyncCoordinator implements AutoCloseable {
    public enum Stream { FBS_QUEUE,FBS_STATUS,SUPPLIES,SUPPLY_CONTENT,HISTORY,CATALOG,ZNACK,GS1,FBO,FINANCE }
    public record Page(String checkpoint,boolean complete,int changed,SyncFailure warning) {
        public Page(String checkpoint,boolean complete,int changed){this(checkpoint,complete,changed,null);}
        public static Page complete(int changed){return new Page("",true,changed);}
    }
    public record Health(int shopId,Stream stream,boolean running,Instant success,Instant complete,Instant next,String error) {}
    @FunctionalInterface public interface Handler {Page run(Shop shop,Stream stream,String checkpoint)throws Exception;}
    private record Key(int shopId,Stream stream) {}
    private static final class Entry {
        final Shop shop;final String owner;volatile boolean running;volatile long dueNanos;long revision=-1;
        Entry(Shop shop,long now){this.shop=shop;owner=ListingIdentity.fingerprint(shop);dueNanos=now;}
    }
    private final java.nio.file.Path dataDirectory=com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize();
    private final SyncStreamStore store;
    private final Handler handler;
    private final Clock clock;
    private final LongSupplier nanos;
    private final Executor fast,background,registry;
    private final ScheduledExecutorService timer;
    private final Map<Key,Entry> entries=new LinkedHashMap<>();
    private final Set<Key> requested=ConcurrentHashMap.newKeySet();
    private final Map<Key,Health> health=new ConcurrentHashMap<>();
    private final List<Consumer<Health>> listeners=new CopyOnWriteArrayList<>();
    private volatile boolean closed;private volatile int selected;private int lastShop;private int fastRunning,backgroundRunning,registryRunning;
    public SyncCoordinator(Handler handler){
        this(new SyncStreamStore(),handler,Clock.systemUTC(),System::nanoTime,
            Executors.newFixedThreadPool(2,factory("vncode-fbs-sync")),Executors.newFixedThreadPool(1,factory("vncode-background-sync")),Executors.newFixedThreadPool(2,factory("vncode-registry-sync")),
            Executors.newSingleThreadScheduledExecutor(factory("vncode-sync-clock")));
    }
    SyncCoordinator(SyncStreamStore store,Handler handler,Clock clock,LongSupplier nanos,Executor fast,Executor background,ScheduledExecutorService timer){
        this(store,handler,clock,nanos,fast,background,background,timer);
    }
    SyncCoordinator(SyncStreamStore store,Handler handler,Clock clock,LongSupplier nanos,Executor fast,Executor background,Executor registry,ScheduledExecutorService timer){
        this.store=store;this.handler=handler;this.clock=clock;this.nanos=nanos;this.fast=fast;this.background=background;this.registry=registry;this.timer=timer;
        if(timer!=null)timer.scheduleWithFixedDelay(this::safeTick,1,1,TimeUnit.SECONDS);
    }
    private static ThreadFactory factory(String name){return r->{var t=new Thread(r,name);t.setDaemon(true);return t;};}
    public synchronized void updateShops(Collection<Shop> shops){
        var allowed=new HashSet<Key>();
        for(var source:shops){if(!source.isCredentialConfigured())continue;var shop=new Shop(source.getId(),source.getName(),source.getMarketplace(),source.getClientId(),source.getApiKey());
            for(var stream:Stream.values()){
                var key=new Key(shop.getId(),stream);allowed.add(key);var old=entries.get(key);
                if(old==null||!old.owner.equals(ListingIdentity.fingerprint(shop)))entries.put(key,new Entry(shop,nanos.getAsLong()));
            }
        }
        entries.keySet().retainAll(allowed);requested.retainAll(allowed);health.keySet().retainAll(allowed);
    }
    public void selectShop(int id){selected=id;}
    /** Non-blocking on FX: persistence and network requests are handled on the scheduler thread. */
    public void request(int shopId,Stream stream){if(!closed)requested.add(new Key(shopId,stream));}
    public synchronized boolean running(Stream stream){return entries.entrySet().stream().anyMatch(e->e.getKey().stream()==stream&&e.getValue().running);}
    public AutoCloseable listen(Consumer<Health> listener){listeners.add(listener);return ()->listeners.remove(listener);}
    public List<Health> health(int shopId){return health.values().stream().filter(h->h.shopId()==shopId).toList();}
    private void safeTick(){try{tick();}catch(RuntimeException error){int shop; synchronized(this){shop=selected;}if(shop>0){var old=health.get(new Key(shop,Stream.FBS_QUEUE));publish(new Health(shop,Stream.FBS_QUEUE,false,old==null?null:old.success(),old==null?null:old.complete(),clock.instant().plusSeconds(5),"SYNC_STORAGE"));}}}
    void tick(){
        try(var scope=SyncDataScope.enter(dataDirectory)){tickScoped();}
    }
    private void tickScoped(){
        if(closed)return;
        if(!dataDirectory.equals(com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize())){close();return;}
        List<Map.Entry<Key,Entry>> ordered; int firstShop;
        synchronized(this){
            ordered=new ArrayList<>(entries.entrySet());
            firstShop=entries.keySet().stream().mapToInt(Key::shopId).min().orElse(-1);
        }
        // Alternate shops, preserving bounded worker slots for the visible FBS queue.
        ordered.sort(Comparator.<Map.Entry<Key,Entry>>comparingLong(e->e.getValue().dueNanos)
            .thenComparingInt(e->e.getKey().shopId()==lastShop?1:0)
            .thenComparingInt(e->e.getKey().shopId()==selected?0:1)
            .thenComparingInt(e->e.getKey().stream().ordinal()));
        for(var pair:ordered){var key=pair.getKey();var entry=pair.getValue();if(entry.running)continue;
            boolean interactive=key.stream()==Stream.FBS_QUEUE||key.stream()==Stream.FBS_STATUS||key.stream()==Stream.SUPPLIES||key.stream()==Stream.SUPPLY_CONTENT;
            boolean registryTask=registry!=background&&(key.stream()==Stream.ZNACK||key.stream()==Stream.GS1);
            if(registryTask&&key.stream()==Stream.GS1&&running(Stream.GS1))continue;
            synchronized(this){if(closed||entries.get(key)!=entry||(interactive?fastRunning>=2:registryTask?registryRunning>=2:backgroundRunning>=1))continue;}
            // Finance retains its per-family planner but receives only one global slot.
            if(key.stream()==Stream.FINANCE&&key.shopId()!=firstShop)continue;
            var now=clock.instant();var state=store.ensure(entry.shop,key.stream().name(),now);
            if(requested.remove(key)){store.request(entry.shop,key.stream().name());state=store.ensure(entry.shop,key.stream().name(),now);}
            if(entry.revision<0){entry.dueNanos=nanos.getAsLong()+Math.max(0,Math.min(Duration.ofHours(1).toNanos(),Duration.between(now,state.next()).toNanos()));}
            if(state.requested()&&!state.error().endsWith("RATE_LIMIT")&&!state.error().endsWith("COOLDOWN")&&entry.revision!=state.revision())entry.dueNanos=nanos.getAsLong();
            entry.revision=state.revision();
            if(nanos.getAsLong()<entry.dueNanos)continue;
            synchronized(this){
                if(closed||entries.get(key)!=entry||entry.running||(interactive?fastRunning>=2:registryTask?registryRunning>=2:backgroundRunning>=1))continue;
                entry.running=true;if(interactive)fastRunning++;else if(registryTask)registryRunning++;else backgroundRunning++;lastShop=key.shopId();
            }
            try{
                store.attempt(entry.shop,key.stream().name(),state,now);
                publish(new Health(key.shopId(),key.stream(),true,state.lastSuccess(),state.lastComplete(),state.next(),state.error()));
                var captured=state;
                (interactive?fast:registryTask?registry:background).execute(()->execute(key,entry,captured,interactive));
            }catch(RuntimeException failed){
                synchronized(this){entry.running=false;if(interactive)fastRunning--;else if(registry!=background&&(key.stream()==Stream.ZNACK||key.stream()==Stream.GS1))registryRunning--;else backgroundRunning--;}
                entry.dueNanos=nanos.getAsLong()+TimeUnit.SECONDS.toNanos(2);
                if(!(failed instanceof RejectedExecutionException))throw failed;
            }
        }
    }
    private void execute(Key key,Entry entry,SyncStreamStore.State before,boolean interactive){
        try(var scope=SyncDataScope.enter(dataDirectory);var lane=com.vncode.app.shared.MarketplaceRequestBudget.lane(!interactive)){executeScoped(key,entry,before,interactive);}
    }
    private void executeScoped(Key key,Entry entry,SyncStreamStore.State before,boolean interactive){
        Page result=null;SyncFailure failure=null;
        try{result=ShopOperationCoordinator.withActiveShop(entry.shop.getId(),()->{ListingIdentity.require(entry.shop);return handler.run(entry.shop,key.stream(),before.checkpoint());});}
        catch(Exception error){failure=SyncFailure.of(error,before.failures());}
        if(failure==null&&result!=null&&result.warning()!=null)failure=result.warning();
        try{
            var now=clock.instant();Duration delay=failure!=null?failure.delay():!result.complete()?Duration.ofSeconds(1):interval(key);
            var next=now.plus(delay);String checkpoint=result==null?before.checkpoint():result.checkpoint();
            store.finish(entry.shop,key.stream().name(),before,checkpoint,result!=null&&result.complete(),now,next,failure);
            var after=store.ensure(entry.shop,key.stream().name(),now);
            entry.dueNanos=nanos.getAsLong()+delay.toNanos();
            publish(new Health(key.shopId(),key.stream(),false,after.lastSuccess(),after.lastComplete(),after.next(),after.error()));
        }catch(RuntimeException storageOrOwnerChanged){
            entry.dueNanos=nanos.getAsLong()+TimeUnit.SECONDS.toNanos(30);
            synchronized(this){if(closed||entries.get(key)!=entry)return;}
            publish(new Health(key.shopId(),key.stream(),false,before.lastSuccess(),before.lastComplete(),clock.instant().plusSeconds(30),"SYNC_STORAGE"));
        }
        finally{synchronized(this){entry.running=false;if(interactive)fastRunning--;else if(registry!=background&&(key.stream()==Stream.ZNACK||key.stream()==Stream.GS1))registryRunning--;else backgroundRunning--;}}
    }
    private Duration interval(Key key){return switch(key.stream()){
        case FBS_QUEUE->Duration.ofSeconds(key.shopId()==selected?10:45);
        case FBS_STATUS->Duration.ofSeconds(key.shopId()==selected?15:60);
        case SUPPLIES->Duration.ofSeconds(key.shopId()==selected?30:90);
        case SUPPLY_CONTENT->Duration.ofSeconds(key.shopId()==selected?10:45);
        case HISTORY->Duration.ofMinutes(5);case CATALOG,ZNACK,GS1,FBO->Duration.ofMinutes(15);case FINANCE->Duration.ofSeconds(5);
    };}
    private void publish(Health update){health.put(new Key(update.shopId(),update.stream()),update);for(var listener:listeners)try{listener.accept(update);}catch(RuntimeException ignored){}}
    @Override public synchronized void close(){closed=true;if(timer!=null)timer.shutdownNow();if(fast instanceof ExecutorService e)e.shutdown();if(background instanceof ExecutorService e)e.shutdown();if(registry!=background&&registry instanceof ExecutorService e)e.shutdown();listeners.clear();}
}
