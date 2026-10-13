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
        repository.upsertProducts(List.of(new Product(GTIN,"Test",null,null,null,null,null)));
    }
    @AfterEach void cleanup(){ZnackPurchaseCoordinator.cancelPendingPolls();System.clearProperty("vncode.appdata.dir");}

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
            @Override public KizOrder buy(Settings s,String gtin,int quantity){
                buys.incrementAndGet();long id=repository.createDraft(gtin,quantity);
                repository.updateOrder(id,"accepted-order","CREATED",OrderStatus.SUBMITTED,null);
                return repository.findOrder(id).orElseThrow();
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
                assertThrows(LicenseDeniedException.class,()->coordinator.advance(Settings.empty(),pipeline));
                var paused=repository.findPipeline(pipeline).orElseThrow();
                assertEquals(PurchaseStage.VALIDATING,paused.stage());assertEquals("LICENSE_REQUIRED",paused.errorMessage());
                assertNull(paused.orderId());assertEquals(0,buys.get());
                LicenseRuntime.install(prior);coordinator.resume(Settings.empty());assertEquals(0,buys.get());
                coordinator.advance(Settings.empty(),pipeline);
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
            @Override public KizOrder buy(Settings s,String gtin,int quantity){throw new AssertionError("Recovery cannot create an order");}
        };
        var codes=new ZnackKizCodeService(null,null,repository){
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
                coordinator.advance(Settings.empty(),pipeline);
                assertEquals(1,reads.get());assertEquals(1,repository.findCodes(orderId).size());
                assertEquals(PurchaseStage.COMPLETED,repository.findPipeline(pipeline).orElseThrow().stage());
                assertEquals("accepted-order",repository.findOrder(orderId).orElseThrow().externalOrderId());
            }finally{LicenseRuntime.install(prior);}
        }
    }
}
