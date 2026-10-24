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
    @Test void explicitKn3ReplacementUsesOnePutAndWaitsForRemoteValidation()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());
        try{
            com.vncode.app.config.Database.initDatabase();var shop=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);var repository=new com.vncode.app.features.kiz.status.Kn3Repository();var old=new com.vncode.app.features.kiz.status.Kn3Mark("101","","","old",com.vncode.app.features.kiz.status.Kn3Mark.State.REJECTED,"sgtinRetired");repository.observe(shop,"101",List.of(old));repository.begin(shop,old,"new","{}");
            var writes=new AtomicInteger();var coordinator=new KizAttachmentCoordinator(Runnable::run,new KizAttachmentCoordinator.AttachmentClient(){
                public KizService.RemoveMetaResult remove(String token,long order){throw new AssertionError("Do not remove an existing rejected value before the replacement PUT");}
                public KizService.AttachCodeResult attach(String token,long order,String code){assertEquals("new",code);writes.incrementAndGet();return new KizService.AttachCodeResult(true,204,"");}
                public Map<Long,KizService.SgtinMetadata> metadata(String token,List<Long> ids){return Map.of(101L,new KizService.SgtinMetadata(true,"old","sgtinRetired"));}
            });coordinator.enqueue(shop,"KN3:101","KN3",List.of(new OrderExportWorkflow.KizAttachmentAssignment(101L,"new",null,true,old.fingerprint())));assertEquals(1,writes.get());assertEquals(com.vncode.app.features.kiz.status.Kn3Mark.State.PENDING,com.vncode.app.features.kiz.status.Kn3Mark.summary(repository.read(shop,"101")));
        }finally{System.clearProperty("vncode.appdata.dir");}
    }
    @Test void aCodeAcceptedWhileTheReplacementWaitsInQueueCannotBeOverwritten()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());
        try{
            com.vncode.app.config.Database.initDatabase();var shop=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);var old=new com.vncode.app.features.kiz.status.Kn3Mark("101","","","old",com.vncode.app.features.kiz.status.Kn3Mark.State.REJECTED,"sgtinRetired");new com.vncode.app.features.kiz.status.Kn3Repository().begin(shop,old,"new","{}");var pending=new ArrayList<Runnable>();
            var coordinator=new KizAttachmentCoordinator(pending::add,new KizAttachmentCoordinator.AttachmentClient(){public KizService.RemoveMetaResult remove(String token,long order){throw new AssertionError("Accepted code must remain");}public KizService.AttachCodeResult attach(String token,long order,String code){throw new AssertionError("Accepted code must remain");}public Map<Long,KizService.SgtinMetadata> metadata(String token,List<Long> ids){return Map.of(101L,new KizService.SgtinMetadata(true,"old","sgtinIntroduced"));}});
            coordinator.enqueue(shop,"KN3:101","KN3",List.of(new OrderExportWorkflow.KizAttachmentAssignment(101L,"new",null,true,old.fingerprint())));pending.getFirst().run();assertEquals("KIZ_REPLACEMENT_CHANGED",coordinator.getAttachmentError(shop.getId(),101));
        }finally{System.clearProperty("vncode.appdata.dir");}
    }

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

    @Test void tenShopsWithThousandOrdersStayBoundedScopedAndThrottled()throws Exception {
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
            for(int shop=1;shop<=10;shop++){assertEquals(1000,last.get(shop).completed());assertEquals(1000,last.get(shop).successfulKizCodes().size());assertTrue(updates.get(shop)<=27,"Progress must have at most 25 intermediate updates plus start/end");}
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test void waitingShopsRotateWhileAnotherWriterIsBlocked()throws Exception{
        // A reserved writer may be descheduled by the OS. Test queue fairness with one
        // writer deliberately held, instead of asserting callback order across CPU threads.
        var scheduled=new CopyOnWriteArrayList<Runnable>();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var order=new CopyOnWriteArrayList<Integer>();var calls=new AtomicInteger();
        try(var worker=Executors.newSingleThreadExecutor()){
            var coordinator=new KizAttachmentCoordinator(scheduled::add,new KizAttachmentCoordinator.AttachmentClient(){
                public KizService.RemoveMetaResult remove(String token,long id){throw new AssertionError();}
                public KizService.AttachCodeResult attach(String token,long id,String code)throws java.io.IOException{
                    int shop=Integer.parseInt(token.substring(6));
                    if(shop==1&&id==1){entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Held writer not released");}
                        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);}}
                    order.add(shop);calls.incrementAndGet();return new KizService.AttachCodeResult(true,204,"");
                }
            });
            try{
                for(int shop=1;shop<=10;shop++)coordinator.enqueue(new Shop(shop,"Shop "+shop,"token-"+shop),"SUP-"+shop,"Supply",assignments(shop,100));
                assertEquals(2,scheduled.size());var held=worker.submit(scheduled.getFirst());assertTrue(entered.await(5,TimeUnit.SECONDS));
                scheduled.get(1).run();
                for(int shop=2;shop<=10;shop++){assertTrue(order.indexOf(shop)<20,"A waiting shop must run before a large queue drains");assertFalse(coordinator.hasActiveJobForShop(shop));}
                assertTrue(coordinator.hasActiveJobForShop(1));assertEquals(900,calls.get());
                release.countDown();held.get(10,TimeUnit.SECONDS);assertEquals(1000,calls.get());assertFalse(coordinator.hasActiveJobs());
            }finally{release.countDown();}
        }
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

    @Test void processingStatusRejectionIsAVisibleOrderErrorRatherThanASuccessfulSkip(){
        var last=new AtomicReference<KizAttachmentCoordinator.KizAttachmentProgress>();
        var coordinator=new KizAttachmentCoordinator(Runnable::run,new KizAttachmentCoordinator.AttachmentClient(){
            public KizService.RemoveMetaResult remove(String token,long id){throw new AssertionError();}
            public KizService.AttachCodeResult attach(String token,long id,String code){return new KizService.AttachCodeResult(false,409,"FailedToUpdateMeta: Processing status "+SECRET);}
        });
        coordinator.addListener(last::set);coordinator.enqueue(new Shop(7,"Main","token"),"SUP","Supply",assignments(7,1));
        assertEquals("KIZ_ORDER_STATUS",coordinator.getAttachmentError(7,1));assertEquals(0,last.get().completed());assertFalse(last.get().failures().isEmpty());assertFalse(last.get().failures().toString().contains(SECRET));
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

    @Test void repeatedSellerCodesReachTheQueueAsSeparateStockUnits()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());
        try{
            com.vncode.app.config.Database.initDatabase();
            var shop=new Shop(1,"Seller","fixture");
            try(var c=com.vncode.app.config.Database.getConnection();var st=c.createStatement()){
                st.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Seller','fixture')");
                st.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,synced_at) VALUES(1,101,'Synthetic','now')");
            }
            var categories=new com.vncode.app.features.kiz.KizStockCategoryRepository();var category=categories.create(shop,"Synthetic");
            categories.assignProducts(shop,category.id(),List.of(new com.vncode.app.features.products.ProductKey(1,shop.getMarketplace(),"101","")));
            var file=directory.resolve("same.txt");java.nio.file.Files.write(file,List.of("0100000000000017210000000000001","0100000000000017210000000000001"));
            new com.vncode.app.features.kiz.KizWarehouseRepository().commitToCategory(shop,com.vncode.app.features.kiz.KizImportReader.read(file),category.id());
            var units=new com.vncode.app.features.kiz.KizCategoryAllocator().reservePartial(shop,List.of(new com.vncode.app.features.kiz.KizCategoryAllocator.Demand("orders","101",2))).get("orders");
            var queued=new ArrayList<Runnable>();
            var coordinator=new KizAttachmentCoordinator(queued::add,new KizAttachmentCoordinator.AttachmentClient(){
                public KizService.RemoveMetaResult remove(String token,long order){throw new AssertionError("No remote writes in this queue test");}
                public KizService.AttachCodeResult attach(String token,long order,String code){throw new AssertionError("No remote writes in this queue test");}
            });
            coordinator.enqueue(shop,"shipment","shipment",List.of(new OrderExportWorkflow.KizAttachmentAssignment(101L,units.get(0).getCode(),units.get(0),false),new OrderExportWorkflow.KizAttachmentAssignment(102L,units.get(1).getCode(),units.get(1),false)));
            assertEquals(1,queued.size());
        }finally{System.clearProperty("vncode.appdata.dir");}
    }
}
