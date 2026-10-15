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
    @Test void manualRefreshCannotBypassRateLimitCooldown(){
        create((s,stream,cursor)->{throw new IllegalStateException("OZON_RATE_LIMIT");});
        var store=new SyncStreamStore();var now=Instant.now();var state=store.ensure(shop,"FBS_QUEUE",now);
        store.finish(shop,"FBS_QUEUE",state,"cursor",false,now,now.plusSeconds(120),new SyncFailure("OZON_RATE_LIMIT",Duration.ofSeconds(120),false));
        coordinator.request(shop.getId(),SyncCoordinator.Stream.FBS_QUEUE);coordinator.tick();
        assertTrue(coordinator.health(shop.getId()).stream().noneMatch(h->h.stream()==SyncCoordinator.Stream.FBS_QUEUE&&h.running()));
    }
}
