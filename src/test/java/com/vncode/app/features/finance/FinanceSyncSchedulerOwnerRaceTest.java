package com.vncode.app.features.finance;

import com.vncode.app.config.Database;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseService;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.finance.OzonFinanceApiClient;
import com.vncode.app.integration.wb.finance.WbAdvertisingApiClient;
import com.vncode.app.integration.wb.finance.WbFinanceApiClient;
import com.vncode.app.models.Shop;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class FinanceSyncSchedulerOwnerRaceTest {
    @TempDir Path directory;
    private LicenseService previous,fixture;
    private MockWebServer server;
    private FinanceAnalyticsRepository analytics;
    private FinanceSyncScheduler scheduler;
    private Shop oldShop;
    private CountDownLatch entered,resume;

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString()); Database.initDatabase();
        previous=LicenseRuntime.service(); fixture=LicenseDomainFixture.active(directory.resolve("license-fixture"));
        LicenseRuntime.install(fixture); LicenseRuntime.observeShopCount(1);
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'fixture','WILDBERRIES',?)")) {
            p.setString(1,"fixture-old"); p.executeUpdate();
        }
        oldShop=new Shop(1,"fixture",Marketplace.WILDBERRIES,null,"fixture-old");
        entered=new CountDownLatch(1); resume=new CountDownLatch(1); server=new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                entered.countDown(); if(!resume.await(10,TimeUnit.SECONDS)) return new MockResponse().setResponseCode(503);
                return new MockResponse().setBody("[{\"rrdId\":42,\"penalty\":\"99\",\"rrDate\":\"2026-10-03\",\"currency\":\"RUB\"}]");
            }
        }); server.start(); analytics=new FinanceAnalyticsRepository();
        analytics.ensureShopState(1,Marketplace.WILDBERRIES,LocalDate.now(),java.time.Instant.now());
        analytics.upsertFinanceRows(1,List.of(row("42",7)));
        scheduler=new FinanceSyncScheduler(analytics,new WbFinanceApiClient(new OkHttpClient(),server.url("/finance").toString()),
                new WbAdvertisingApiClient(),new OzonFinanceApiClient(),Clock.systemUTC());
        scheduler.updateShops(List.of(oldShop),false);
    }
    @AfterEach void close() throws Exception {
        if(resume!=null)resume.countDown(); if(server!=null)server.shutdown();
        if(previous!=null)LicenseRuntime.install(previous);if(fixture!=null)fixture.close();
        System.clearProperty("vncode.appdata.dir");
    }

    @Test void credentialChangeDuringResponsePreventsSavingOldPageUnderNewOwner() throws Exception {
        try(var executor=Executors.newSingleThreadExecutor()) {
            var task=executor.submit(scheduler::runOneRequest);
            assertTrue(entered.await(10,TimeUnit.SECONDS)); rotate("fixture-new"); resume.countDown(); task.get(15,TimeUnit.SECONDS);
        }
        var current=new Shop(1,"fixture",Marketplace.WILDBERRIES,null,"fixture-new");
        var repo=new FinancePenaltyRepository(); assertFalse(repo.scopeStatus(current).verified());
        assertEquals(0,repo.count(current,LocalDate.of(2026,10,1),LocalDate.of(2026,10,3)));
        try(var c=AnalyticsDatabase.getConnection();var p=c.prepareStatement("SELECT penalty_cost FROM finance_raw WHERE shop_id=1 AND rrd_id='42'");var rows=p.executeQuery()) {
            assertTrue(rows.next()); assertEquals(7,rows.getDouble(1));
        }
    }

    @Test void staleQueuedCredentialDoesNotSendARequest() throws Exception {
        rotate("fixture-new"); scheduler.runOneRequest(); assertEquals(0,server.getRequestCount());
    }

    @Test void keyUpdateCannotInterleaveBetweenOwnerCheckAndLegacyCheckpoint() throws Exception {
        var checked=new CountDownLatch(1); var continueCheckpoint=new CountDownLatch(1); var rotated=new CountDownLatch(1);
        scheduler.setAfterOwnerCheckedBeforeCheckpoint(()->{
            checked.countDown();
            try { if(!continueCheckpoint.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
        });
        try(var executor=Executors.newFixedThreadPool(2)) {
            var job=executor.submit(scheduler::runOneRequest);
            assertTrue(checked.await(10,TimeUnit.SECONDS));
            var rotation=executor.submit(()->{rotate("fixture-new");rotated.countDown();return null;});
            assertFalse(rotated.await(200,TimeUnit.MILLISECONDS));
            continueCheckpoint.countDown(); assertTrue(rotated.await(10,TimeUnit.SECONDS));
            resume.countDown(); job.get(15,TimeUnit.SECONDS); rotation.get(10,TimeUnit.SECONDS);
        } finally {continueCheckpoint.countDown();resume.countDown();}
        var current=new Shop(1,"fixture",Marketplace.WILDBERRIES,null,"fixture-new");
        assertFalse(new FinancePenaltyRepository().scopeStatus(current).verified());
    }

    @Test void freshResponseBecomesVisibleOnlyUnderTheVerifiedOwner() {
        resume.countDown(); scheduler.runOneRequest();
        var repo=new FinancePenaltyRepository();
        assertTrue(repo.scopeStatus(oldShop).verified()); assertFalse(repo.scopeStatus(oldShop).complete());
        var entries=repo.load(oldShop,LocalDate.of(2026,10,1),LocalDate.of(2026,10,3),10,0);
        assertEquals(1,entries.size()); assertEquals(99,entries.getFirst().amount(),0.001);
    }

    private void rotate(String value) throws Exception {
        ShopOperationCoordinator.withExclusiveShop(1,()->{try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key=? WHERE id=1")){p.setString(1,value);p.executeUpdate();}return null;});
    }
    private static FinanceRawRow row(String id,double penalty) {
        return new FinanceRawRow(id,"report","2026-10-03","RUB","services","legacy",null,null,null,null,
                0,false,0,0,0,0,0,0,0,penalty,0,0,0,0,"{}");
    }
}
