package com.vncode.app.features.gs1;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.sync.SyncRunRepository;
import com.vncode.app.integration.gs1.Gs1Membership;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class Gs1SyncServiceTest {
    @TempDir Path directory;Shop shop;
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);}
    @AfterEach void clean(){System.clearProperty("vncode.appdata.dir");}
    private Gs1AutomaticSyncService.Result good(){return new Gs1AutomaticSyncService.Result(new Gs1Membership("7700000000","Synthetic",true,false,LocalDate.now().plusYears(1),List.of(),Instant.now(),Gs1Membership.CATALOG_SOURCE),"source@example.invalid",List.of(),"","");}
    @Test void oneReportContainsTheActualResultOfEachSourceAndNotifiesTheVisiblePane()throws Exception{
        var updates=new ArrayList<Gs1SyncService.Update>();try(var observer=Gs1SyncService.observe(shop,updates::add)){
            assertTrue(new Gs1SyncService(new Gs1AutomaticSyncService((s,settings,scope)->good())).sync(shop).complete());
        }
        var reports=new SyncRunRepository();var run=reports.list(shop.getId(),0,10).getFirst();assertEquals("gs1",run.kind());assertEquals("SUCCEEDED",run.state());assertEquals(2,reports.items(run.id(),0,10).size());assertTrue(reports.items(run.id(),0,10).stream().allMatch(item->item.state().equals("SUCCEEDED")));assertEquals("RUNNING",updates.getFirst().state());assertEquals("SUCCEEDED",updates.getLast().state());
    }
    @Test void aFailedCatalogReadAndSuccessfulBalanceReadProduceAPartialReport()throws Exception{
        new Gs1SyncService(new Gs1AutomaticSyncService((s,settings,scope)->new Gs1AutomaticSyncService.Result(null,"",List.of(),"NETWORK_TIMEOUT",""))).sync(shop);
        var reports=new SyncRunRepository();var run=reports.list(shop.getId(),0,10).getFirst();assertEquals("PARTIAL",run.state());var items=reports.items(run.id(),0,10);assertEquals("FAILED",items.get(0).state());assertEquals("NETWORK_TIMEOUT",items.get(0).error());assertEquals("SUCCEEDED",items.get(1).state());
    }
    @Test void malformedNullMembershipCannotBeReportedAsSuccessfulCatalogRead()throws Exception{
        var result=new Gs1SyncService(new Gs1AutomaticSyncService((s,settings,scope)->new Gs1AutomaticSyncService.Result(null,"",List.of(),"",""))).sync(shop);assertFalse(result.complete());assertEquals("GS1_CATALOG_RESPONSE_INCOMPLETE",result.catalogError());
    }
    @Test void wholeReadFailureTerminatesQueuedSourcesAndStoresOnlyASafeCode(){
        assertThrows(java.net.SocketTimeoutException.class,()->new Gs1SyncService(new Gs1AutomaticSyncService((s,settings,scope)->{throw new java.net.SocketTimeoutException("sensitive upstream payload");})).sync(shop));
        var reports=new SyncRunRepository();var run=reports.list(shop.getId(),0,10).getFirst();assertEquals("FAILED",run.state());assertEquals("NETWORK_TIMEOUT",run.error());assertTrue(reports.items(run.id(),0,10).stream().noneMatch(row->Set.of("QUEUED","RUNNING").contains(row.state())));
    }
    @Test void wholeReadFailureCannotMakeTheLastSuccessfulCacheLookFreshlySuccessful()throws Exception{
        new Gs1SyncService(new Gs1AutomaticSyncService((s,settings,scope)->good())).sync(shop);var before=new Gs1AutomaticSyncService().cached(shop).orElseThrow();
        assertThrows(java.net.SocketTimeoutException.class,()->new Gs1SyncService(new Gs1AutomaticSyncService((s,settings,scope)->{throw new java.net.SocketTimeoutException();})).sync(shop));
        var after=new Gs1AutomaticSyncService().cached(shop).orElseThrow();assertEquals("NETWORK_TIMEOUT",after.catalogError());assertEquals("NETWORK_TIMEOUT",after.balanceError());assertEquals(before.membership(),after.membership());assertEquals(before.catalogCheckedAt(),after.catalogCheckedAt());assertEquals(before.balanceCheckedAt(),after.balanceCheckedAt());
    }
    @Test void configurationFailureHasASettingsReportAndNeverCallsTheSource()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE znack_settings SET signer_tested_at=NULL");}
        var calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->new Gs1SyncService(new Gs1AutomaticSyncService((s,settings,scope)->{calls.incrementAndGet();return good();})).sync(shop));assertEquals(0,calls.get());var run=new SyncRunRepository().list(shop.getId(),0,10).getFirst();assertEquals("CHECKING_SETTINGS",run.step());assertEquals("FAILED",run.state());
    }
    @Test void repeatedRequestsForTheSameOwnerShareOneReadAndOneReport()throws Exception{
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();var joined=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var service=new Gs1SyncService(new Gs1AutomaticSyncService((s,settings,scope)->{calls.incrementAndGet();started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return good();}));
            var first=pool.submit(()->service.sync(shop));assertTrue(started.await(5,TimeUnit.SECONDS));var second=pool.submit(()->{joined.countDown();return service.sync(shop);});assertTrue(joined.await(5,TimeUnit.SECONDS));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!second.isDone()&&System.nanoTime()<end&&pool instanceof java.util.concurrent.ThreadPoolExecutor executor&&executor.getActiveCount()<2)Thread.onSpinWait();
            // Both calls overlap the same blocked upstream read; completing it unblocks all subscribers.
            Thread.sleep(100);release.countDown();assertTrue(first.get(5,TimeUnit.SECONDS).complete());assertTrue(second.get(5,TimeUnit.SECONDS).complete());assertEquals(1,calls.get());assertEquals(1,new SyncRunRepository().list(shop.getId(),0,10).size());
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void settingsFailureRemainsVisibleEvenWhenItsReportCannotBeWritten()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("UPDATE znack_settings SET signer_tested_at=NULL");
            s.execute("CREATE TRIGGER reject_gs1_failure BEFORE INSERT ON sync_run_events BEGIN SELECT RAISE(ABORT,'synthetic report failure'); END");
        }
        var failure=assertThrows(com.vncode.app.features.gtinsync.RegisteredGtinProof.UnverifiedOwnerException.class,
                ()->new Gs1SyncService(new Gs1AutomaticSyncService((s,settings,scope)->{throw new AssertionError("Must not call API");})).sync(shop));
        assertEquals(com.vncode.app.features.gtinsync.RegisteredGtinProof.OwnerFailure.SIGNATURE_NOT_TESTED,failure.reason());
        assertEquals(1,failure.getSuppressed().length);
    }
    @Test void fatalSourceFailureUnblocksAllCoalescedReaders()throws Exception{
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var joined=new CountDownLatch(1);var calls=new AtomicInteger();
        var original=new AssertionError("Synthetic source failure");
        try(var pool=Executors.newFixedThreadPool(2)){
            var service=new Gs1SyncService(new Gs1AutomaticSyncService((s,settings,scope)->{calls.incrementAndGet();started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));throw original;}));
            var first=pool.submit(()->service.sync(shop));assertTrue(started.await(5,TimeUnit.SECONDS));
            var second=pool.submit(()->{joined.countDown();return service.sync(shop);});
            try{assertTrue(joined.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->second.get(150,TimeUnit.MILLISECONDS));}
            finally{release.countDown();}
            assertSame(original,assertThrows(ExecutionException.class,()->first.get(5,TimeUnit.SECONDS)).getCause());
            assertSame(original,assertThrows(ExecutionException.class,()->second.get(5,TimeUnit.SECONDS)).getCause());
            assertEquals(1,calls.get());
        }
    }
    @Test void closingAViewObserverDoesNotCancelOrReceiveLaterWork()throws Exception{
        var updates=new AtomicInteger();var observer=Gs1SyncService.observe(shop,u->updates.incrementAndGet());observer.close();new Gs1SyncService(new Gs1AutomaticSyncService((s,settings,scope)->good())).sync(shop);assertEquals(0,updates.get());
    }
}
