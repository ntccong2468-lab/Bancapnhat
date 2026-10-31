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
    /** Safe summary only; no cursors, tokens or upstream messages. */
    public record QueueProgress(int pages,String error,Instant next,boolean running,boolean requested) {}
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
    /** Must be called off the UI thread. This is a point-in-time local check. */
    public boolean isQueueCurrent(Shop shop,Duration maxAge){
        if(closed||shop==null||maxAge==null||maxAge.isNegative()||maxAge.isZero())return false;
        Key key=new Key(shop.getId(),Stream.FBS_QUEUE);Entry entry;
        synchronized(this){entry=entries.get(key);if(entry==null||entry.running||requested.contains(key)||!entry.owner.equals(ListingIdentity.fingerprint(shop)))return false;}
        try{
            ListingIdentity.require(shop);
            var state=store.existing(shop.getId(),Stream.FBS_QUEUE.name());
            if(state==null||!entry.owner.equals(state.owner())||state.requested()||!state.checkpoint().isBlank()||!state.error().isBlank()||state.lastComplete()==null)return false;
            Instant now=clock.instant();
            if(state.lastComplete().isAfter(now)||Duration.between(state.lastComplete(),now).compareTo(maxAge)>0)return false;
            synchronized(this){return !closed&&entries.get(key)==entry&&!entry.running&&!requested.contains(key);}
        }catch(RuntimeException unavailable){return false;}
    }
    /** Ozon may know every posting ID even when one posting lacks details. */
    public boolean isQueueMembershipCurrent(Shop shop,Duration maxAge){
        if(isQueueCurrent(shop,maxAge))return true;
        if(closed||shop==null||shop.getMarketplace()!=com.vncode.app.integration.marketplace.Marketplace.OZON||maxAge==null||maxAge.isNegative()||maxAge.isZero())return false;
        Key key=new Key(shop.getId(),Stream.FBS_QUEUE);Entry entry;
        synchronized(this){entry=entries.get(key);if(entry==null||entry.running||requested.contains(key)||!entry.owner.equals(ListingIdentity.fingerprint(shop)))return false;}
        try{
            ListingIdentity.require(shop);var state=store.existing(shop.getId(),Stream.FBS_QUEUE.name());
            if(state==null||!entry.owner.equals(state.owner())||state.requested()||!state.checkpoint().isBlank()||!"FBS_INCOMPLETE_POSTING".equals(state.error()))return false;
            var attempt=store.lastAttempt(shop.getId(),Stream.FBS_QUEUE.name());if(attempt==null)return false;
            Instant complete;
            try(var c=com.vncode.app.config.Database.getConnection();var p=c.prepareStatement("SELECT owner,completed_at,incomplete FROM ozon_fbs_queue_scans WHERE shop_id=?")){
                p.setInt(1,shop.getId());try(var r=p.executeQuery()){
                    if(!r.next()||!entry.owner.equals(r.getString(1))||r.getInt(3)!=1)return false;
                    complete=Instant.parse(r.getString(2));
                }
            }
            Instant now=clock.instant();if(complete.isBefore(attempt)||complete.isAfter(now)||Duration.between(complete,now).compareTo(maxAge)>0)return false;
            synchronized(this){return !closed&&entries.get(key)==entry&&!entry.running&&!requested.contains(key);}
        }catch(Exception invalid){return false;}
    }
    /** Read on a worker thread; this does not start or alter synchronization. */
    public QueueProgress queueProgress(Shop shop){
        QueueProgress unavailable=new QueueProgress(0,"SYNC_UNAVAILABLE",null,false,false);
        if(closed||shop==null)return unavailable;
        Key key=new Key(shop.getId(),Stream.FBS_QUEUE);Entry entry;
        synchronized(this){entry=entries.get(key);if(entry==null||!entry.owner.equals(ListingIdentity.fingerprint(shop)))return unavailable;}
        try{
            ListingIdentity.require(shop);
            var state=store.existing(shop.getId(),Stream.FBS_QUEUE.name());
            if(state==null||!entry.owner.equals(state.owner()))return unavailable;
            int pages=0;
            if(state.checkpoint()!=null&&!state.checkpoint().isBlank()){
                var object=com.google.gson.JsonParser.parseString(state.checkpoint()).getAsJsonObject();
                if(object.has("pages"))pages=Math.max(0,Math.min(20_001,object.get("pages").getAsInt()));
            }
            String error=state.error()==null||state.error().isBlank()?"":SyncRunRepository.code(state.error());
            synchronized(this){if(closed||entries.get(key)!=entry)return unavailable;return new QueueProgress(pages,error,state.next(),entry.running,state.requested()||requested.contains(key));}
        }catch(RuntimeException invalid){return unavailable;}
    }
    public AutoCloseable listen(Consumer<Health> listener){listeners.add(listener);return ()->listeners.remove(listener);}
    public List<Health> health(int shopId){return health.values().stream().filter(h->h.shopId()==shopId).toList();}
    private void safeTick(){try{tick();}catch(RuntimeException error){int shop; synchronized(this){shop=selected;}if(shop>0){var old=health.get(new Key(shop,Stream.FBS_QUEUE));publish(new Health(shop,Stream.FBS_QUEUE,false,old==null?null:old.success(),old==null?null:old.complete(),clock.instant().plusSeconds(5),"SYNC_STORAGE"));}}}
    void tick(){
        try(var scope=SyncDataScope.enter(dataDirectory)){tickScoped();}
    }
    private void tickScoped(){
        if(closed||com.vncode.app.shared.UpdateHandoff.requested())return;
        if(!dataDirectory.equals(com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize())){close();return;}
        List<Map.Entry<Key,Entry>> ordered; int firstShop,visibleShop;
        synchronized(this){
            ordered=new ArrayList<>(entries.entrySet());
            firstShop=entries.keySet().stream().mapToInt(Key::shopId).min().orElse(-1);
            visibleShop=selected;
        }
        // A large overdue backlog must not defer the visible shop's actionable queue.
        // Future/cooldown deadlines are still checked below; other shops use the remaining turns.
        ordered.sort(Comparator.<Map.Entry<Key,Entry>>comparingInt(e->visibleFbsPriority(e.getKey(),visibleShop))
            .thenComparingLong(e->e.getValue().dueNanos)
            .thenComparingInt(e->e.getKey().shopId()==lastShop?1:0)
            .thenComparingInt(e->e.getKey().shopId()==visibleShop?0:1)
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
    private static int visibleFbsPriority(Key key,int visibleShop){
        if(key.shopId()!=visibleShop)return 4;
        return switch(key.stream()){
            case FBS_QUEUE->0;case FBS_STATUS->1;case SUPPLY_CONTENT->2;case SUPPLIES->3;default->4;
        };
    }
    private void execute(Key key,Entry entry,SyncStreamStore.State before,boolean interactive){
        try(var work=com.vncode.app.shared.UpdateHandoff.work();var scope=SyncDataScope.enter(dataDirectory);var lane=com.vncode.app.shared.MarketplaceRequestBudget.lane(!interactive)){executeScoped(key,entry,before,interactive);}
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
    private Duration interval(Key key){
        int otherShops;
        synchronized(this){otherShops=(int)entries.keySet().stream().filter(k->k.stream()==Stream.FBS_QUEUE&&k.shopId()!=selected).count();}
        long backgroundShops=Math.max(1,otherShops);
        return switch(key.stream()){
        // Poll the visible seller frequently while preserving the shared per-seller
        // API limiter and any server-requested cooldown. Spread background
        // polling across the configured shops so one or two shops are timely,
        // while large multi-shop accounts do not overwhelm their shared quota.
        case FBS_QUEUE->Duration.ofSeconds(key.shopId()==selected?3:Math.max(10,backgroundShops*5));
        case FBS_STATUS->Duration.ofSeconds(key.shopId()==selected?5:Math.max(15,backgroundShops*7));
        case SUPPLIES->Duration.ofSeconds(key.shopId()==selected?30:Math.max(30,backgroundShops*10));
        case SUPPLY_CONTENT->Duration.ofSeconds(key.shopId()==selected?10:Math.max(15,backgroundShops*5));
        case HISTORY->Duration.ofMinutes(5);case CATALOG,ZNACK,GS1,FBO->Duration.ofMinutes(15);case FINANCE->Duration.ofSeconds(5);
    };}
    private void publish(Health update){health.put(new Key(update.shopId(),update.stream()),update);for(var listener:listeners)try{listener.accept(update);}catch(RuntimeException ignored){}}
    @Override public synchronized void close(){closed=true;if(timer!=null)timer.shutdownNow();if(fast instanceof ExecutorService e)e.shutdown();if(background instanceof ExecutorService e)e.shutdown();if(registry!=background&&registry instanceof ExecutorService e)e.shutdown();listeners.clear();}
}
