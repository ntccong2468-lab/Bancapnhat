package com.vncode.app.features.license;

import com.vncode.app.config.Database;
import com.vncode.app.features.fbosupply.*;
import com.vncode.app.features.products.*;
import com.vncode.app.features.news.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.wb.*;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LicenseReadBoundaryTest {
    @TempDir Path root;
    LicenseService prior,denied;
    final Shop wb=new Shop(1,"WB","fixture"),ozon=new Shop(2,"Ozon",Marketplace.OZON,"client","fixture");
    MockWebServer server;
    OzonApiClient api;
    @BeforeEach void setup()throws Exception {
        prior=LicenseRuntime.service();denied=LicenseDomainFixture.denied(root.resolve("denied"));
        System.setProperty("vncode.appdata.dir",root.resolve("data").toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace,client_id) VALUES(1,'WB','fixture','WILDBERRIES',NULL),(2,'Ozon','fixture','OZON','client')");}
        server=new MockWebServer();server.start();
        api=new OzonApiClient(2,new OzonCredentials("client","fixture"),server.url("/"),new OkHttpClient.Builder().callTimeout(2,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1)));
    }
    @AfterEach void cleanup()throws Exception {LicenseRuntime.install(prior);denied.close();server.shutdown();System.clearProperty("vncode.appdata.dir");}
    void deny(){LicenseRuntime.install(denied);}
    @Test void deniedWbSyncEntriesDoNotStartRemoteWorkOrSyncRuns()throws Exception {
        deny();
        assertThrows(LicenseGate.LicenseDeniedException.class,()->new WbSyncWorkflow().syncOverview(wb));
        assertThrows(LicenseGate.LicenseDeniedException.class,()->new WbProductSyncService().sync(wb));
        assertThrows(LicenseGate.LicenseDeniedException.class,()->new WbOrderSyncService().syncNewOrders(wb));
        assertThrows(LicenseGate.LicenseDeniedException.class,()->new WbSupplySyncService().syncIncremental(wb));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM wb_sync_runs")){r.next();assertEquals(0,r.getInt(1));}
    }
    @Test void deniedOzonSyncEntriesMakeZeroHttpRequests()throws Exception {
        var clientCreations=new AtomicInteger();var workflow=new OzonSyncWorkflow(s->{clientCreations.incrementAndGet();return api;});deny();
        assertThrows(LicenseGate.LicenseDeniedException.class,()->workflow.syncOverview(ozon));
        assertThrows(LicenseGate.LicenseDeniedException.class,()->workflow.checkConnection(ozon));
        assertThrows(LicenseGate.LicenseDeniedException.class,()->new OzonCatalogSyncService(2,api).sync());
        assertThrows(LicenseGate.LicenseDeniedException.class,()->new OzonPostingSyncService(2,api).syncUnfulfilled());
        assertEquals(0,clientCreations.get());assertEquals(0,server.getRequestCount());
    }
    @Test void deniedProductRefreshKeepsLocalPageUsableWithoutCallingReaders(){
        var calls=new AtomicInteger();var products=new ProductRepository();var service=new ProductRefreshService(s->{calls.incrementAndGet();return 0;},(s,k)->{calls.incrementAndGet();throw new AssertionError("Remote reader must not run");},products);
        var query=new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,false,null,100);deny();
        assertThrows(LicenseGate.LicenseDeniedException.class,()->service.refresh(wb,query));
        assertThrows(LicenseGate.LicenseDeniedException.class,()->service.refreshCard(wb,new ProductKey(1,Marketplace.WILDBERRIES,"123","1")));
        assertEquals(0,calls.get());assertEquals(0,products.loadPage(query).rows().size());
    }
    @Test void deniedFboSyncKeepsCachedListReadable(){
        var service=new FboSupplySyncService();deny();
        assertThrows(LicenseGate.LicenseDeniedException.class,()->service.syncOrders(wb));
        assertTrue(service.cachedOrders(wb).isEmpty());
    }
    @Test void deniedOfficialNewsRefreshMakesNoRequestsAndCachedNewsRemainsAvailable(){
        var calls=new AtomicInteger();var service=new OfficialNewsService(new SellerNewsRepository(root.resolve("news.db")),(s,from,id)->{calls.incrementAndGet();throw new AssertionError("Remote news reader must not run");},Clock.systemUTC());deny();
        assertThrows(LicenseGate.LicenseDeniedException.class,()->service.refresh(wb,NewsScope.forShop(wb),true));
        assertEquals(0,calls.get());assertTrue(service.cached(NewsScope.forShop(wb)).isEmpty());
    }
    @Test void revokeBetweenOzonPagesPreservesFirstPageWithoutSecondHttpOrApiError()throws Exception {
        server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){deny();return new MockResponse().setHeader("Content-Type","application/json").setBody("{\"postings\":[{\"posting_number\":\"0001-01-1\",\"order_id\":\"1\",\"status\":\"awaiting_packaging\",\"products\":[]}],\"cursor\":\"page2\",\"has_next\":true}");}});
        assertThrows(LicenseGate.LicenseDeniedException.class,()->new OzonPostingSyncService(2,api).sync());
        assertEquals(1,server.getRequestCount());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM ozon_postings WHERE shop_id=2")){r.next();assertEquals(1,r.getInt(1));}
        var state=new OzonSyncStateRepository().find(2);assertEquals("",state.postingsChangedSince());assertTrue(state.lastError()==null||state.lastError().isBlank(),"License pause must not turn into API failure");
    }
}
