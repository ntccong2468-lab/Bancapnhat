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
        var service=ozon();var initial=new JsonArray();initial.add(unitPosting("101-1-1","ORDER","awaiting_packaging",1).getAsJsonObject("result"));var queue=new JsonObject();queue.add("postings",initial);queue.addProperty("has_next",false);server.enqueue(new MockResponse().setBody(queue.toString()));
        assertTrue(service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,"").complete());
        server.enqueue(new MockResponse().setBody("{\"postings\":[],\"has_next\":false}"));
        var first=service.step(shop,SyncCoordinator.Stream.FBS_STATUS,"");assertFalse(first.complete());
        server.enqueue(new MockResponse().setBody(unitPosting("101-1-1","ORDER","cancelled",1).toString()));
        assertTrue(service.step(shop,SyncCoordinator.Stream.FBS_STATUS,first.checkpoint()).complete());assertEquals(1,count("SELECT count(*) FROM ozon_postings WHERE shop_id=2 AND status='cancelled'"));
        assertEquals("/v4/posting/fbs/unfulfilled/list",server.takeRequest().getPath());assertEquals("/v4/posting/fbs/list",server.takeRequest().getPath());assertEquals("/v3/posting/fbs/get",server.takeRequest().getPath());
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
                assertEquals("POST",request.getMethod());
                if(request.getPath().equals("/v4/posting/fbs/list"))return new MockResponse().setBody("{\"postings\":[],\"has_next\":false}");
                assertEquals("/v3/posting/fbs/get",request.getPath(),"Status sync must not submit shipping requests");
                var body=JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
                String number=body.get("posting_number").getAsString();read.add(number);
                JsonObject result=switch(number){
                    case "BROKEN-CHILD","OTHER-CHILD"->unitPosting(number,"FOREIGN-ORDER","awaiting_deliver",1);
                    case "HEALTHY"->unitPosting(number,"HEALTHY-ORDER","awaiting_deliver",1);
                    default->throw new AssertionError("Unexpected posting read: "+number);
                };
                return new MockResponse().setHeader("Content-Type","application/json").setBody(result.toString());
            }catch(Throwable failure){unexpected.compareAndSet(null,failure);return new MockResponse().setResponseCode(422).setBody("{}");}
        }});
        var listPage=service.step(shop,SyncCoordinator.Stream.FBS_STATUS,"");
        var page=service.step(shop,SyncCoordinator.Stream.FBS_STATUS,listPage.checkpoint());
        assertNull(unexpected.get(),()->"HTTP fixture failed: "+unexpected.get());
        assertEquals(3,read.size());assertTrue(read.contains("HEALTHY"));
        assertTrue(read.stream().filter(number->!number.equals("HEALTHY")).allMatch(number->number.equals("BROKEN-CHILD")||number.equals("OTHER-CHILD")));
        assertTrue(server.getRequestCount()<=5,"Two bounded receipt reads, one list, and one healthy status read");
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
                  "products":[{"sku":101,"quantity":1}],"requirements":{"products_requiring_mandatory_mark":[]},"optional":{"products_with_possible_mandatory_mark":[]},"barcodes":{"lower_barcode":"","upper_barcode":""},"available_actions":["ship"]}}
                """).getAsJsonObject();
        var posting=response.getAsJsonObject("result");posting.addProperty("posting_number",number);
        posting.addProperty("order_id",order);posting.addProperty("status",status);
        posting.getAsJsonArray("products").get(0).getAsJsonObject().addProperty("quantity",quantity);return response;
    }
    @Test void missingOzonPostingListIsAnErrorRatherThanSuccessfulEmptySnapshot()throws Exception{
        var service=ozon();server.enqueue(new MockResponse().setBody("{}"));
        assertEquals("FBS_INVALID_RESPONSE",assertThrows(java.io.IOException.class,()->service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,"")).getMessage());
    }
    @Test void ozonMissingStatusDoesNotOverwriteVerifiedOrderOrClaimFullMembership()throws Exception{
        var service=ozon();
        var valid=unitPosting("101-1-1","ORDER","awaiting_packaging",1).getAsJsonObject("result");var validRows=new JsonArray();validRows.add(valid);var validResponse=new JsonObject();validResponse.add("postings",validRows);validResponse.addProperty("has_next",false);server.enqueue(new MockResponse().setBody(validResponse.toString()));
        assertTrue(service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,"").complete());
        String oldScan;try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT completed_at FROM ozon_fbs_queue_scans WHERE shop_id=2")){assertTrue(r.next());oldScan=r.getString(1);}
        server.enqueue(new MockResponse().setBody("{\"postings\":[{\"posting_number\":\"101-1-1\",\"products\":[]}],\"has_next\":true,\"cursor\":\"page-2\"}"));
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{}"));
        var first=service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,"");assertFalse(first.complete());
        server.enqueue(new MockResponse().setBody("{\"postings\":[],\"has_next\":false}"));
        var last=service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,first.checkpoint());assertTrue(last.complete());assertEquals("FBS_STATUS_UNCLASSIFIED",last.warning().code());
        assertEquals(1,count("SELECT count(*) FROM ozon_postings WHERE shop_id=2 AND status='awaiting_packaging'"));
        assertEquals(0,count("SELECT count(*) FROM ozon_postings WHERE shop_id=2 AND status='unknown'"));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT completed_at FROM ozon_fbs_queue_scans WHERE shop_id=2")){assertTrue(r.next());assertEquals(oldScan,r.getString(1));}
    }
    @Test void ozonStatusPassUsesPagesAndPreservesProductAndKizDetails()throws Exception{
        var service=ozon();var repository=new OzonPostingRepository();var cached=new ArrayList<OzonPostingDto>();
        var first=new JsonArray();var second=new JsonArray();
        for(int i=0;i<102;i++){
            String id=String.format("P-%03d",i);
            var before=unitPosting(id,"ORDER-"+i,"awaiting_packaging",1);
            var item=before.getAsJsonObject("result");
            if(i==0){var barcodes=new JsonObject();barcodes.addProperty("lower_barcode","KEEP-LABEL");item.add("barcodes",barcodes);}
            cached.add(OzonJson.parsePostingDetail(before));
            var current=unitPosting(id,"ORDER-"+i,"awaiting_deliver",5).getAsJsonObject("result");
            if(i<100)first.add(current);else second.add(current);
        }
        repository.upsertPage(shop.getId(),cached);
        var body1=new JsonObject();body1.add("postings",first);body1.addProperty("has_next",true);body1.addProperty("cursor","page-2");
        var body2=new JsonObject();body2.add("postings",second);body2.addProperty("has_next",false);
        server.enqueue(new MockResponse().setBody(body1.toString()));server.enqueue(new MockResponse().setBody(body2.toString()));
        var page1=service.step(shop,SyncCoordinator.Stream.FBS_STATUS,"");assertFalse(page1.complete());
        var page2=service.step(shop,SyncCoordinator.Stream.FBS_STATUS,page1.checkpoint());assertFalse(page2.complete());
        var done=service.step(shop,SyncCoordinator.Stream.FBS_STATUS,page2.checkpoint());assertTrue(done.complete());
        assertEquals(102,count("SELECT count(*) FROM ozon_postings WHERE shop_id=2 AND status='awaiting_deliver'"));
        assertEquals(1,repository.find(shop.getId(),"P-000").items().getFirst().quantity());
        assertEquals("KEEP-LABEL",repository.find(shop.getId(),"P-000").lowerBarcode());
        assertEquals(2,server.getRequestCount(),"Orders seen in a complete list pass do not need individual status reads");
        assertEquals("desc",JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject().get("sort_dir").getAsString());
    }
    @Test void ozonSlowListCannotOverwriteNewerAcceptedOrderOrClaimComplete()throws Exception{
        var service=ozon();var repository=new OzonPostingRepository();
        var before=OzonJson.parsePostingDetail(unitPosting("P-1","ORDER","awaiting_packaging",1));repository.upsertDetail(shop.getId(),before);
        server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){
            repository.upsertDetail(shop.getId(),OzonJson.parsePostingDetail(unitPosting("P-1","ORDER","awaiting_deliver",1)));
            var stale=unitPosting("P-1","ORDER","awaiting_packaging",1).getAsJsonObject("result");
            var list=new JsonArray();list.add(stale);var body=new JsonObject();body.add("postings",list);body.addProperty("has_next",false);return new MockResponse().setBody(body.toString());
        }});
        var page=service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,"");
        assertEquals("awaiting_deliver",repository.find(shop.getId(),"P-1").status());
        assertNotNull(page.warning());assertEquals("FBS_CONCURRENT_REFRESH",page.warning().code());
        assertTrue(page.complete());assertEquals("",page.checkpoint(),"Retry a fresh complete scan after the race");
    }
    @Test void ozonMarkedOrderStillGetsDetailRefreshAfterStatusList()throws Exception{
        var service=ozon();var repository=new OzonPostingRepository();
        var response=unitPosting("P-MARK","ORDER","awaiting_packaging",1);
        var requirements=new JsonObject();var marked=new JsonArray();marked.add(101);requirements.add("products_requiring_mandatory_mark",marked);
        response.getAsJsonObject("result").add("requirements",requirements);repository.upsertDetail(shop.getId(),OzonJson.parsePostingDetail(response));
        var list=new JsonArray();list.add(response.getAsJsonObject("result"));var body=new JsonObject();body.add("postings",list);body.addProperty("has_next",false);
        server.enqueue(new MockResponse().setBody(body.toString()));server.enqueue(new MockResponse().setBody(response.toString()));
        var first=service.step(shop,SyncCoordinator.Stream.FBS_STATUS,"");assertFalse(first.complete());
        assertTrue(service.step(shop,SyncCoordinator.Stream.FBS_STATUS,first.checkpoint()).complete());
        assertEquals("/v4/posting/fbs/list",server.takeRequest().getPath());assertEquals("/v3/posting/fbs/get",server.takeRequest().getPath());
    }
    @Test void ozonIncompleteQueueDoesNotEraseKizOrLabelsAndKeepsNewOrderVisibleButBlocked()throws Exception{
        var service=ozon();var repository=new OzonPostingRepository();
        var old=unitPosting("P-OLD","ORDER","awaiting_packaging",1);
        var oldItem=old.getAsJsonObject("result");oldItem.getAsJsonObject("barcodes").addProperty("lower_barcode","KEEP");
        oldItem.getAsJsonObject("requirements").getAsJsonArray("products_requiring_mandatory_mark").add(101);
        repository.upsertDetail(shop.getId(),OzonJson.parsePostingDetail(old));
        var missing=unitPosting("P-OLD","ORDER","awaiting_packaging",1).getAsJsonObject("result");missing.remove("requirements");missing.remove("barcodes");
        var newPartial=unitPosting("P-NEW","ORDER-2","awaiting_packaging",1).getAsJsonObject("result");newPartial.remove("available_actions");
        var complete=unitPosting("P-OK","ORDER-3","awaiting_packaging",1).getAsJsonObject("result");
        var list=new JsonArray();list.add(missing);list.add(newPartial);list.add(complete);
        var response=new JsonObject();response.add("postings",list);response.addProperty("has_next",false);server.enqueue(new MockResponse().setBody(response.toString()));
        var firstDetail=new JsonObject();firstDetail.add("result",missing);server.enqueue(new MockResponse().setBody(firstDetail.toString()));
        var secondDetail=new JsonObject();secondDetail.add("result",newPartial);server.enqueue(new MockResponse().setBody(secondDetail.toString()));
        var result=service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,"");
        assertEquals("FBS_INCOMPLETE_POSTING",result.warning().code());
        assertEquals("KEEP",repository.find(shop.getId(),"P-OLD").lowerBarcode());
        assertEquals(List.of("101"),repository.find(shop.getId(),"P-OLD").requirements().mandatoryMarkProductIds());
        assertTrue(repository.find(shop.getId(),"P-NEW").requirements().blocksPreparation());
        assertFalse(repository.find(shop.getId(),"P-NEW").canShip());
        assertFalse(repository.find(shop.getId(),"P-OK").requirements().blocksPreparation());
        assertEquals(1,count("SELECT count(*) FROM ozon_fbs_queue_scans WHERE shop_id=2 AND incomplete=1"));
    }
    @Test void ozonQueueRejectsNonAdjacentCursorCycleAcrossRestartAndRetainsOrders()throws Exception{
        var service=ozon();
        for(int i=0;i<3;i++){
            var list=new JsonArray();list.add(unitPosting("P-"+i,"ORDER-"+i,"awaiting_packaging",1).getAsJsonObject("result"));
            var response=new JsonObject();response.add("postings",list);response.addProperty("has_next",true);response.addProperty("cursor",i==1?"B":"A");server.enqueue(new MockResponse().setBody(response.toString()));
        }
        var first=service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,"");
        var second=service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,first.checkpoint());
        // Cursor history is persisted in the checkpoint, so a new service instance can detect the cycle too.
        var resumed=new FbsSyncService(s->new OzonApiClient(s.getId(),new OzonCredentials(s.getClientId(),s.getApiKey()),server.url("/"),new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(3)).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))),()->{throw new AssertionError("Not WB");});
        assertEquals("FBS_INVALID_CURSOR",assertThrows(java.io.IOException.class,()->resumed.step(shop,SyncCoordinator.Stream.FBS_QUEUE,second.checkpoint())).getMessage());
        assertEquals(3,count("SELECT count(*) FROM ozon_postings WHERE shop_id=2"));
    }
    @Test void ozonPartialReactivatedOrderRemainsVisibleAndUnblocksAfterFullDetail()throws Exception{
        var service=ozon();var repository=new OzonPostingRepository();
        var old=unitPosting("P-OLD","ORDER","cancelled",1);old.getAsJsonObject("result").getAsJsonObject("barcodes").addProperty("lower_barcode","KEEP");
        repository.upsertDetail(shop.getId(),OzonJson.parsePostingDetail(old));
        var partial=unitPosting("P-OLD","ORDER","awaiting_packaging",1).getAsJsonObject("result");partial.remove("requirements");
        var rows=new JsonArray();rows.add(partial);var page=new JsonObject();page.add("postings",rows);page.addProperty("has_next",false);
        server.enqueue(new MockResponse().setBody(page.toString()));server.enqueue(new MockResponse().setResponseCode(404).setBody("{}"));
        var first=service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,"");
        assertEquals("FBS_INCOMPLETE_POSTING",first.warning().code());
        var preserved=repository.find(shop.getId(),"P-OLD");assertEquals("awaiting_packaging",preserved.status());assertEquals("KEEP",preserved.lowerBarcode());assertTrue(preserved.requirements().blocksPreparation());assertFalse(preserved.canShip());
        server.enqueue(new MockResponse().setBody(page.toString()));server.enqueue(new MockResponse().setBody(unitPosting("P-OLD","ORDER","awaiting_packaging",1).toString()));
        var second=service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,"");assertNull(second.warning());assertFalse(repository.find(shop.getId(),"P-OLD").requirements().blocksPreparation());assertTrue(repository.find(shop.getId(),"P-OLD").canShip());
    }
    @Test void ozonEmptyProductsInAnActiveOrderCannotEraseExistingProducts()throws Exception{
        var service=ozon();var repository=new OzonPostingRepository();
        var old=unitPosting("P-EMPTY","ORDER","awaiting_packaging",1);repository.upsertDetail(shop.getId(),OzonJson.parsePostingDetail(old));
        var partial=unitPosting("P-EMPTY","ORDER","awaiting_packaging",1).getAsJsonObject("result");partial.add("products",new JsonArray());
        var rows=new JsonArray();rows.add(partial);var page=new JsonObject();page.add("postings",rows);page.addProperty("has_next",false);
        server.enqueue(new MockResponse().setBody(page.toString()));server.enqueue(new MockResponse().setResponseCode(404).setBody("{}"));
        var result=service.step(shop,SyncCoordinator.Stream.FBS_QUEUE,"");assertEquals("FBS_INCOMPLETE_POSTING",result.warning().code());
        var saved=repository.find(shop.getId(),"P-EMPTY");assertEquals(1,saved.items().size());assertTrue(saved.requirements().blocksPreparation());
    }
}
