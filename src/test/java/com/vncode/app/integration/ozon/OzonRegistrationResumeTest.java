package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class OzonRegistrationResumeTest {
    @TempDir Path directory;
    private Shop shop;private String scope;private OzonApiClient api;private MockWebServer server;
    private com.vncode.app.features.license.LicenseService previous,license;
    private final List<String> cursors=Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean failSecond=new AtomicBoolean();
    private final AtomicBoolean changeTotal=new AtomicBoolean();
    private final AtomicBoolean duplicate=new AtomicBoolean();
    private final AtomicBoolean changeHead=new AtomicBoolean();
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.resolve("data").toString());Database.initDatabase();
        shop=GtinWriteTestFixture.shop(Marketplace.OZON);scope=RegisteredGtinProof.current(shop).source();
        previous=LicenseRuntime.service();license=LicenseDomainFixture.active(directory.resolve("license"));LicenseRuntime.install(license);
        server=new MockWebServer();server.start();
        server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){
            var body=JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();String path=request.getPath();
            if(path.endsWith("/v3/product/list")){
                String cursor=body.has("last_id")?body.get("last_id").getAsString():"";cursors.add(cursor);
                if(!cursor.isBlank()&&failSecond.getAndSet(false))return new MockResponse().setResponseCode(429).setHeader("Retry-After","1").setBody("{}");
                String id=cursor.isBlank()?(changeHead.get()?"103":"101"):(duplicate.get()?"101":"102"),offer=offer(id);int total=!cursor.isBlank()&&changeTotal.get()?3:2;
                String next=cursor.isBlank()?"cursor-"+id:"cursor-102";
                return json("{\"result\":{\"total\":"+total+",\"items\":[{\"product_id\":\""+id+"\",\"offer_id\":\""+offer+"\"}],\"last_id\":\""+next+"\"}}");
            }
            if(path.endsWith("/v3/product/info/list")){
                String id=body.getAsJsonArray("product_id").get(0).getAsString(),offer=offer(id);
                return json("{\"items\":[{\"id\":\""+id+"\",\"offer_id\":\""+offer+"\",\"name\":\"Fixture\",\"sku\":\""+id+"\",\"barcodes\":[]}]}");
            }
            if(path.endsWith("/v4/product/info/attributes")){
                String id=body.getAsJsonObject("filter").getAsJsonArray("product_id").get(0).getAsString(),offer=offer(id);
                return json("{\"result\":[{\"id\":\""+id+"\",\"offer_id\":\""+offer+"\",\"description_category_id\":1,\"type_id\":2,\"attributes\":[]}]}");
            }
            return json("{\"result\":[]}");
        }});
        api=new OzonApiClient(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey()),server.url("/"),new OkHttpClient.Builder().callTimeout(5,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1)));
    }
    @AfterEach void cleanup()throws Exception{if(server!=null)server.shutdown();if(previous!=null)LicenseRuntime.install(previous);if(license!=null)license.close();System.clearProperty("vncode.appdata.dir");}
    @Test void rateLimitResumesSavedCursorAndReplaysExactMembersIntoNewReceipt()throws Exception{
        failSecond.set(true);var first=new ArrayList<String>();
        assertThrows(OzonApiException.class,()->sync(first));assertEquals(List.of("101"),first);
        var resumed=new ArrayList<String>();sync(resumed);
        assertEquals(List.of("101","102"),resumed);assertEquals(List.of("","cursor-101","","cursor-101"),cursors);
        var scan=new OzonRegistrationCheckpoint(shop,scope).open();assertTrue(scan.completed());assertEquals(2,scan.count());
        var replay=new ArrayList<String>();sync(replay);assertEquals(List.of("101","102"),replay);assertEquals(5,cursors.size());
    }
    @Test void interruptedCallbackReplaysCommittedPageAndDoesNotSkipIt()throws Exception{
        assertThrows(IllegalStateException.class,()->new OzonCatalogSyncService(shop.getId(),api).syncForRegistration(shop,scope,rows->{throw new IllegalStateException("fixture_crash");}));
        var resumed=new ArrayList<String>();sync(resumed);assertEquals(List.of("101","102"),resumed);assertEquals(List.of("","","cursor-101"),cursors);
    }
    @Test void replayReleasesReadConnectionBeforeObserverWrites()throws Exception{
        failSecond.set(true);assertThrows(OzonApiException.class,()->sync(new ArrayList<>()));
        var resumed=new ArrayList<String>();new OzonCatalogSyncService(shop.getId(),api).syncForRegistration(shop,scope,rows->{
            try(var c=Database.getConnection();var p=c.prepareStatement("INSERT OR REPLACE INTO app_config(key,value) VALUES('test-registration-replay','ok')")){p.executeUpdate();}catch(Exception e){throw new RuntimeException(e);}
            rows.forEach(row->resumed.add(row.productId()));
        });assertEquals(List.of("101","102"),resumed);
    }
    @Test void changedTotalInvalidatesCheckpointBeforeNewPageBecomesObserved()throws Exception{
        failSecond.set(true);assertThrows(OzonApiException.class,()->sync(new ArrayList<>()));changeTotal.set(true);
        var resumed=new ArrayList<String>();var error=assertThrows(IOException.class,()->sync(resumed));assertEquals("OZON_REGISTRATION_CATALOG_CHANGED",error.getMessage());assertEquals(List.of("101"),resumed);
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT count(*) FROM ozon_products WHERE shop_id=? AND product_id='102'")){p.setInt(1,shop.getId());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}}
        changeTotal.set(false);var fresh=new ArrayList<String>();sync(fresh);assertEquals(List.of("101","102"),fresh);assertEquals("",cursors.get(4));
    }
    @Test void duplicateAcrossPagesInvalidatesInsteadOfCreatingAFalseReceipt()throws Exception{
        failSecond.set(true);assertThrows(OzonApiException.class,()->sync(new ArrayList<>()));duplicate.set(true);
        assertEquals("OZON_REGISTRATION_CATALOG_CHANGED",assertThrows(IOException.class,()->sync(new ArrayList<>())).getMessage());
    }
    @Test void changedFirstPageCannotReuseOlderMembershipOrCursor()throws Exception{
        failSecond.set(true);assertThrows(OzonApiException.class,()->sync(new ArrayList<>()));changeHead.set(true);
        var resumed=new ArrayList<String>();sync(resumed);assertEquals(List.of("103","102"),resumed);
        assertEquals(List.of("","cursor-101","","","cursor-103"),cursors);
    }
    @Test void changedCatalogOwnerOrExpiredProgressStartsASeparateFullScan()throws Exception{
        failSecond.set(true);assertThrows(OzonApiException.class,()->sync(new ArrayList<>()));
        GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);scope=RegisteredGtinProof.current(shop).source();
        failSecond.set(true);assertThrows(OzonApiException.class,()->sync(new ArrayList<>()));assertEquals("",cursors.get(2));
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_registration_scans SET updated_at=? WHERE shop_id=? AND source=?")){p.setString(1,Instant.now().minusSeconds(7201).toString());p.setInt(2,shop.getId());p.setString(3,scope);p.executeUpdate();}
        var fresh=new ArrayList<String>();sync(fresh);assertEquals(List.of("101","102"),fresh);assertEquals("",cursors.get(4));
    }
    @Test void checkpointCanResumeMoreThanTenThousandMarketplaceProducts()throws Exception{
        var checkpoint=new OzonRegistrationCheckpoint(shop,scope);var state=checkpoint.open();
        for(int start=0;start<10001;start+=500){
            int end=Math.min(start+500,10001);var page=new ArrayList<OzonProductDto>();
            for(int id=start+1;id<=end;id++)page.add(new OzonProductDto(Integer.toString(id),"offer-"+id,"sku-"+id,"Product","",false,"",List.of()));
            state=checkpoint.append(state,page,"cursor-"+end,end==10001,10001);
        }
        var resumed=new OzonRegistrationCheckpoint(shop,scope);var saved=resumed.open();
        assertTrue(saved.completed());assertEquals(10001,saved.count());
        var count=new java.util.concurrent.atomic.AtomicInteger();resumed.replay(saved,rows->count.addAndGet(rows.size()));assertEquals(10001,count.get());
    }
    private void sync(List<String> result)throws IOException{new OzonCatalogSyncService(shop.getId(),api).syncForRegistration(shop,scope,rows->rows.forEach(row->result.add(row.productId())));}
    private static String offer(String id){return switch(id){case "101"->"A";case "102"->"B";default->"C";};}
    private static MockResponse json(String body){return new MockResponse().setHeader("Content-Type","application/json").setBody(body);}
}
