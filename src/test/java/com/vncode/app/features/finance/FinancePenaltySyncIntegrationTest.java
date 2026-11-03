package com.vncode.app.features.finance;

import com.google.gson.JsonParser;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class FinancePenaltySyncIntegrationTest {
    @TempDir Path directory;
    private LicenseService previous,fixture;
    private MockWebServer server;
    private FinancePenaltySyncStore store;
    private FinanceSyncScheduler scheduler;
    private Shop shop;
    private MutableClock clock;
    private final LocalDate from=LocalDate.of(2026,10,1),to=LocalDate.of(2026,10,3);
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        previous=LicenseRuntime.service();fixture=LicenseDomainFixture.active(directory.resolve("license-fixture"));
        LicenseRuntime.install(fixture);LicenseRuntime.observeShopCount(1);
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'fixture','WILDBERRIES',?)")){p.setString(1,"fixture-old");p.executeUpdate();}
        shop=new Shop(1,"fixture",Marketplace.WILDBERRIES,null,"fixture-old");
        server=new MockWebServer();server.start();clock=new MutableClock(Instant.now());store=new FinancePenaltySyncStore();
        var analytics=new FinanceAnalyticsRepository(); analytics.ensureShopState(1,Marketplace.WILDBERRIES,to,clock.instant());
        var ads=new WbAdvertisingApiClient(){@Override public List<AdvertisingRawRow> loadCosts(String key,LocalDate a,LocalDate b){return List.of();}};
        scheduler=new FinanceSyncScheduler(analytics,new WbFinanceApiClient(new OkHttpClient(),server.url("/finance").toString()),ads,new OzonFinanceApiClient(),clock);
        scheduler.updateShops(List.of(shop),false);
    }
    @AfterEach void teardown() throws Exception {
        if(server!=null)server.shutdown();if(previous!=null)LicenseRuntime.install(previous);if(fixture!=null)fixture.close();
        System.clearProperty("vncode.appdata.dir");
    }
    @Test void exactRangeAndCursorPersistAcrossPagesWithoutIgnoringSharedRate() throws Exception {
        store.request(shop,from,to,clock.instant());
        server.enqueue(new MockResponse().setBody("[{\"rrdId\":42,\"rrDate\":\"2026-10-02\",\"penalty\":\"10\",\"bonusTypeName\":\"Причина\"}]"));
        server.enqueue(new MockResponse().setResponseCode(204));
        scheduler.runOneRequest();
        var first=store.status(shop,from,to);assertTrue(first.verified());assertFalse(first.complete());
        assertEquals(1,store.count(shop,from,to));
        var req=JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        assertEquals(from.toString(),req.get("dateFrom").getAsString());assertEquals(to.toString(),req.get("dateTo").getAsString());assertEquals("0",req.get("rrdId").getAsString());
        scheduler.runOneRequest();assertEquals(1,server.getRequestCount());
        clock.advance(62);scheduler.runOneRequest();
        var second=JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();assertEquals("42",second.get("rrdId").getAsString());
        assertTrue(store.status(shop,from,to).complete());assertEquals("Причина",store.load(shop,from,to,10,0).getFirst().description());
        assertEquals("NOT_STARTED",store.status(shop,from.minusDays(1),to).status());
    }
    @Test void empty200IsNotACompletePeriodAnd429WithoutHeaderWaitsConservatively() {
        store.request(shop,from,to,clock.instant());server.enqueue(new MockResponse().setBody("[]"));
        scheduler.runOneRequest();var status=store.status(shop,from,to);
        assertFalse(status.complete());assertEquals("WB_EMPTY_PAGE_UNCONFIRMED",status.failureCode());
        clock.advance(62);server.enqueue(new MockResponse().setResponseCode(429));scheduler.runOneRequest();
        status=store.status(shop,from,to);assertFalse(status.complete());assertEquals("WB_429_DELAY_UNKNOWN",status.failureCode());
        assertEquals(clock.instant().plusSeconds(12*60*60),store.nextAllowed(1,"FINANCE"));
        int calls=server.getRequestCount();clock.advance(62);scheduler.runOneRequest();assertEquals(calls,server.getRequestCount());
    }
    @Test void cancellationDuringHttpDiscardsLatePageAndPreservesCursor() throws Exception {
        var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request)throws InterruptedException{
            entered.countDown();if(!resume.await(10,TimeUnit.SECONDS))return new MockResponse().setResponseCode(503);
            return new MockResponse().setBody("[{\"rrdId\":42,\"rrDate\":\"2026-10-02\",\"penalty\":\"10\"}]");
        }});
        store.request(shop,from,to,clock.instant());
        try(var executor=Executors.newSingleThreadExecutor()){
            var task=executor.submit(scheduler::runOneRequest);assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertTrue(store.cancel(shop,from,to));resume.countDown();task.get(15,TimeUnit.SECONDS);
        } finally {resume.countDown();}
        assertEquals("CANCELLED",store.status(shop,from,to).status());assertFalse(store.status(shop,from,to).complete());assertEquals(0,store.count(shop,from,to));
    }
    @Test void malformedHugeRetryDelayDoesNotOverflowOrRetryImmediately() {
        store.request(shop,from,to,clock.instant());
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("X-Ratelimit-Retry",Long.toString(Long.MAX_VALUE)));
        scheduler.runOneRequest();
        assertEquals("WAITING",store.status(shop,from,to).status());
        assertEquals(Instant.parse("9999-12-31T23:59:59Z"),store.nextAllowed(1,"FINANCE"));
        clock.advance(3600);scheduler.runOneRequest();assertEquals(1,server.getRequestCount());
    }
    @Test void credentialRotationDuringHttpDoesNotCommitToEitherOwner() throws Exception {
        var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request)throws InterruptedException{
            entered.countDown();if(!resume.await(10,TimeUnit.SECONDS))return new MockResponse().setResponseCode(503);
            return new MockResponse().setBody("[{\"rrdId\":42,\"rrDate\":\"2026-10-02\",\"penalty\":\"10\"}]");
        }});
        store.request(shop,from,to,clock.instant());
        try(var executor=Executors.newSingleThreadExecutor()){
            var task=executor.submit(scheduler::runOneRequest);assertTrue(entered.await(10,TimeUnit.SECONDS));
            ShopOperationCoordinator.withExclusiveShop(1,()->{try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key='fixture-new' WHERE id=1")){p.executeUpdate();}return null;});
            resume.countDown();task.get(15,TimeUnit.SECONDS);
        } finally {resume.countDown();}
        var current=new Shop(1,"fixture",Marketplace.WILDBERRIES,null,"fixture-new");
        assertEquals("NOT_STARTED",store.status(current,from,to).status());assertEquals(0,store.count(current,from,to));
    }
    private static final class MutableClock extends Clock {
        private Instant now;MutableClock(Instant now){this.now=now;}void advance(long seconds){now=now.plusSeconds(seconds);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return now;}
    }
}
