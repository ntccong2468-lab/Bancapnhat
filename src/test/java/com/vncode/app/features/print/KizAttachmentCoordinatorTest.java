package com.vncode.app.features.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vncode.app.features.kiz.KizService;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseGate;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.models.Kiz;
import com.vncode.app.models.Shop;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KizAttachmentCoordinatorTest {
    private static final String SECRET = "upstream-secret-response-body";
    @TempDir Path directory;

    @Test
    void deniedEnqueueCreatesNoBackgroundJob() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        var coordinator = new KizAttachmentCoordinator(queued::add, new KizAttachmentCoordinator.AttachmentClient() {
            public KizService.RemoveMetaResult remove(String token, long orderId) { throw new AssertionError(); }
            public KizService.AttachCodeResult attach(String token, long orderId, String code) { throw new AssertionError(); }
        });
        var prior = LicenseRuntime.service();
        try (var denied = LicenseDomainFixture.denied(directory.resolve("denied"))) {
            LicenseRuntime.install(denied);
            assertThrows(LicenseGate.LicenseDeniedException.class, () -> coordinator.enqueue(
                    new Shop(7, "Main", "token"), "SUP-1", "Supply", List.of(
                            new OrderExportWorkflow.KizAttachmentAssignment(101L, "KIZ-101", null, false))));
            assertTrue(queued.isEmpty());
            assertFalse(coordinator.hasActiveJobs());
        } finally { LicenseRuntime.install(prior); }
    }

    @Test
    void queuedAttachmentRechecksRightsBeforeSendingAndDoesNotRemoveOldMetadata() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        List<KizAttachmentCoordinator.KizAttachmentProgress> progress = new ArrayList<>();
        var coordinator = new KizAttachmentCoordinator(queued::add, new KizAttachmentCoordinator.AttachmentClient() {
            public KizService.RemoveMetaResult remove(String token, long orderId) { throw new AssertionError("Denied queue cannot remove metadata"); }
            public KizService.AttachCodeResult attach(String token, long orderId, String code) { throw new AssertionError("Denied queue cannot send metadata"); }
        });
        coordinator.addListener(progress::add);
        coordinator.enqueue(new Shop(7, "Main", "token"), "SUP-1", "Supply", List.of(
                new OrderExportWorkflow.KizAttachmentAssignment(101L, "KIZ-101", new Kiz(1, "KIZ-101", "r"), true)));
        var prior = LicenseRuntime.service();
        try (var denied = LicenseDomainFixture.denied(directory.resolve("denied"))) {
            LicenseRuntime.install(denied);
            queued.getFirst().run();
            assertFalse(coordinator.hasActiveJobs());
            assertEquals(0, progress.getLast().completed());
            assertEquals("LICENSE_REQUIRED", coordinator.getAttachmentError(101L));
            assertTrue(progress.getLast().successfulKizCodes().isEmpty());
        } finally { LicenseRuntime.install(prior); }
    }

    @Test
    void acceptedAttachmentResultSurvivesRevocationAndNextItemIsNotSent() throws Exception {
        List<KizAttachmentCoordinator.KizAttachmentProgress> progress = new ArrayList<>();
        var calls = new AtomicInteger();
        var prior = LicenseRuntime.service();
        try (var denied = LicenseDomainFixture.denied(directory.resolve("denied"))) {
            var coordinator = new KizAttachmentCoordinator(Runnable::run, new KizAttachmentCoordinator.AttachmentClient() {
                public KizService.RemoveMetaResult remove(String token, long orderId) { throw new AssertionError("Unexpected remove"); }
                public KizService.AttachCodeResult attach(String token, long orderId, String code) {
                    calls.incrementAndGet();
                    LicenseRuntime.install(denied);
                    return new KizService.AttachCodeResult(true, 204, "");
                }
            });
            coordinator.addListener(progress::add);
            coordinator.enqueue(new Shop(7, "Main", "token"), "SUP-1", "Supply", List.of(
                    new OrderExportWorkflow.KizAttachmentAssignment(101L, "KIZ-101", new Kiz(1, "KIZ-101", "r1"), false),
                    new OrderExportWorkflow.KizAttachmentAssignment(102L, "KIZ-102", new Kiz(2, "KIZ-102", "r2"), false)));
            assertEquals(1, calls.get());
            assertEquals(1, progress.getLast().completed());
            assertEquals(List.of("KIZ-101"), progress.getLast().successfulKizCodes());
            assertEquals("LICENSE_REQUIRED", coordinator.getAttachmentError(102L));
        } finally { LicenseRuntime.install(prior); }
    }

    @Test
    void runsAttachmentsOnTheInjectedExecutorAndPublishesSafeSuccessProgress() {
        List<Runnable> queued = new ArrayList<>();
        List<KizAttachmentCoordinator.KizAttachmentProgress> progress = new ArrayList<>();
        KizAttachmentCoordinator coordinator = new KizAttachmentCoordinator(
                queued::add,
                new KizAttachmentCoordinator.AttachmentClient() {
                    @Override
                    public KizService.RemoveMetaResult remove(String token, long orderId) {
                        throw new AssertionError("remove should not be called");
                    }

                    @Override
                    public KizService.AttachCodeResult attach(String token, long orderId, String code) {
                        assertEquals("token", token);
                        assertEquals(101L, orderId);
                        assertEquals("KIZ-101", code);
                        return new KizService.AttachCodeResult(true, 204, "");
                    }
                });
        coordinator.addListener(progress::add);
        Shop shop = new Shop(7, "Main", "token");
        Kiz source = new Kiz(1, "KIZ-101", "reservation-101");

        coordinator.enqueue(shop, "SUP-1", "Supply", List.of(
                new OrderExportWorkflow.KizAttachmentAssignment(101L, "KIZ-101", source, false)));

        assertEquals(1, queued.size());
        assertTrue(coordinator.hasActiveJobForSupply(7, "SUP-1"));
        queued.getFirst().run();

        assertFalse(coordinator.hasActiveJobs());
        KizAttachmentCoordinator.KizAttachmentProgress completed = progress.getLast();
        assertFalse(completed.active());
        assertEquals(1, completed.completed());
        assertEquals(List.of("KIZ-101"), completed.successfulKizCodes());
        assertTrue(completed.failures().isEmpty());
        assertNull(coordinator.getAttachmentError(101L));
    }

    @Test
    void redactsUpstreamBodiesFromFailureStateAndListenerProgress() {
        List<KizAttachmentCoordinator.KizAttachmentProgress> progress = new ArrayList<>();
        KizAttachmentCoordinator coordinator = new KizAttachmentCoordinator(
                Runnable::run,
                new KizAttachmentCoordinator.AttachmentClient() {
                    @Override
                    public KizService.RemoveMetaResult remove(String token, long orderId) {
                        return new KizService.RemoveMetaResult(false, 500, SECRET);
                    }

                    @Override
                    public KizService.AttachCodeResult attach(String token, long orderId, String code) {
                        throw new AssertionError("attach should not run after remove failure");
                    }
                });
        coordinator.addListener(progress::add);

        coordinator.enqueue(new Shop(7, "Main", "token"), "SUP-1", "Supply", List.of(
                new OrderExportWorkflow.KizAttachmentAssignment(
                        101L, "KIZ-101", new Kiz(1, "KIZ-101", "reservation-101"), true)));

        String stored = coordinator.getAttachmentError(101L);
        assertEquals("KIZ_RECONCILIATION_REQUIRED",stored);
        assertFalse(stored.contains(SECRET));
        assertFalse(progress.toString().contains(SECRET));
        assertFalse(coordinator.hasActiveJobs());
    }

    @Test void duplicateSupplyAndOverlappingOrderCannotOverwriteExistingOwnership() {
        List<Runnable> queued=new ArrayList<>();var calls=new AtomicInteger();
        var coordinator=new KizAttachmentCoordinator(queued::add,successClient(calls));
        var shop=new Shop(7,"Main","token");var assignment=assignments(7,100);
        coordinator.enqueue(shop,"SUP-1","Supply",assignment);
        assertThrows(IllegalStateException.class,()->coordinator.enqueue(shop,"SUP-1","Supply",assignment));
        assertThrows(IllegalStateException.class,()->coordinator.enqueue(shop,"SUP-2","Supply",assignment));
        assertEquals(1,queued.size());assertTrue(coordinator.hasActiveJobForSupply(7,"SUP-1"));
        queued.getFirst().run();assertEquals(100,calls.get());assertFalse(coordinator.hasActiveJobs());
    }

    @Test void tenShopsWithThousandOrdersStayBoundedFairScopedAndThrottled()throws Exception {
        var executor=Executors.newFixedThreadPool(4);var started=new CountDownLatch(2);var release=new CountDownLatch(1);var finished=new CountDownLatch(10);
        var inFlight=new AtomicInteger();var peak=new AtomicInteger();var calls=new AtomicInteger();
        Map<String,AtomicInteger> perShop=new ConcurrentHashMap<>();Set<String> sent=ConcurrentHashMap.newKeySet();List<Integer> orderOfShops=new CopyOnWriteArrayList<>();
        Map<Integer,Integer> updates=new ConcurrentHashMap<>();Map<Integer,KizAttachmentCoordinator.KizAttachmentProgress> last=new ConcurrentHashMap<>();
        try {
            var coordinator=new KizAttachmentCoordinator(executor,new KizAttachmentCoordinator.AttachmentClient(){
                public KizService.RemoveMetaResult remove(String token,long id){throw new AssertionError();}
                public KizService.AttachCodeResult attach(String token,long id,String code)throws java.io.IOException {
                    int shop=Integer.parseInt(token.substring(6));int active=inFlight.incrementAndGet();peak.accumulateAndGet(active,Math::max);
                    AtomicInteger shopActive=perShop.computeIfAbsent(token,key->new AtomicInteger());assertEquals(1,shopActive.incrementAndGet());
                    try {
                        orderOfShops.add(shop);
                        started.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Workers did not release");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException("FIXTURE_CANCELLED");}
                        assertTrue(sent.add(shop+":"+id));assertEquals("KIZ-"+shop+"-"+id,code);calls.incrementAndGet();return new KizService.AttachCodeResult(true,204,"");
                    } finally {shopActive.decrementAndGet();inFlight.decrementAndGet();}
                }
            });
            coordinator.addListener(p->{updates.merge(p.shopId(),1,Integer::sum);last.put(p.shopId(),p);if(!p.active())finished.countDown();});
            for(int shop=1;shop<=10;shop++)coordinator.enqueue(new Shop(shop,"Shop "+shop,"token-"+shop),"SUP-"+shop,"Supply",assignments(shop,1000));
            assertTrue(started.await(5,TimeUnit.SECONDS));assertEquals(2,inFlight.get());release.countDown();
            assertTrue(finished.await(20,TimeUnit.SECONDS));assertEquals(10000,calls.get());assertEquals(10000,sent.size());assertEquals(2,peak.get());assertFalse(coordinator.hasActiveJobs());
            for(int shop=1;shop<=10;shop++){assertEquals(1000,last.get(shop).completed());assertEquals(1000,last.get(shop).successfulKizCodes().size());assertTrue(updates.get(shop)<=27,"Progress must have at most 25 intermediate updates plus start/end");assertTrue(orderOfShops.indexOf(shop)<50,"Waiting shop "+shop+" must start before a large supply monopolizes workers");}
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test void cancellationKeepsAcceptedFirstUnitAndDoesNotSendRemainingUnits()throws Exception {
        var executor=Executors.newFixedThreadPool(2);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);var calls=new AtomicInteger();
        var last=new AtomicReference<KizAttachmentCoordinator.KizAttachmentProgress>();
        try {
            var coordinator=new KizAttachmentCoordinator(executor,new KizAttachmentCoordinator.AttachmentClient(){
                public KizService.RemoveMetaResult remove(String token,long id){throw new AssertionError();}
                public KizService.AttachCodeResult attach(String token,long id,String code)throws java.io.IOException{calls.incrementAndGet();entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException("FIXTURE_CANCELLED");}return new KizService.AttachCodeResult(true,204,"");}
            });
            coordinator.addListener(p->{last.set(p);if(!p.active())finished.countDown();});
            coordinator.enqueue(new Shop(7,"Main","token"),"SUP-1","Supply",assignments(7,1000));
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(coordinator.cancel(7,"SUP-1"));release.countDown();assertTrue(finished.await(5,TimeUnit.SECONDS));
            assertEquals(1,calls.get());assertEquals(1,last.get().completed());assertEquals(1,last.get().successfulKizCodes().size());assertFalse(coordinator.hasActiveJobs());
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test void errorsWithSameOrderIdCannotLeakOrClearAcrossShopsAndRateLimitStopsJob() {
        var coordinator=new KizAttachmentCoordinator(Runnable::run,new KizAttachmentCoordinator.AttachmentClient(){
            public KizService.RemoveMetaResult remove(String token,long id){throw new AssertionError();}
            public KizService.AttachCodeResult attach(String token,long id,String code){return new KizService.AttachCodeResult(false,429,SECRET);}
        });
        coordinator.enqueue(new Shop(7,"A","a"),"SUP-1","Supply",assignments(7,100));
        coordinator.enqueue(new Shop(8,"B","b"),"SUP-1","Supply",assignments(8,100));
        assertEquals("KIZ_HTTP_429",coordinator.getAttachmentError(7,1));assertEquals("KIZ_HTTP_429",coordinator.getAttachmentError(8,1));
        assertNull(coordinator.getAttachmentError(1));coordinator.clearError(1);coordinator.clearErrors();assertEquals("KIZ_HTTP_429",coordinator.getAttachmentError(8,1));
        coordinator.clearErrors(7);assertNull(coordinator.getAttachmentError(7,1));assertEquals("KIZ_HTTP_429",coordinator.getAttachmentError(8,1));assertNull(coordinator.getAttachmentError(8,2));
    }

    @Test void timeoutIsCheckpointedAndOnlyExplicitReadReconciliationClearsAmbiguity()throws Exception {
        var calls=new AtomicInteger();var journal=new KizAttachmentCoordinator.MemoryJournal();
        var coordinator=new KizAttachmentCoordinator(Runnable::run,new KizAttachmentCoordinator.AttachmentClient(){
            public KizService.RemoveMetaResult remove(String token,long id){throw new AssertionError();}
            public KizService.AttachCodeResult attach(String token,long id,String code)throws java.io.IOException{calls.incrementAndGet();throw new java.io.IOException(SECRET);}
            public Map<Long,KizService.SgtinMetadata> metadata(String token,List<Long> ids){return Map.of(1L,new KizService.SgtinMetadata(true,"KIZ-7-1"));}
        },journal,System::nanoTime);
        var shop=new Shop(7,"Main","token");var one=assignments(7,1);
        coordinator.enqueue(shop,"SUP-1","Supply",one);assertEquals(1,calls.get());
        coordinator.enqueue(shop,"SUP-1","Supply",one);assertEquals(1,calls.get(),"Uncertain writes must never be replayed");
        assertEquals(KizAttachmentCoordinator.Reconciliation.CONFIRMED,coordinator.reconcile(shop,1,"KIZ-7-1"));
        coordinator.enqueue(shop,"SUP-1","Supply",one);assertEquals(1,calls.get());assertNull(coordinator.getAttachmentError(7,1));
    }

    @Test void persistedShopDeletionWhileQueuedStopsWithoutAnySellerWrite()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());
        try {
            com.vncode.app.config.Database.initDatabase();
            try(var c=com.vncode.app.config.Database.getConnection();var st=c.createStatement()){st.execute("INSERT INTO shops(id,name,api_key) VALUES(7,'Main','fixture-token')");}
            List<Runnable> queued=new ArrayList<>();var calls=new AtomicInteger();
            var coordinator=new KizAttachmentCoordinator(queued::add,new KizAttachmentCoordinator.AttachmentClient(){
                public void requireCurrentShop(int id,String token)throws java.io.IOException{KizService.requireCurrentShop(id,token);}
                public KizService.RemoveMetaResult remove(String token,long id){calls.incrementAndGet();throw new AssertionError();}
                public KizService.AttachCodeResult attach(String token,long id,String code){calls.incrementAndGet();throw new AssertionError();}
            });
            coordinator.enqueue(new Shop(7,"Main","fixture-token"),"SUP-1","Supply",assignments(7,100));
            try(var c=com.vncode.app.config.Database.getConnection();var st=c.createStatement()){st.execute("DELETE FROM shops WHERE id=7");}
            queued.getFirst().run();assertEquals(0,calls.get());assertEquals("SHOP_CREDENTIALS_CHANGED",coordinator.getAttachmentError(7,1));assertFalse(coordinator.hasActiveJobs());
        } finally {System.clearProperty("vncode.appdata.dir");}
    }

    private static List<OrderExportWorkflow.KizAttachmentAssignment> assignments(int shop,int quantity){
        List<OrderExportWorkflow.KizAttachmentAssignment> result=new ArrayList<>();
        for(long id=1;id<=quantity;id++){String code="KIZ-"+shop+"-"+id;result.add(new OrderExportWorkflow.KizAttachmentAssignment(id,code,new Kiz((int)id,code,"reservation-"+shop),false));}return result;
    }
    private static KizAttachmentCoordinator.AttachmentClient successClient(AtomicInteger calls){return new KizAttachmentCoordinator.AttachmentClient(){
        public KizService.RemoveMetaResult remove(String token,long id){throw new AssertionError();}
        public KizService.AttachCodeResult attach(String token,long id,String code){calls.incrementAndGet();return new KizService.AttachCodeResult(true,204,"");}
    };}
}
