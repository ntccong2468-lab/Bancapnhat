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
            (selected,settings,scope)->(payload,context)->{assertEquals(shop.getId(),selected.getId());assertEquals(GtinWriteTestFixture.CERTIFICATE,scope.fingerprint());signatures.incrementAndGet();return new CryptoProSigningResult(new byte[]{1,2,3},"");},clock);
        service=new Gs1AutomaticSyncService(remote);
    }
    @AfterEach void clear()throws Exception{try{server.shutdown();}finally{System.clearProperty("vncode.appdata.dir");}}
    private static MockResponse json(String value){return new MockResponse().setHeader("Content-Type","application/json").setBody(value);}
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
