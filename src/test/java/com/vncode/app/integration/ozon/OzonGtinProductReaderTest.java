package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonGtinProductReaderTest {
    @TempDir Path root;MockWebServer server;Shop shop;OzonApiClient api;OzonGtinProductReader reader;
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.OZON);server=new MockWebServer();server.start();
        api=new OzonApiClient(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey()),server.url("/"),new OkHttpClient.Builder().callTimeout(3,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(2)));
        reader=new OzonGtinProductReader(shop,api);
    }
    @AfterEach void cleanup()throws Exception{try{server.shutdown();}finally{System.clearProperty("vncode.appdata.dir");}}
    void respond(String body){server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(body));}
    String page(String items,int total,String cursor){return "{\"result\":{\"items\":["+items+"],\"total_items\":"+total+",\"last_id\":\""+cursor+"\"}}";}
    String ref(int id){return "{\"product_id\":"+id+",\"offer_id\":\"offer-"+id+"\"}";}
    String card(int id){return "{\"id\":"+id+",\"offer_id\":\"offer-"+id+"\",\"sku\":"+(id+1000)+",\"barcodes\":[\"00000000000017\",\"OZN-"+id+"\"]}";}
    String info(String items){return "{\"items\":["+items+"]}";}
    com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey key(){return new com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey(shop.getId(),Marketplace.OZON,"501","offer-501");}
    @Test void readsActiveAndArchivedPartitionsAndPreservesLeadingZeros()throws Exception{
        respond(page(ref(501),1,""));respond(info(card(501)));respond(page(ref(502),1,""));respond(info(card(502)));
        var rows=reader.list();assertEquals(2,rows.size());assertEquals(List.of("00000000000017","OZN-501"),rows.getFirst().barcodes());assertEquals("offer-502",rows.getLast().key().variantId());
        assertEquals("ALL",JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject().getAsJsonObject("filter").get("visibility").getAsString());assertEquals("/v3/product/info/list",server.takeRequest().getPath());
        assertEquals("ARCHIVED",JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject().getAsJsonObject("filter").get("visibility").getAsString());
    }
    @Test void shortPageStillFollowsCursorWhenTotalItemsShowsMoreProducts()throws Exception{
        respond(page(ref(501),2,"next"));respond(info(card(501)));respond(page(ref(502),2,""));respond(info(card(502)));respond(page("",0,""));assertEquals(2,reader.list().size());assertEquals(5,server.getRequestCount());
        server.takeRequest();server.takeRequest();assertEquals("next",JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject().get("last_id").getAsString());
    }
    @Test void duplicateDetailsCannotStandInForAMissingProductEvenWhenCountsMatch(){
        respond(page(ref(501)+","+ref(502),2,""));respond(info(card(501)+","+card(501)));assertThrows(IOException.class,reader::list);assertEquals(2,server.getRequestCount());
    }
    @Test void foreignDetailsCannotReplaceTheRequestedIdentity(){respond(info(card(502)));assertThrows(IOException.class,()->reader.read(key()));}
    @Test void duplicateExactReadbackIsAmbiguous(){respond(info(card(501)+","+card(501)));assertThrows(IOException.class,()->reader.read(key()));}
    @Test void missingItemsIsNotAnEmptyCatalog(){respond("{}");assertThrows(IOException.class,reader::list);assertEquals(1,server.getRequestCount());}
    @Test void missingOrConflictingTotalsCannotProveACompleteRead(){respond("{\"result\":{\"items\":[]}}");assertThrows(IOException.class,reader::list);respond("{\"result\":{\"items\":[],\"total_items\":0,\"total\":2}}");assertThrows(IOException.class,reader::list);assertEquals(2,server.getRequestCount());}
    @Test void missingProductRemainsUnknownInsteadOfReturningAnotherProduct(){respond(info(""));assertThrows(IOException.class,()->reader.read(key()));}
    @Test void wrongOfferCannotBecomeTheSelectedVariant(){respond(info(card(501).replace("offer-501","other-offer")));assertThrows(IOException.class,()->reader.read(key()));}
    @Test void invalidBarcodeTypeIsNotSilentlyDropped(){respond(info(card(501).replace("\"OZN-501\"","123")));assertThrows(IOException.class,()->reader.read(key()));}
    @Test void repeatedIdAcrossPagesFailsTheWholeIncompleteRead(){
        respond(page(ref(501),2,"next"));respond(info(card(501)));respond(page(ref(501),2,"next-2"));assertThrows(IOException.class,reader::list);assertEquals(3,server.getRequestCount());
    }
    @Test void declaredRemainingProductsWithoutACursorCannotBeReportedAsComplete(){respond(page(ref(501),2,""));respond(info(card(501)));assertThrows(IOException.class,reader::list);}
    @Test void aShrinkingTotalDuringPaginationCannotTurnMissingProductsIntoAbsence(){respond(page(ref(501),2,"next"));respond(info(card(501)));respond(page("",1,""));assertThrows(IOException.class,reader::list);assertEquals(3,server.getRequestCount());}
    @Test void failedAuthorizationDoesNotBecomeAnEmptyCatalog(){server.enqueue(new MockResponse().setResponseCode(403).setBody("{}"));assertThrows(OzonApiException.class,reader::list);assertEquals(1,server.getRequestCount());}
    @Test void rotatedCredentialsCannotReadOrPublishTheOldAccountsSnapshot()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='new-synthetic-key' WHERE id=2");}assertThrows(SecurityException.class,reader::list);assertEquals(0,server.getRequestCount());
    }
    @Test void aWrongCapturedShopCannotBorrowAnApiClientsCurrentAccount(){
        var wrong=new Shop(shop.getId(),shop.getName(),Marketplace.OZON,shop.getClientId(),"wrong-synthetic-token");var reader=new OzonGtinProductReader(wrong,api);assertThrows(SecurityException.class,reader::list);assertThrows(SecurityException.class,()->reader.read(key()));assertEquals(0,server.getRequestCount());
    }
    @Test void freshBarcodeReadbackChecksTheExactSkuAndUpdatesOnlyObservedProducts()throws Exception{
        var first=key();var second=new com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey(shop.getId(),Marketplace.OZON,"502","offer-502");
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,synced_at) VALUES(2,'501','offer-501','1501','fixture'),(2,'502','offer-502','1502','fixture')");
            s.execute("INSERT INTO ozon_product_barcodes(shop_id,product_id,barcode) VALUES(2,'501','old'),(2,'502','untouched')");
        }
        respond(info(card(501)+","+card(502)));
        var actual=reader.readBarcodes(Map.of(first,"1501",second,"wrong-sku"));
        assertEquals(Map.of(first,List.of("00000000000017","OZN-501")),actual);assertEquals(1,server.getRequestCount());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT barcode FROM ozon_product_barcodes WHERE shop_id=2 AND product_id='502'" );var row=p.executeQuery()){
            assertTrue(row.next());assertEquals("untouched",row.getString(1));
        }
    }
    @Test void freshBarcodeReadbackUsesAtMostOneThousandProductsPerRequest()throws Exception{
        var requested=new java.util.LinkedHashMap<com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey,String>();
        var first=new java.util.ArrayList<String>();
        for(int id=1;id<=1001;id++){
            requested.put(new com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey(shop.getId(),Marketplace.OZON,Integer.toString(id),"offer-"+id),Integer.toString(id+1000));
            if(id<=1000)first.add(card(id));
        }
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,synced_at) VALUES(2,?,?,?,'fixture')")){
            for(int id=1;id<=1001;id++){p.setString(1,Integer.toString(id));p.setString(2,"offer-"+id);p.setString(3,Integer.toString(id+1000));p.addBatch();}p.executeBatch();
        }
        respond(info(String.join(",",first)));respond(info(card(1001)));
        assertEquals(1001,reader.readBarcodes(requested).size());assertEquals(2,server.getRequestCount());
        assertEquals(1000,JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject().getAsJsonArray("product_id").size());
        assertEquals(1,JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject().getAsJsonArray("product_id").size());
    }
    @Test void incompleteFreshReadbackCannotConfirmAnyProduct(){
        respond(info(""));assertThrows(IOException.class,()->reader.readBarcodes(Map.of(key(),"1501")));
    }
    @Test void autoArchivedProductCannotBecomeAVerifiedCurrentMapping()throws Exception{
        respond(info(card(501).replace("\"barcodes\":", "\"is_autoarchived\":true,\"barcodes\":")));
        assertTrue(reader.readBarcodes(Map.of(key(),"1501")).isEmpty());
    }
    @Test void anOlderResponseArrivingLateCannotOverwriteOrConfirmANewerBarcodeRead()throws Exception{
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,synced_at) VALUES(2,'501','offer-501','1501','fixture')")){p.executeUpdate();}
        var olderStarted=new java.util.concurrent.CountDownLatch(1);var releaseOlder=new java.util.concurrent.CountDownLatch(1);
        var order=new java.util.concurrent.atomic.AtomicInteger();
        server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request)throws InterruptedException{
            if(order.incrementAndGet()==1){olderStarted.countDown();releaseOlder.await(5,TimeUnit.SECONDS);return new MockResponse().setHeader("Content-Type","application/json").setBody(info(card(501)));}
            return new MockResponse().setHeader("Content-Type","application/json").setBody(info(card(501).replace("00000000000017","newer-barcode")));
        }});
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var older=executor.submit(()->reader.readBarcodes(Map.of(key(),"1501")));assertTrue(olderStarted.await(3,TimeUnit.SECONDS));
            var newer=reader.readBarcodes(Map.of(key(),"1501"));assertTrue(newer.get(key()).contains("newer-barcode"));releaseOlder.countDown();
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->older.get(5,TimeUnit.SECONDS));
            assertInstanceOf(IOException.class,failure.getCause());assertEquals("OZON_BARCODE_READ_SUPERSEDED",failure.getCause().getMessage());
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT barcode FROM ozon_product_barcodes WHERE shop_id=2 AND product_id='501' ORDER BY barcode");var rows=p.executeQuery()){
                var values=new java.util.ArrayList<String>();while(rows.next())values.add(rows.getString(1));assertEquals(List.of("OZN-501","newer-barcode"),values);
            }
        }finally{releaseOlder.countDown();executor.shutdownNow();}
    }
}
