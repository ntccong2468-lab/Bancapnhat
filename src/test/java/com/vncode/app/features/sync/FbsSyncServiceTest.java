package com.vncode.app.features.sync;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.wb.*;
import com.vncode.app.models.Shop;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FbsSyncServiceTest {
    @TempDir Path root;Shop shop;MockWebServer server;
    static final Gson JSON=new Gson();
    @BeforeEach void init(){System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();}
    @AfterEach void clear()throws Exception{try{if(server!=null)server.shutdown();}finally{System.clearProperty("vncode.appdata.dir");}}
    void orders(int count)throws Exception{
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO wb_orders(shop_id,order_id,supplier_status,wb_status,synced_at) VALUES(1,?,'new','waiting','now')")){
            c.setAutoCommit(false);for(int id=1;id<=count;id++){p.setLong(1,id);p.addBatch();}p.executeBatch();c.commit();
        }
    }
    int count(String sql)throws Exception{try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){return r.getInt(1);}}
    FbsSyncService wb(WbApiClient client){return new FbsSyncService(s->{throw new AssertionError("Not Ozon");},()->client);}
    @Test void existingFbsStatusTurnsRefreshBoundedKn3BatchesAndRotateEveryOrder()throws Exception {
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);orders(105);var batches=new ArrayList<List<Long>>();
        var marks=new com.vncode.app.features.kiz.status.Kn3SyncService((s,ids)->{batches.add(ids);var result=new HashMap<Long,com.vncode.app.features.kiz.KizService.SgtinMetadata>();for(long id:ids)result.put(id,new com.vncode.app.features.kiz.KizService.SgtinMetadata(true,"synthetic-"+id,id%2==0?"sgtinIntroduced":"sgtinRetired"));return result;});
        var api=new WbApiClient(){@Override public WbOrderStatusesResponse getOrderStatuses(String token,List<Long> ids){var rows=new JsonArray();for(long id:ids){var row=new JsonObject();row.addProperty("id",id);row.addProperty("supplierStatus","confirm");row.addProperty("wbStatus","waiting");rows.add(row);}var body=new JsonObject();body.add("orders",rows);return JSON.fromJson(body,WbOrderStatusesResponse.class);}};
        var service=new FbsSyncService(s->{throw new AssertionError("Not Ozon");},()->api,System::nanoTime,marks);
        service.step(shop,SyncCoordinator.Stream.FBS_STATUS,"");service.step(shop,SyncCoordinator.Stream.FBS_STATUS,"");assertEquals(100,batches.getFirst().size());assertEquals(100,batches.getLast().size());assertTrue(batches.getLast().contains(105L));
        assertEquals(105,count("SELECT count(*) FROM kn3_marks"));assertEquals(52,count("SELECT count(*) FROM kn3_marks WHERE state='ACCEPTED'"));assertEquals(53,count("SELECT count(*) FROM kn3_marks WHERE state='REJECTED'"));
    }
    @Test void kn3ReadFailureDoesNotStrandTheExistingOrderStatusCursor()throws Exception {
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);orders(1205);var batches=new ArrayList<Integer>();
        var api=new WbApiClient(){@Override public WbOrderStatusesResponse getOrderStatuses(String token,List<Long> ids){batches.add(ids.size());var rows=new JsonArray();for(long id:ids){var row=new JsonObject();row.addProperty("id",id);row.addProperty("supplierStatus","confirm");row.addProperty("wbStatus","waiting");rows.add(row);}var body=new JsonObject();body.add("orders",rows);return JSON.fromJson(body,WbOrderStatusesResponse.class);}};
        var marks=new com.vncode.app.features.kiz.status.Kn3SyncService((s,ids)->{throw new java.io.IOException("WB_METADATA_HTTP_401");});var service=new FbsSyncService(s->{throw new AssertionError("Not Ozon");},()->api,System::nanoTime,marks);
        var first=service.step(shop,SyncCoordinator.Stream.FBS_STATUS,"");assertFalse(first.complete());assertNotNull(first.warning());var second=service.step(shop,SyncCoordinator.Stream.FBS_STATUS,first.checkpoint());assertTrue(second.complete());assertEquals(List.of(1000,205),batches);
    }
    @Test void thousandPlusWbStatusesUseBoundedPagesAndResumeBeyondFirstBatch()throws Exception{
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);orders(1205);var batches=new ArrayList<Integer>();
        var service=wb(new WbApiClient(){@Override public WbOrderStatusesResponse getOrderStatuses(String token,List<Long> ids){
            batches.add(ids.size());var rows=new JsonArray();for(long id:ids){var row=new JsonObject();row.addProperty("id",id);row.addProperty("supplierStatus","confirm");row.addProperty("wbStatus","waiting");rows.add(row);}var body=new JsonObject();body.add("orders",rows);return JSON.fromJson(body,WbOrderStatusesResponse.class);
        }});
        var first=service.step(shop,SyncCoordinator.Stream.FBS_STATUS,"");assertFalse(first.complete());assertEquals(1000,first.changed());
        var second=service.step(shop,SyncCoordinator.Stream.FBS_STATUS,first.checkpoint());assertTrue(second.complete());assertEquals(List.of(1000,205),batches);
        assertEquals(1205,count("SELECT count(*) FROM wb_orders WHERE shop_id=1 AND supplier_status='confirm'"));
    }
    @Test void incompleteWbStatusBatchCannotAdvanceOrSilentlyReplaceExistingState()throws Exception{
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);orders(2);
        var service=wb(new WbApiClient(){@Override public WbOrderStatusesResponse getOrderStatuses(String token,List<Long> ids){return JSON.fromJson("{\"orders\":[{\"id\":1,\"supplierStatus\":\"cancel\"}]}",WbOrderStatusesResponse.class);}});
        assertEquals("FBS_INCOMPLETE_RESPONSE",assertThrows(java.io.IOException.class,()->service.step(shop,SyncCoordinator.Stream.FBS_STATUS,"")).getMessage());
        assertEquals(2,count("SELECT count(*) FROM wb_orders WHERE supplier_status='new'"));
    }
    @Test void preferredSupplyRefreshesMembershipAndCountBeforeOtherOpenSupplies()throws Exception{
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);orders(105);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO wb_supplies(shop_id,supply_id,done,synced_at) VALUES(1,'A',0,'now'),(1,'Z',0,'now')");}
        var read=new ArrayList<String>();
        var service=wb(new WbApiClient(){
            @Override public WbSupplyDto getSupplyDetail(String token,String id){read.add(id);return JSON.fromJson("{\"id\":\""+id+"\",\"done\":false}",WbSupplyDto.class);}
            @Override public WbSupplyOrderIdsResponse getSupplyOrderIds(String token,String id){return JSON.fromJson("{\"orderIds\":"+(id.equals("Z")?java.util.stream.LongStream.rangeClosed(1,105).boxed().toList():List.of())+"}",WbSupplyOrderIdsResponse.class);}
        });service.selectSupply(1,"Z");
        var first=service.step(shop,SyncCoordinator.Stream.SUPPLY_CONTENT,"");assertEquals(105,first.changed());assertFalse(first.complete());
        var second=service.step(shop,SyncCoordinator.Stream.SUPPLY_CONTENT,first.checkpoint());assertEquals(List.of("Z","A"),read);
        assertTrue(service.step(shop,SyncCoordinator.Stream.SUPPLY_CONTENT,second.checkpoint()).complete());
        assertEquals(105,count("SELECT count(*) FROM wb_supply_orders WHERE shop_id=1 AND supply_id='Z'"));assertEquals(105,count("SELECT order_count FROM wb_supplies WHERE shop_id=1 AND supply_id='Z'"));
    }
    FbsSyncService ozon()throws Exception{
        shop=GtinWriteTestFixture.shop(Marketplace.OZON);server=new MockWebServer();server.start();
        return new FbsSyncService(s->new OzonApiClient(s.getId(),new OzonCredentials(s.getClientId(),s.getApiKey()),server.url("/"),new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(3)).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))),()->{throw new AssertionError("Not WB");});
    }
    @Test void activeShipmentIsRefreshedDuringALongScanAndChangingItDoesNotLoseBackgroundPosition()throws Exception{
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO wb_supplies(shop_id,supply_id,done,synced_at) VALUES(1,'A',0,'now'),(1,'B',0,'now'),(1,'C',0,'now'),(1,'Y',0,'now'),(1,'Z',0,'now')");}
        var read=new ArrayList<String>();var time=new java.util.concurrent.atomic.AtomicLong();
        var api=new WbApiClient(){
            @Override public WbSupplyDto getSupplyDetail(String token,String id){read.add(id);return JSON.fromJson("{\"id\":\""+id+"\",\"done\":false}",WbSupplyDto.class);}
            @Override public WbSupplyOrderIdsResponse getSupplyOrderIds(String token,String id){return JSON.fromJson("{\"orderIds\":[]}",WbSupplyOrderIdsResponse.class);}
        };
        var service=new FbsSyncService(s->{throw new AssertionError("Not Ozon");},()->api,time::get);
        assertTrue(service.selectSupply(1,"Z"));assertFalse(service.selectSupply(1,"Z"));
        var cursor=service.step(shop,SyncCoordinator.Stream.SUPPLY_CONTENT,"").checkpoint();
        cursor=service.step(shop,SyncCoordinator.Stream.SUPPLY_CONTENT,cursor).checkpoint();
        time.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(11));
        cursor=service.step(shop,SyncCoordinator.Stream.SUPPLY_CONTENT,cursor).checkpoint();
        cursor=service.step(shop,SyncCoordinator.Stream.SUPPLY_CONTENT,cursor).checkpoint();
        assertEquals(List.of("Z","A","Z","B"),read);
        assertTrue(service.selectSupply(1,"Y"));cursor=service.step(shop,SyncCoordinator.Stream.SUPPLY_CONTENT,cursor).checkpoint();
        service.step(shop,SyncCoordinator.Stream.SUPPLY_CONTENT,cursor);
        assertEquals(List.of("Z","A","Z","B","Y","C"),read,"Changing the visible shipment must preempt the scan, then resume it");
    }
    @Test void ozonActivePostingLeavingUnfulfilledQueueIsReconciledByDetailStatus()throws Exception{
        var service=ozon();server.enqueue(new MockResponse().setBody("{\"postings\":[{\"posting_number\":\"101-1-1\",\"status\":\"awaiting_packaging\",\"products\":[]}],\"has_next\":false}"));
        assertTrue(service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,"").complete());
        server.enqueue(new MockResponse().setBody("{\"result\":{\"posting_number\":\"101-1-1\",\"status\":\"cancelled\",\"products\":[]}}"));
        assertTrue(service.step(shop,SyncCoordinator.Stream.FBS_STATUS,"").complete());assertEquals(1,count("SELECT count(*) FROM ozon_postings WHERE shop_id=2 AND status='cancelled'"));
        assertEquals("/v4/posting/fbs/unfulfilled/list",server.takeRequest().getPath());assertEquals("/v3/posting/fbs/get",server.takeRequest().getPath());
    }
    @Test void foreignPendingSplitChildDoesNotBlockIndependentHealthyStatusRefresh()throws Exception{
        var service=ozon();
        var source=OzonJson.parsePostingDetail(unitPosting("PARENT","ORDER","awaiting_packaging",2));
        var healthy=OzonJson.parsePostingDetail(unitPosting("HEALTHY","HEALTHY-ORDER","awaiting_packaging",1));
        var repository=new OzonPostingRepository();repository.upsertPage(shop.getId(),List.of(source,healthy));
        // A frozen receipt from an earlier accepted HTTP request survives app restart.
        try(var c=Database.getConnection();var statement=c.createStatement()){
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_unit_ship_receipts(
                        shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
                        posting_number TEXT NOT NULL,owner TEXT NOT NULL,source_json TEXT NOT NULL,
                        request_json TEXT NOT NULL,request_fingerprint TEXT NOT NULL,
                        children_json TEXT NOT NULL DEFAULT '[]',state TEXT NOT NULL,updated_at TEXT NOT NULL,
                        parent_in_children INTEGER NOT NULL DEFAULT 1,
                        readback_json TEXT NOT NULL DEFAULT '{}',next_child INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(shop_id,posting_number))
                    """);
            var request=OzonPackageBuilder.perUnitPackages(source).toString();
            try(var insert=c.prepareStatement("""
                    INSERT INTO ozon_unit_ship_receipts(shop_id,posting_number,owner,source_json,
                        request_json,request_fingerprint,children_json,state,updated_at)
                    VALUES(?,?,?,?,?,?,'["BROKEN-CHILD","OTHER-CHILD"]','ACCEPTED','2026-10-20T00:00:00Z')
                    """)){
                insert.setInt(1,shop.getId());insert.setString(2,"PARENT");
                insert.setString(3,com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop));
                insert.setString(4,JSON.toJson(source));insert.setString(5,request);
                insert.setString(6,com.vncode.app.integration.listing.ListingIdentity.hash(request));insert.executeUpdate();
            }
        }
        var read=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var unexpected=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){
            try{
                assertEquals("POST",request.getMethod());assertEquals("/v3/posting/fbs/get",request.getPath(),"Status sync must not submit shipping requests");
                var body=JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
                String number=body.get("posting_number").getAsString();read.add(number);
                JsonObject result=switch(number){
                    case "BROKEN-CHILD"->unitPosting(number,"FOREIGN-ORDER","awaiting_deliver",1);
                    case "HEALTHY"->unitPosting(number,"HEALTHY-ORDER","awaiting_deliver",1);
                    default->throw new AssertionError("Unexpected posting read: "+number);
                };
                return new MockResponse().setHeader("Content-Type","application/json").setBody(result.toString());
            }catch(Throwable failure){unexpected.compareAndSet(null,failure);return new MockResponse().setResponseCode(422).setBody("{}");}
        }});
        var page=service.step(shop,SyncCoordinator.Stream.FBS_STATUS,"");
        assertNull(unexpected.get(),()->"HTTP fixture failed: "+unexpected.get());
        assertEquals(List.of("BROKEN-CHILD","HEALTHY"),read);
        assertTrue(server.getRequestCount()<=4,"At most three receipt reads plus the ordinary healthy status read");
        assertTrue(page.complete());assertEquals(1,page.changed());
        assertEquals("awaiting_deliver",repository.find(shop.getId(),"HEALTHY").status());
        assertNotNull(repository.find(shop.getId(),"PARENT"),"Preserve the unresolved source cache/history");
        assertEquals(1,count("SELECT count(*) FROM ozon_unit_ship_receipts WHERE state='ACCEPTED'"));
        assertEquals(0,count("SELECT count(*) FROM ozon_postings WHERE posting_number='BROKEN-CHILD'"));
        assertEquals(0,count("SELECT count(*) FROM ozon_action_log WHERE action_type='ship_units'"));
    }
    private static JsonObject unitPosting(String number,String order,String status,int quantity){
        var response=JsonParser.parseString("""
                {"result":{"posting_number":"fixture","order_id":"fixture","order_number":"ORDER-NO",
                  "status":"awaiting_packaging","delivery_method":{"warehouse_id":"WH"},
                  "products":[{"sku":101,"quantity":1}],"requirements":{},"available_actions":["ship"]}}
                """).getAsJsonObject();
        var posting=response.getAsJsonObject("result");posting.addProperty("posting_number",number);
        posting.addProperty("order_id",order);posting.addProperty("status",status);
        posting.getAsJsonArray("products").get(0).getAsJsonObject().addProperty("quantity",quantity);return response;
    }
    @Test void missingOzonPostingListIsAnErrorRatherThanSuccessfulEmptySnapshot()throws Exception{
        var service=ozon();server.enqueue(new MockResponse().setBody("{}"));
        assertEquals("FBS_INVALID_RESPONSE",assertThrows(java.io.IOException.class,()->service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,"")).getMessage());
    }
}
