package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SyncCoordinatorTest {
    @TempDir Path root;Shop shop;AtomicLong nanos=new AtomicLong();Clock clock=Clock.systemUTC();
    final Queue<Runnable> fast=new ArrayDeque<>(),background=new ArrayDeque<>();SyncCoordinator coordinator;
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);}
    @AfterEach void clear(){if(coordinator!=null)coordinator.close();System.clearProperty("vncode.appdata.dir");}
    private void create(SyncCoordinator.Handler handler){coordinator=new SyncCoordinator(new SyncStreamStore(),handler,clock,nanos::get,fast::add,background::add,null);coordinator.updateShops(List.of(shop));}
    @Test void repeatedRefreshesCoalesceAndFbsHasReservedWorkersWhileBackgroundRuns(){
        var calls=new ArrayList<SyncCoordinator.Stream>();create((s,stream,cursor)->{calls.add(stream);return SyncCoordinator.Page.complete(1);});
        for(int n=0;n<100;n++)coordinator.request(shop.getId(),SyncCoordinator.Stream.FBS_QUEUE);
        coordinator.tick();assertEquals(2,fast.size());assertEquals(1,background.size());coordinator.tick();assertEquals(2,fast.size());assertEquals(1,background.size());
        fast.remove().run();assertEquals(List.of(SyncCoordinator.Stream.FBS_QUEUE),calls);assertNotNull(new SyncStreamStore().ensure(shop,"FBS_QUEUE",Instant.now()).lastComplete());
    }
    @Test void catalogAndFinanceCannotOccupyTheRegistryWorkers(){
        var registry=new ArrayDeque<Runnable>();var calls=new ArrayList<SyncCoordinator.Stream>();
        coordinator=new SyncCoordinator(new SyncStreamStore(),(s,stream,cursor)->{calls.add(stream);return SyncCoordinator.Page.complete(0);},clock,nanos::get,fast::add,background::add,registry::add,null);
        coordinator.updateShops(List.of(shop));coordinator.tick();
        assertEquals(2,registry.size());assertEquals(2,fast.size());assertEquals(1,background.size());
        while(!registry.isEmpty())registry.remove().run();
        assertTrue(calls.contains(SyncCoordinator.Stream.ZNACK));assertTrue(calls.contains(SyncCoordinator.Stream.GS1));
        assertEquals(1,background.size(),"Catalog worker is still occupied while registry requests can finish independently");
    }
    @Test void fbsKeepsPollingWhileCatalogAndRegistryJobsRemainOccupied(){
        var registry = new ArrayDeque<Runnable>(); var queues = new java.util.concurrent.atomic.AtomicInteger();
        coordinator = new SyncCoordinator(new SyncStreamStore(), (s, stream, cursor) -> {
            if (stream == SyncCoordinator.Stream.FBS_QUEUE) queues.incrementAndGet();
            return SyncCoordinator.Page.complete(0);
        }, clock, nanos::get, fast::add, background::add, registry::add, null);
        coordinator.updateShops(List.of(shop)); coordinator.selectShop(shop.getId());
        for (int second = 0; second <= 25; second++) {
            coordinator.tick(); while (!fast.isEmpty()) fast.remove().run();
            nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        }
        assertEquals(9, queues.get(), "Visible FBS queue must refresh every three seconds despite occupied slow workers");
        assertEquals(1, background.size()); assertEquals(2, registry.size());
    }
    @Test void overdueOtherShopsCannotDelayTheVisibleShopsDueFbsQueue()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(3,'Other','fixture-token','WILDBERRIES')");
        }
        var other=new Shop(3,"Other",Marketplace.WILDBERRIES,null,"fixture-token");
        var calls=new ArrayList<String>();
        create((s,stream,cursor)->{calls.add(s.getId()+":"+stream.name());return SyncCoordinator.Page.complete(0);});
        coordinator.updateShops(List.of(shop,other));coordinator.selectShop(shop.getId());
        coordinator.tick();while(!fast.isEmpty())fast.remove().run();calls.clear();
        // Other shops and shipment scans still have older deadlines; the visible queue is due again.
        nanos.set(Duration.ofSeconds(20).toNanos());coordinator.request(shop.getId(),SyncCoordinator.Stream.FBS_QUEUE);
        coordinator.tick();fast.remove().run();
        assertEquals(shop.getId()+":FBS_QUEUE",calls.getFirst());
    }
    @Test void requestArrivingDuringReadIsNotLostByCompletion(){
        create((s,stream,cursor)->{if(stream==SyncCoordinator.Stream.FBS_QUEUE)new SyncStreamStore().request(shop,stream.name());return SyncCoordinator.Page.complete(0);});
        coordinator.tick();fast.remove().run();assertTrue(new SyncStreamStore().ensure(shop,"FBS_QUEUE",Instant.now()).requested());
    }
    @Test void failedReadPreservesCheckpointAndNeverAdvancesSuccessWatermark(){
        var store=new SyncStreamStore();var state=store.ensure(shop,"FBS_QUEUE",Instant.now());store.finish(shop,"FBS_QUEUE",state,"page2",false,Instant.now(),Instant.now(),null);
        create((s,stream,cursor)->{assertEquals("page2",cursor);throw new java.net.SocketTimeoutException("sensitive raw URL must not persist");});
        coordinator.tick();fast.remove().run();var after=store.ensure(shop,"FBS_QUEUE",Instant.now());assertEquals("page2",after.checkpoint());assertNull(after.lastComplete());assertEquals("NETWORK_TIMEOUT",after.error());assertEquals(1,after.failures());
    }
    @Test void wallClockChangesDoNotBypassMonotonicDeadline(){
        var instant=new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return instant.get();}};
        var queueCalls=new java.util.concurrent.atomic.AtomicInteger();
        create((s,stream,cursor)->{if(stream==SyncCoordinator.Stream.FBS_QUEUE)queueCalls.incrementAndGet();return SyncCoordinator.Page.complete(0);});coordinator.selectShop(shop.getId());
        coordinator.tick();while(!fast.isEmpty())fast.remove().run();assertEquals(1,queueCalls.get());
        instant.set(instant.get().plus(Duration.ofDays(100)));coordinator.tick();while(!fast.isEmpty())fast.remove().run();assertEquals(1,queueCalls.get(),"Wall-clock jump must not cause an early poll");
        nanos.set(Duration.ofSeconds(11).toNanos());coordinator.tick();while(!fast.isEmpty())fast.remove().run();assertEquals(2,queueCalls.get());
    }
    @Test void unsafeUpstreamMessageIsNeverStoredAsErrorCode(){
        var failure=SyncFailure.of(new IllegalStateException("Authorization: secret-token @ private endpoint"),2);assertEquals("SYNC_INTERNAL",failure.code());assertFalse(failure.toString().contains("secret-token"));
    }
    @Test void fieldSpecificRegistrationErrorsKeepTheirCodeWithoutPersistingPrivateDetails(){
        var failure=SyncFailure.of(new IllegalStateException("ZNACK_INVALID_ATTRIBUTE_VALUE: Размер одежды [35]; token=private-secret"),0);
        assertEquals("ZNACK_INVALID_ATTRIBUTE_VALUE",failure.code());
        assertFalse(failure.toString().contains("private-secret"));
        assertEquals("SYNC_INTERNAL",SyncFailure.of(new IllegalStateException("Authorization: secret-token"),0).code());
    }
    @Test void blockedSqliteWriterCannotBlockUiSelectionRefreshOrShutdown()throws Exception{
        create((s,stream,cursor)->SyncCoordinator.Page.complete(0));
        var scheduler=java.util.concurrent.Executors.newSingleThreadExecutor();
        var controls=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var lock=Database.getConnection();var sql=lock.createStatement()){
            sql.execute("BEGIN IMMEDIATE");
            var started=new java.util.concurrent.CountDownLatch(1);
            var tick=scheduler.submit(()->{started.countDown();coordinator.tick();});
            try{
                assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS));
                Thread.sleep(100);
                assertFalse(tick.isDone(),"The scheduler must actually be waiting for SQLite");
                controls.submit(()->{
                    coordinator.selectShop(shop.getId());
                    coordinator.request(shop.getId(),SyncCoordinator.Stream.GS1);
                    coordinator.updateShops(List.of(shop));
                    coordinator.close();
                }).get(500,java.util.concurrent.TimeUnit.MILLISECONDS);
            }finally{sql.execute("ROLLBACK");}
            tick.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }finally{scheduler.shutdownNow();controls.shutdownNow();}
    }
    @Test void tenShopsEventuallyReceiveBackgroundGs1AndFbsTurns()throws Exception{
        var shops=new ArrayList<Shop>();shops.add(shop);
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO shops(id,name,api_key,marketplace) VALUES(?,'Synthetic','fixture-token','WILDBERRIES')")){
            for(int id=3;id<=11;id++){p.setInt(1,id);p.executeUpdate();shops.add(new Shop(id,"Synthetic",Marketplace.WILDBERRIES,null,"fixture-token"));}
        }
        var gs1=new HashSet<Integer>();var queues=new HashSet<Integer>();
        create((s,stream,cursor)->{if(stream==SyncCoordinator.Stream.GS1)gs1.add(s.getId());if(stream==SyncCoordinator.Stream.FBS_QUEUE)queues.add(s.getId());return SyncCoordinator.Page.complete(0);});
        coordinator.updateShops(shops);coordinator.selectShop(shop.getId());
        for(int turn=0;turn<100;turn++){
            coordinator.tick();while(!fast.isEmpty())fast.remove().run();while(!background.isEmpty())background.remove().run();
            nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        }
        assertEquals(10,queues.size());assertEquals(10,gs1.size(),"Repeated visible-shop polls cannot starve GS1 of other shops");
    }
    @Test void backgroundShopRefreshesPromptlyWithoutStarvingTheVisibleShop()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,api_key,marketplace) VALUES(3,'Other','fixture-token','WILDBERRIES')");
        }
        var other=new Shop(3,"Other",Marketplace.WILDBERRIES,null,"fixture-token");
        var queueCalls=new HashMap<Integer,Integer>();
        create((s,stream,cursor)->{if(stream==SyncCoordinator.Stream.FBS_QUEUE)queueCalls.merge(s.getId(),1,Integer::sum);return SyncCoordinator.Page.complete(0);});
        coordinator.updateShops(List.of(shop,other));coordinator.selectShop(shop.getId());
        for(int second=0;second<=13;second++){
            coordinator.tick();while(!fast.isEmpty())fast.remove().run();while(!background.isEmpty())background.remove().run();
            nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        }
        assertTrue(queueCalls.getOrDefault(shop.getId(),0)>=4,"Visible shop continues frequent polling");
        assertTrue(queueCalls.getOrDefault(other.getId(),0)>=2,"A second shop does not wait 45 seconds for refresh");
    }
    @Test void manualRefreshCannotBypassRateLimitCooldown(){
        create((s,stream,cursor)->{throw new IllegalStateException("OZON_RATE_LIMIT");});
        var store=new SyncStreamStore();var now=Instant.now();var state=store.ensure(shop,"FBS_QUEUE",now);
        store.finish(shop,"FBS_QUEUE",state,"cursor",false,now,now.plusSeconds(120),new SyncFailure("OZON_RATE_LIMIT",Duration.ofSeconds(120),false));
        coordinator.request(shop.getId(),SyncCoordinator.Stream.FBS_QUEUE);coordinator.tick();
        assertTrue(coordinator.health(shop.getId()).stream().noneMatch(h->h.stream()==SyncCoordinator.Stream.FBS_QUEUE&&h.running()));
    }
    @Test void queueFreshnessRequiresACompleteSuccessfulReadAndNoPendingRefresh(){
        create((s,stream,cursor)->SyncCoordinator.Page.complete(1));coordinator.selectShop(shop.getId());
        assertFalse(coordinator.isQueueCurrent(shop,Duration.ofSeconds(30)));
        coordinator.tick();assertFalse(coordinator.isQueueCurrent(shop,Duration.ofSeconds(30)),"In-flight read is not complete");
        fast.remove().run();assertTrue(coordinator.isQueueCurrent(shop,Duration.ofSeconds(30)));
        coordinator.request(shop.getId(),SyncCoordinator.Stream.FBS_QUEUE);
        assertFalse(coordinator.isQueueCurrent(shop,Duration.ofSeconds(30)),"A requested refresh must finish first");
    }
    @Test void queueFreshnessRejectsPartialCheckpointOrFailure(){
        create((s,stream,cursor)->new SyncCoordinator.Page("{\"pages\":2,\"after\":\"private-cursor\"}",false,1));coordinator.selectShop(shop.getId());
        coordinator.tick();fast.remove().run();
        assertFalse(coordinator.isQueueCurrent(shop,Duration.ofSeconds(30)));
        var progress=coordinator.queueProgress(shop);assertEquals(2,progress.pages());assertFalse(progress.toString().contains("private-cursor"));
    }
    @Test void incompleteOzonDetailsPermitMembershipOnlyWithFreshCompleteOwnerBoundScan() throws Exception {
        var ozon=GtinWriteTestFixture.shop(Marketplace.OZON);
        create((s,stream,cursor)->{
            if(stream==SyncCoordinator.Stream.FBS_QUEUE){
                try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_fbs_queue_scans(shop_id,owner,completed_at,incomplete) VALUES(?,?,?,1)")){
                    p.setInt(1,s.getId());p.setString(2,com.vncode.app.integration.listing.ListingIdentity.fingerprint(s));p.setString(3,Instant.now().toString());p.executeUpdate();
                }
                return new SyncCoordinator.Page("",true,2,new SyncFailure("FBS_INCOMPLETE_POSTING",Duration.ofSeconds(3),false));
            }
            return SyncCoordinator.Page.complete(0);
        });
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS ozon_fbs_queue_scans(shop_id INTEGER PRIMARY KEY,owner TEXT NOT NULL,completed_at TEXT NOT NULL,incomplete INTEGER NOT NULL)");
        }
        coordinator.updateShops(List.of(ozon));coordinator.selectShop(ozon.getId());coordinator.tick();fast.remove().run();
        assertFalse(coordinator.isQueueCurrent(ozon,Duration.ofSeconds(30)));
        assertTrue(coordinator.isQueueMembershipCurrent(ozon,Duration.ofSeconds(30)));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE ozon_fbs_queue_scans SET completed_at='2000-01-01T00:00:00Z'");}
        assertFalse(coordinator.isQueueMembershipCurrent(ozon,Duration.ofSeconds(30)),"Old scan cannot prove the latest attempt");
    }
}
