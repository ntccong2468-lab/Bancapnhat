package com.vncode.app.integration.wb;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WbRegistrationSnapshotTest {
    @TempDir Path directory;Shop shop=new Shop(1,"Fixture","synthetic");
    final List<String> seen=new ArrayList<>();
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Fixture','synthetic')");}}
    @AfterEach void clean(){System.clearProperty("vncode.appdata.dir");}
    private WbProductSyncService service(List<WbProductCardsResponse> responses){return new WbProductSyncService(new WbApiClient(){int n;
        @Override public WbProductCardsResponse getProductCards(String key,String locale,String updated,Long nm,int limit){seen.add(updated==null?"FIRST":updated);return n<responses.size()?responses.get(n++):page("{\"cards\":[],\"cursor\":{\"total\":0}}");}
    },new WbProductRepository(),new WbSyncStateRepository(),new WbSyncRunRepository());}
    private WbProductCardsResponse page(String json){return new Gson().fromJson(json,WbProductCardsResponse.class);}
    @Test void completeRegistrationReadIgnoresAnOldIncrementalCursor()throws Exception{
        new WbSyncStateRepository().updateProductsCursor(1,"old",999L,"old");var emitted=new ArrayList<WbProductCard>();
        int count=service(List.of(page("{\"cards\":[{\"nmID\":101,\"vendorCode\":\"A\",\"sizes\":[{\"chrtID\":201}]}],\"cursor\":{\"total\":1,\"updatedAt\":\"now\",\"nmID\":101}}"))).syncFromStart(shop,emitted::addAll);
        assertEquals(List.of("FIRST","now"),seen);assertEquals(1,count);assertEquals(101L,emitted.getFirst().getNmID());
    }
    @Test void absentCardsAndMissingCursorCannotClaimACompletedSnapshot(){
        for(String json:List.of("{}","{\"cards\":[{\"nmID\":101,\"sizes\":[]}]}"))assertThrows(java.io.IOException.class,()->service(List.of(page(json))).syncFromStart(shop,p->{}));
    }
    @Test void emptySuccessfulArrayCompletesWithoutInventingProducts()throws Exception{assertEquals(0,service(List.of(page("{\"cards\":[],\"cursor\":{\"total\":0}}"))).syncFromStart(shop,p->fail("No page must be emitted")));}
    @Test void repeatedCursorEndsWithAnErrorInsteadOfLoopingForever(){
        var response=fullPage("same");
        assertEquals("WB_REPEATED_PRODUCT_CURSOR",assertThrows(java.io.IOException.class,()->service(List.of(response,response)).syncFromStart(shop,p->{})).getMessage());assertEquals(2,seen.size());
    }
    @Test void incrementalReadAlsoRejectsMissingPagesAndCursors(){
        for(String json:List.of("{}","{\"cards\":[{\"nmID\":101}]}"))
            assertThrows(java.io.IOException.class,()->service(List.of(page(json))).sync(shop));
    }
    private WbProductCardsResponse fullPage(String cursor){
        var body=new com.google.gson.JsonObject();var cards=new com.google.gson.JsonArray();
        for(int id=101;id<=200;id++){var card=new com.google.gson.JsonObject();card.addProperty("nmID",id);card.add("sizes",new com.google.gson.JsonArray());cards.add(card);}
        body.add("cards",cards);var next=new com.google.gson.JsonObject();next.addProperty("total",100);next.addProperty("updatedAt",cursor);next.addProperty("nmID",200);body.add("cursor",next);return new Gson().fromJson(body,WbProductCardsResponse.class);
    }
    @Test void incrementalReadCannotLoopOnARepeatedCursor(){
        var response=fullPage("same");
        assertEquals("WB_REPEATED_PRODUCT_CURSOR",assertThrows(java.io.IOException.class,()->service(List.of(response,response)).sync(shop)).getMessage());
        assertEquals(2,seen.size());
    }
    @Test void aFailedLaterPageKeepsTheCheckpointWithoutClaimingFullSuccess()throws Exception{
        var states=new WbSyncStateRepository();states.updateProductsCursor(1,"before",99L,"previous-success");
        assertThrows(java.io.IOException.class,()->service(List.of(fullPage("page-1"),page("{}"))).sync(shop));
        var failed=states.getShopSyncState(1);assertEquals("page-1",failed.productsCursorUpdatedAt());assertEquals("previous-success",failed.productsLastSyncedAt());
        seen.clear();assertEquals(0,service(List.of(page("{\"cards\":[],\"cursor\":{\"total\":0}}"))).sync(shop));
        assertEquals(List.of("page-1"),seen);assertNotEquals("previous-success",states.getShopSyncState(1).productsLastSyncedAt());
    }
    @Test void aContradictoryTwentyRowPageCannotOverwriteGoodDataOrClaimFullOrIncrementalSuccess()throws Exception {
        seedKnownProduct();var states=new WbSyncStateRepository();states.updateProductsCursor(1,"before",99L,"previous-success");
        for(boolean complete:List.of(true,false)){
            var sync=service(List.of(shortPage(20,80)));var emitted=new ArrayList<WbProductCard>();
            var error=assertThrows(java.io.IOException.class,()->{if(complete)sync.syncFromStart(shop,emitted::addAll);else sync.sync(shop);});
            assertEquals("WB_INCOMPLETE_PRODUCT_CURSOR",error.getMessage());assertTrue(emitted.isEmpty());
            assertKnownProductUnchanged();var state=states.getShopSyncState(1);assertEquals("before",state.productsCursorUpdatedAt());assertEquals("previous-success",state.productsLastSyncedAt());
            assertEquals("0/0/0",latestRun());
        }
    }
    @Test void aCompleteHundredRowPageAndAValidTwentyRowPageReadTheWholeSnapshot()throws Exception {
        var emitted=new ArrayList<WbProductCard>();var next=shortPage(20,20,201);
        assertEquals(120,service(List.of(fullPage("page-1"),next)).syncFromStart(shop,emitted::addAll));
        assertEquals(120,emitted.size());assertEquals(List.of("FIRST","page-1","short-page"),seen);assertEquals("1/120/120",latestRun());
        assertEquals(220L,emitted.getLast().getNmID());assertNotNull(new WbSyncStateRepository().getShopSyncState(1).productsLastSyncedAt());
    }
    @Test void fullRefreshDoesNotStopAfterAnApparentlyTerminalTwentyRowPage()throws Exception {
        var emitted=new ArrayList<WbProductCard>();
        assertEquals(50,service(List.of(shortPage(20,20,101),shortPage(30,30,201))).syncFromStart(shop,emitted::addAll));
        assertEquals(50,emitted.size());assertEquals(List.of("FIRST","short-page","short-page"),seen);
        assertEquals("1/50/50",latestRun());
    }
    @Test void nullCardsAndAnActualParserFailureLeaveThePreviousSnapshotIntact()throws Exception {
        seedKnownProduct();var malformed=page("{\"cards\":[null],\"cursor\":{\"total\":1,\"updatedAt\":\"next\",\"nmID\":101}}");
        assertThrows(java.io.IOException.class,()->service(List.of(malformed)).syncFromStart(shop,p->fail("Unknown rows must not be emitted")));
        assertKnownProductUnchanged();assertEquals("0/0/0",latestRun());
        var sync=new WbProductSyncService(new WbApiClient(){
            @Override public WbProductCardsResponse getProductCards(String key,String locale,String updated,Long nm,int limit){return page("{\"cards\":{},\"cursor\":{\"total\":0}}");}
        },new WbProductRepository(),new WbSyncStateRepository(),new WbSyncRunRepository());
        assertThrows(com.google.gson.JsonParseException.class,()->sync.syncFromStart(shop,p->fail("A malformed array is not empty success")));
        assertKnownProductUnchanged();assertEquals("0/0/0",latestRun());
    }
    @Test void aLaterRateLimitPreservesTheSavedPageAndNeverAdvancesTheCompletedReceipt()throws Exception {
        var states=new WbSyncStateRepository();states.updateProductsCursor(1,"before",99L,"previous-success");var emitted=new ArrayList<WbProductCard>();
        var sync=new WbProductSyncService(new WbApiClient(){int calls;
            @Override public WbProductCardsResponse getProductCards(String key,String locale,String updated,Long nm,int limit)throws java.io.IOException{
                seen.add(updated==null?"FIRST":updated);if(calls++==0)return fullPage("page-1");throw new WbApiException("WB rate limit",429,"");
            }
        },new WbProductRepository(),states,new WbSyncRunRepository());
        assertEquals(429,assertThrows(WbApiException.class,()->sync.syncFromStart(shop,emitted::addAll)).getStatusCode());
        var state=states.getShopSyncState(1);assertEquals("page-1",state.productsCursorUpdatedAt());assertEquals("previous-success",state.productsLastSyncedAt());
        assertEquals(100,emitted.size());assertEquals("0/100/100",latestRun());
    }
    @Test void productRecoveryAlsoRejectsContradictoryOrAbsentPagesBeforeOverwritingKnownData()throws Exception {
        seedKnownProduct();
        for(var response:List.of(shortPage(20,80),page("{}"))){
            var failure=assertThrows(java.io.IOException.class,()->service(List.of(response)).recoverProductsByNmIds(shop,List.of(101L)));
            assertTrue(failure.getMessage().startsWith("WB_INCOMPLETE_PRODUCT_"));assertKnownProductUnchanged();assertEquals("0/0/0",latestRun());
        }
    }
    @Test void productRecoveryKeepsValidShortPagesAndStopsARepeatedCursor()throws Exception {
        assertEquals(1,service(List.of(shortPage(20,20))).recoverProductsByNmIds(shop,List.of(110L)));assertEquals("1/20/1",latestRun());
        seen.clear();var repeated=fullPage("same");
        assertEquals("WB_REPEATED_PRODUCT_CURSOR",assertThrows(java.io.IOException.class,()->service(List.of(repeated,repeated)).recoverProductsByNmIds(shop,List.of(999L))).getMessage());
        assertEquals(2,seen.size());assertEquals("0/100/0",latestRun());
    }
    private WbProductCardsResponse shortPage(int count,int total){return shortPage(count,total,101);}
    private WbProductCardsResponse shortPage(int count,int total,int start){
        var body=new com.google.gson.JsonObject();var cards=new com.google.gson.JsonArray();
        for(int id=start;id<start+count;id++){var card=new com.google.gson.JsonObject();card.addProperty("nmID",id);card.addProperty("title","Changed remote title");card.add("sizes",new com.google.gson.JsonArray());cards.add(card);}
        body.add("cards",cards);var next=new com.google.gson.JsonObject();next.addProperty("total",total);next.addProperty("updatedAt","short-page");next.addProperty("nmID",start+count-1);body.add("cursor",next);return new Gson().fromJson(body,WbProductCardsResponse.class);
    }
    private void seedKnownProduct()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,101,'KEEP','Known good title','old')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,201,101,'XL')");
            s.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,201,'04631993764363')");
        }
    }
    private void assertKnownProductUnchanged()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT c.title,c.vendor_code,b.sku FROM wb_product_cards c JOIN wb_product_sizes v ON v.shop_id=c.shop_id AND v.nm_id=c.nm_id JOIN wb_product_size_skus b ON b.shop_id=v.shop_id AND b.chrt_id=v.chrt_id WHERE c.shop_id=1 AND c.nm_id=101")){
            assertTrue(rows.next());assertEquals("Known good title",rows.getString(1));assertEquals("KEEP",rows.getString(2));assertEquals("04631993764363",rows.getString(3));assertFalse(rows.next());
        }
    }
    private String latestRun()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT success,items_read,items_written FROM wb_sync_runs ORDER BY id DESC LIMIT 1")){
            assertTrue(rows.next());return rows.getInt(1)+"/"+rows.getInt(2)+"/"+rows.getInt(3);
        }
    }
}
