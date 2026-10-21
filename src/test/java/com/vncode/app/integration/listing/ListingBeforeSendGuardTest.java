package com.vncode.app.integration.listing;

import com.google.gson.JsonObject;
import com.vncode.app.config.Database;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.MarketplaceRequestBudget;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ListingBeforeSendGuardTest {
    @TempDir Path root;Shop shop;
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Fixture','WILDBERRIES','fixture-token')");}
        shop=new Shop(1,"Fixture",Marketplace.WILDBERRIES,null,"fixture-token");
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void changedShopDuringSharedRequestBudgetWaitCannotSendOldCredentials()throws Exception{changedOwner(false);}
    @Test void reusedOwnerStatementStillDetectsCredentialRotationDuringBudgetWait()throws Exception{changedOwner(true);}
    private void changedOwner(boolean batchChecks)throws Exception {
        try(var server=new MockWebServer();var first=MarketplaceRequestBudget.acquire();var second=MarketplaceRequestBudget.acquire()){
            server.start();var client=new ListingHttpClient(shop,new OkHttpClient(),server.url("/"));
            var thread=new AtomicReference<Thread>();var guardCalls=new AtomicInteger();
            var executor=Executors.newSingleThreadExecutor(task->{var worker=new Thread(task,"listing-budget-fixture");thread.set(worker);return worker;});
            try {
                var pending=executor.submit(()->{
                    try(var database=Database.openBatchSession();var checks=batchChecks?ListingIdentity.batchChecks(shop):null){
                        return client.call("content/v2/cards/update",new JsonObject(),true,guardCalls::incrementAndGet);
                    }
                });
                awaitSemaphoreWait(thread);
                try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated-fixture' WHERE id=1");}
                first.close();
                var failure=assertThrows(ExecutionException.class,()->pending.get(3,TimeUnit.SECONDS));
                assertInstanceOf(SecurityException.class,failure.getCause());assertEquals(0,guardCalls.get());assertEquals(0,server.getRequestCount());
            }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
    @Test void licenseRevokedDuringBudgetWaitIsCheckedImmediatelyBeforeNetwork()throws Exception {
        var prior=LicenseRuntime.service();
        try(var denied=LicenseDomainFixture.denied(root.resolve("denied-license"));var server=new MockWebServer();var first=MarketplaceRequestBudget.acquire();var second=MarketplaceRequestBudget.acquire()){
            server.start();var client=new ListingHttpClient(shop,new OkHttpClient(),server.url("/"));
            var thread=new AtomicReference<Thread>();var guardCalls=new AtomicInteger();
            var executor=Executors.newSingleThreadExecutor(task->{var worker=new Thread(task,"listing-license-fixture");thread.set(worker);return worker;});
            try {
                var pending=executor.submit(()->client.call("content/v2/cards/update",new JsonObject(),true,()->{guardCalls.incrementAndGet();LicenseRuntime.require(Permission.GTIN);}));
                awaitSemaphoreWait(thread);LicenseRuntime.install(denied);first.close();
                var failure=assertThrows(ExecutionException.class,()->pending.get(3,TimeUnit.SECONDS));
                assertInstanceOf(LicenseDeniedException.class,failure.getCause());assertEquals(1,guardCalls.get());assertEquals(0,server.getRequestCount());
            }finally{LicenseRuntime.install(prior);executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
    @Test void http503RetryAfterZeroCannotReplayMutationWithinOneCall()throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(new MockResponse().setResponseCode(503).addHeader("Retry-After","0").setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            var client=new ListingHttpClient(shop,new OkHttpClient(),server.url("/"));
            assertThrows(java.io.IOException.class,()->client.call("v1/barcode/add",new JsonObject(),true,()->{}));assertEquals(1,server.getRequestCount());
        }
    }
    @Test void explicitHttp400PreservesTheReasonAndRedactsCredentials()throws Exception{
        try(var server=new MockWebServer()){
            server.start();server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"message\":\"Invalid material; token=fixture-secret; "+shop.getApiKey()+"\"}"));
            var client=new ListingHttpClient(shop,new OkHttpClient(),server.url("/"));
            var error=assertThrows(com.vncode.app.integration.listing.ListingRejectedException.class,()->client.call("content/v2/cards/update",new JsonObject(),true));
            assertTrue(error.getMessage().contains("Invalid material"));assertFalse(error.getMessage().contains("fixture-secret"));
            assertEquals(400,error.statusCode());assertEquals("WB_HTTP_400",error.code());assertEquals("WB_HTTP_400",com.vncode.app.features.sync.SyncFailure.of(error,0).code());
            assertFalse(error.getMessage().contains(shop.getApiKey()));assertEquals(1,server.getRequestCount());
        }
    }
    @Test void freshDispatchReadUsesNoHeldBackgroundPermitAndCanCallTheMarketplace()throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(new MockResponse().setBody("{}"));server.enqueue(new MockResponse().setBody("{}"));
            var client=new ListingHttpClient(shop,new OkHttpClient(),server.url("/"));
            var executor=Executors.newSingleThreadExecutor();var finalChecks=new AtomicInteger();
            try {
                var pending=executor.submit(()->{
                    try(var lane=MarketplaceRequestBudget.lane(true)) {
                        return client.call("content/v2/cards/update",new JsonObject(),true,()->{
                            assertEquals(0,MarketplaceRequestBudget.activeCount());
                            client.call("content/v2/get/cards/list",new JsonObject(),false);
                        },()->{assertEquals(1,MarketplaceRequestBudget.activeCount());finalChecks.incrementAndGet();});
                    }
                });
                pending.get(3,TimeUnit.SECONDS);
                assertEquals(1,finalChecks.get());assertEquals(2,server.getRequestCount());
                assertEquals("/content/v2/get/cards/list",server.takeRequest().getPath());
                assertEquals("/content/v2/cards/update",server.takeRequest().getPath());
            } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
    @Test void failedFreshDispatchReadKeepsItsCauseAndNeverSendsTheMutation()throws Exception {
        try(var server=new MockWebServer()) {
            server.start();var client=new ListingHttpClient(shop,new OkHttpClient(),server.url("/"));
            var failure=new java.io.IOException("fixture current card unavailable");var finalChecks=new AtomicInteger();
            var actual=assertThrows(java.io.IOException.class,()->client.call("content/v2/cards/update",new JsonObject(),true,()->{throw failure;},finalChecks::incrementAndGet));
            assertSame(failure,actual);assertEquals(0,finalChecks.get());assertEquals(0,server.getRequestCount());
            ListingTransport fixture=(path,body,mutation)->{fail("A failed current read cannot reach the fixture mutation");return null;};
            assertSame(failure,assertThrows(java.io.IOException.class,()->fixture.call("content/v2/cards/update",new JsonObject(),true,()->{throw failure;},finalChecks::incrementAndGet)));
        }
    }
    private static void awaitSemaphoreWait(AtomicReference<Thread> thread) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<deadline){var worker=thread.get();if(worker!=null&&worker.getState()==Thread.State.WAITING)return;Thread.onSpinWait();}
        fail("Fixture did not reach the shared request-budget wait");
    }
}
