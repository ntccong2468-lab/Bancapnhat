package com.vncode.app.features.gs1;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.gs1.NationalCatalogGs1Client;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ChestnyZnakBalanceClient;
import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import com.vncode.app.models.Shop;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.atomic.*;
import static com.vncode.app.testsupport.MockServerOrigins.allow;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP adapters with synthetic certificates; never opens CryptoPro or a browser. */
class Gs1AutomaticSessionTest {
    @TempDir Path root;
    Shop shop;MockWebServer server;Gs1AutomaticSyncService service;
    final AtomicInteger catalogLogins=new AtomicInteger(),cashLogins=new AtomicInteger(),signatures=new AtomicInteger();
    final AtomicBoolean holdCatalog=new AtomicBoolean();
    final java.util.concurrent.CountDownLatch held=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
    final AtomicBoolean expireCatalog=new AtomicBoolean(),expireCash=new AtomicBoolean(),denyCatalog=new AtomicBoolean();
    final AtomicReference<Instant> now=new AtomicReference<>(Instant.now());
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        server=new MockWebServer();server.setDispatcher(new Dispatcher(){
            @Override public MockResponse dispatch(RecordedRequest request){
                String path=request.getPath();
                return switch(path){
                    case "/catalog/signed-data"->json("{\"data\":\"YWJj\"}");
                    case "/catalog/certlogin"->{catalogLogins.incrementAndGet();yield json("{\"csrfToken\":\"fixture-csrf\",\"gs1Member\":true}").setHeader("Set-Cookie","catalog=fixture; Path=/catalog; HttpOnly");}
                    case "/catalog/gs1/gcp-gln"->{
                        if(holdCatalog.compareAndSet(true,false)){held.countDown();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
                        assertEquals("catalog=fixture",request.getHeader("Cookie"));assertEquals("fixture-csrf",request.getHeader("X-Csrf-Token"));assertNull(request.getHeader("Authorization"));
                        yield denyCatalog.get()||expireCatalog.getAndSet(false)?new MockResponse().setResponseCode(401):json("{\"gcps\":[{\"gcp\":\"4601234\",\"gtinsLeft\":50,\"glns\":[]}],\"expiryDate\":\"2027-10-01\"}");
                    }
                    case "/catalog/gs1/form"->json("{\"applicant\":{\"inn\":\"7700000000\",\"companyName\":\"Synthetic\",\"email\":\"source@example.invalid\"},\"isSent\":true}");
                    case "/bff-elk/api/v1/auth/key"->json("{\"uuid\":\"fixture-uuid\",\"data\":\"fixture-challenge\"}");
                    case "/bff-elk/v1/united-auth/login"->{cashLogins.incrementAndGet();yield json("{\"access_token\":\"fixture-elk-token\"}");}
                    case "/bff-elk/v1/profile/organisation"->{
                        assertEquals("Bearer fixture-elk-token",request.getHeader("Authorization"));assertNull(request.getHeader("Cookie"));
                        yield expireCash.getAndSet(false)?new MockResponse().setResponseCode(401):json("{\"inn\":\"7700000000\"}");
                    }
                    case "/bff-elk/v1/organisation/balance"->json("[{\"organisationId\":1,\"productGroupId\":2,\"contractId\":3,\"balance\":12345}]");
                    default->new MockResponse().setResponseCode(500);
                };
            }
        });server.start();
        var http=new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(3)).build();
        var clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
        var remote=new Gs1AutomaticSyncService.Remote(new NationalCatalogGs1Client(http,server.url("/catalog/")),new ChestnyZnakBalanceClient(http,server.url("/").uri(),allow(server)),
            (selected,settings,scope)->(payload,context)->{assertTrue(java.util.Set.of(1,2).contains(selected.getId()));assertEquals(GtinWriteTestFixture.CERTIFICATE,scope.fingerprint());signatures.incrementAndGet();return new CryptoProSigningResult(new byte[]{1,2,3},"");},clock);
        service=new Gs1AutomaticSyncService(remote);
    }
    @AfterEach void clear()throws Exception{try{server.shutdown();}finally{System.clearProperty("vncode.appdata.dir");}}
    private static MockResponse json(String value){return new MockResponse().setHeader("Content-Type","application/json").setBody(value);}
    @Test void slowResponsesEndAtTheReadBudgetAndDoNotContinueIntoAnotherSource()throws Exception{
        var http=new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(3)).build();
        var shortRemote=new Gs1AutomaticSyncService.Remote(new NationalCatalogGs1Client(http,server.url("/catalog/")),new ChestnyZnakBalanceClient(http,server.url("/").uri(),allow(server)),
            (selected,settings,scope)->(payload,context)->new CryptoProSigningResult(new byte[]{1,2,3},""),Clock.systemUTC(),Duration.ofMillis(500));
        holdCatalog.set(true);var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var future=pool.submit(()->new Gs1AutomaticSyncService(shortRemote).refresh(shop));assertTrue(held.await(2,java.util.concurrent.TimeUnit.SECONDS));Thread.sleep(600);release.countDown();
            var result=future.get(2,java.util.concurrent.TimeUnit.SECONDS);assertEquals("GS1_SYNC_TIMEOUT",result.catalogError());assertEquals("GS1_SYNC_TIMEOUT",result.balanceError());assertEquals(0,cashLogins.get());
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void foregroundGs1ActionsReuseTheVerifiedBackgroundCatalogSession()throws Exception{
        service.refresh(shop);var session=service.catalogSession(shop);assertEquals("7700000000",session.inn());session.form();assertEquals(1,catalogLogins.get());assertEquals(1,cashLogins.get());assertEquals(2,signatures.get());
    }
    @Test void aSlowStoreDoesNotHoldTheOtherStoresSessionLock()throws Exception{
        var second=GtinWriteTestFixture.shop(Marketplace.OZON);holdCatalog.set(true);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->service.refresh(shop));assertTrue(held.await(2,java.util.concurrent.TimeUnit.SECONDS));var other=pool.submit(()->service.refresh(second));
            assertTrue(other.get(2,java.util.concurrent.TimeUnit.SECONDS).complete());assertFalse(first.isDone(),"The other store must complete before the blocked read is released");release.countDown();assertTrue(first.get(3,java.util.concurrent.TimeUnit.SECONDS).complete());
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void aReturnedSessionCannotMakeRequestsAfterTheShopCredentialsChange()throws Exception{
        var session=service.catalogSession(shop);int before=server.getRequestCount();try(var c=Database.getConnection();var sql=c.createStatement()){sql.execute("UPDATE shops SET api_key='different-fixture' WHERE id=1");}
        assertThrows(SecurityException.class,session::form);assertEquals(before,server.getRequestCount());
    }
    @Test void configuredLoginReadsProfileAndReusesSeparateServiceSessions()throws Exception{
        assertTrue(service.refresh(shop).complete());assertTrue(service.refresh(shop).complete());
        assertEquals(1,catalogLogins.get());assertEquals(1,cashLogins.get());assertEquals(2,signatures.get());
        var cache=service.cached(shop).orElseThrow();assertEquals("source@example.invalid",cache.email());assertEquals(Boolean.TRUE,cache.applicationSent());
        assertFalse(cache.toString().contains("source@example.invalid"));assertEquals(13,server.getRequestCount());
    }
    @Test void expiredCatalogAndElkSessionsEachReauthenticateOnceWithoutBrowser()throws Exception{
        service.refresh(shop);expireCatalog.set(true);expireCash.set(true);
        assertTrue(service.refresh(shop).complete());assertEquals(2,catalogLogins.get());assertEquals(2,cashLogins.get());assertEquals(4,signatures.get());
    }
    @Test void persistentAuthenticationFailureStopsAfterOneReauthenticationAndKeepsOtherServiceData()throws Exception{
        service.refresh(shop);denyCatalog.set(true);
        var result=service.refresh(shop);assertFalse(result.complete());assertNull(result.membership());assertEquals(1,result.balances().size());
        assertEquals("GS1_AUTH_REQUIRED",result.catalogError());assertEquals(2,catalogLogins.get());assertEquals(1,cashLogins.get());
        assertEquals("source@example.invalid",service.cached(shop).orElseThrow().email());assertEquals(Boolean.TRUE,service.cached(shop).orElseThrow().applicationSent());
    }
    @Test void cacheExpiryRequiresFreshAuthentication()throws Exception{
        service.refresh(shop);now.set(now.get().plusSeconds(601));assertTrue(service.refresh(shop).complete());
        assertEquals(2,catalogLogins.get());assertEquals(2,cashLogins.get());
    }
}
