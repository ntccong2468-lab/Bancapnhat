package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.znack.ZnackModels.*;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ZnackLicenseBoundaryTest {
    private static final String GTIN="04601234567890";
    @TempDir Path temp;
    ZnackRepository repository;
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Test','fixture')");}
        repository=new ZnackRepository(new ShopContext(1,"Test"));
        repository.upsertProducts(List.of(new Product(GTIN,"Test",null,"CONFORMITY_DECLARATION","FIXTURE-PERMIT","01.01.2026",null)));
        repository.saveSettings(fixtureSettings());
    }
    @AfterEach void cleanup(){ZnackPurchaseCoordinator.cancelPendingPolls();ZnackSigningSession.resetForTests();System.clearProperty("vncode.appdata.dir");}

    private Settings fixtureSettings(){return new Settings("http://127.0.0.1:9","http://127.0.0.1:9","fixture-oms","fixture-connection","7700000001","","","","fixture-certificate","[]","","","",true,"","[]","{}",java.time.Instant.parse("2026-01-01T00:00:00Z"));}

    @Test void deniedNewPurchaseDoesNotCreatePipeline()throws Exception {
        var prior=LicenseRuntime.service();
        try(var denied=LicenseDomainFixture.denied(temp.resolve("denied-license"))) {
            LicenseRuntime.install(denied);
            try {
                var coordinator=new ZnackPurchaseCoordinator(repository,null,null,null);
                String key=UUID.randomUUID().toString();
                assertThrows(LicenseDeniedException.class,()->coordinator.enqueue(Settings.empty(),GTIN,1,key));
                assertTrue(repository.findPipelineByRequestKey(key).isEmpty());assertTrue(repository.findActivePipelines().isEmpty());
            }finally{LicenseRuntime.install(prior);}
        }
    }

    @Test void revokedQueuedPurchaseIsPausedUntilExplicitAdvance()throws Exception {
        var buys=new AtomicInteger();
        var orders=new ZnackKizOrderService(null,null,null,repository){
            @Override public ZnackPurchaseBatchStore.Batch advanceBatch(Settings settings,long batchId){
                buys.incrementAndGet();var store=new ZnackPurchaseBatchStore(repository);var batch=store.find(batchId).orElseThrow();
                store.savePayload(batchId,Wb1326PurchasePersistenceTest.payload(batch).toString());assertTrue(store.beginSubmission(batchId));
                store.acceptRemoteOrder(batchId,"accepted-order");return store.find(batchId).orElseThrow();
            }
        };
        var coordinator=new ZnackPurchaseCoordinator(repository,orders,null,null){
            @Override public void validatePrerequisites(Settings s,String gtin,int quantity){}
            @Override void schedule(long id){}
        };
        long pipeline=repository.enqueuePipeline(GTIN,1,UUID.randomUUID().toString());
        var prior=LicenseRuntime.service();
        try(var denied=LicenseDomainFixture.denied(temp.resolve("denied-license"))) {
            LicenseRuntime.install(denied);
            try {
                assertThrows(LicenseDeniedException.class,()->coordinator.advance(fixtureSettings(),pipeline));
                var paused=repository.findPipeline(pipeline).orElseThrow();
                assertEquals(PurchaseStage.VALIDATING,paused.stage());assertEquals("LICENSE_REQUIRED",paused.errorMessage());
                assertNull(paused.orderId());assertEquals(0,buys.get());
                LicenseRuntime.install(prior);coordinator.resume(fixtureSettings());assertEquals(0,buys.get());
                coordinator.advance(fixtureSettings(),pipeline);
                assertEquals(1,buys.get());assertEquals(PurchaseStage.POLLING_ORDER,repository.findPipeline(pipeline).orElseThrow().stage());
            }finally{LicenseRuntime.install(prior);}
        }
    }

    @Test void acceptedOrderReadbackAndCodeRetrievalRemainAvailableWhileDenied()throws Exception {
        long pipeline=repository.enqueuePipeline(GTIN,1,UUID.randomUUID().toString());
        long orderId=repository.createDraft(GTIN,1);repository.updateOrder(orderId,"accepted-order","CREATED",OrderStatus.SUBMITTED,null);
        repository.updatePipeline(pipeline,orderId,PurchaseStage.POLLING_ORDER,null);
        var reads=new AtomicInteger();
        var orders=new ZnackKizOrderService(null,null,null,repository){
            @Override public KizOrder refresh(Settings s,long id){
                reads.incrementAndGet();repository.updateOrder(id,null,"READY",OrderStatus.CODES_READY,null);
                return repository.findOrder(id).orElseThrow();
            }
            @Override public ZnackPurchaseBatchStore.Batch advanceBatch(Settings s,long batch){throw new AssertionError("Recovery cannot create an order");}
            @Override public java.util.OptionalInt availableCodes(long id){return java.util.OptionalInt.of(1);}
        };
        var codes=new ZnackKizCodeService(null,null,repository){
            @Override public int download(Settings s,long id,int available){return download(s,id);}
            @Override public int download(Settings s,long id){
                int inserted=repository.insertCodes(id,GTIN,new DownloadedCodes(List.of("accepted-code"),"accepted-block"));
                repository.updateOrder(id,null,"READY",OrderStatus.CODES_DOWNLOADED,null);return inserted;
            }
        };
        var coordinator=new ZnackPurchaseCoordinator(repository,orders,codes,null){@Override void schedule(long id){}};
        var prior=LicenseRuntime.service();
        try(var denied=LicenseDomainFixture.denied(temp.resolve("denied-license"))) {
            LicenseRuntime.install(denied);
            try {
                coordinator.advance(fixtureSettings(),pipeline);
                assertEquals(1,reads.get());assertEquals(1,repository.findCodes(orderId).size());
                assertEquals(PurchaseStage.WAITING_INTRODUCTION_READINESS,repository.findPipeline(pipeline).orElseThrow().stage());
                assertEquals("accepted-order",repository.findOrder(orderId).orElseThrow().externalOrderId());
            }finally{LicenseRuntime.install(prior);}
        }
    }
}
