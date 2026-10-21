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
}
