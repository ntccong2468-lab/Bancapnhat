package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class OzonAcceptanceKizTest {
    @TempDir Path directory;
    MockWebServer server;Shop shop;boolean multi,pending,rejected,foreign,optional;
    final Map<String,JsonObject> sent=new ConcurrentHashMap<>();
    final Set<String> shipped=ConcurrentHashMap.newKeySet(),accepted=ConcurrentHashMap.newKeySet();
    final List<String> trace=new CopyOnWriteArrayList<>();
    volatile CountDownLatch concurrentChildReads;
    final java.util.concurrent.atomic.AtomicBoolean childReadsOverlapped=new java.util.concurrent.atomic.AtomicBoolean();
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Ozon','OZON','fixture','fixture')");
            s.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(1,'04600000000001','Fixture','2026-08-18T00:00:00Z')");
            s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) VALUES(1,1,'04600000000001',2,'COMPLETED','2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
            for(int i=1;i<=2;i++)s.execute("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) VALUES("+i+",1,1,'010460000000000121FIXTURE"+i+"','fixture','04600000000001','AVAILABLE','IN_CIRCULATION','2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
            s.execute("INSERT INTO ozon_product_gtin_mappings(shop_id,sku,gtin,created_at,updated_at) VALUES(1,'101','04600000000001','2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
        }
        shop=new Shop(1,"Ozon",Marketplace.OZON,"fixture","fixture");server=new MockWebServer();
        server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){
            try{
                var gate=concurrentChildReads;
                if(gate!=null&&request.getPath().equals("/v3/posting/fbs/get")) {
                    var body=JsonParser.parseString(request.getBody().clone().readUtf8()).getAsJsonObject();
                    if(body.get("posting_number").getAsString().startsWith("CHILD-")) {
                        gate.countDown();
                        if(gate.await(2,TimeUnit.SECONDS))childReadsOverlapped.set(true);
                    }
                }
                return respond(request);
            }catch(Throwable failed){return new MockResponse().setResponseCode(500);}
        }});server.start();
    }
    @AfterEach void close()throws Exception{server.shutdown();System.clearProperty("vncode.appdata.dir");}
    @Test void exactPassedKizIsSubmittedBeforeOzonAssembly()throws Exception {
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true).status());
        assertTrue(trace.indexOf("/v6/fbs/posting/product/exemplar/set")<trace.indexOf("/v4/posting/fbs/ship"));
        assertTrue(trace.indexOf("/v5/fbs/posting/product/exemplar/status")<trace.indexOf("/v4/posting/fbs/ship"));
        assertEquals(1,sent.size());assertEquals(1,shipped.size());
        assertEquals("ACCEPTED",new OzonExemplarJobRepository().find(1,"PARENT").stage().name());
    }
    @Test void existingJobWithoutAcceptanceHistoryCanBeAccepted()throws Exception {
        var posting=OzonJson.requirePostingDetail(JsonParser.parseString(posting("PARENT")).getAsJsonObject(),"PARENT");
        new OzonPostingRepository().upsertDetail(1,posting);
        var jobs=new OzonExemplarJobRepository();jobs.findOrCreate(1,"PARENT");
        assertNull(jobs.latestActionStatus(1,"accept_kiz","PARENT"));

        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true).status());
        assertEquals(1,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        assertEquals(1,trace.stream().filter(p->p.equals("/v4/posting/fbs/ship")).count());
        assertEquals("ACCEPTED",jobs.find(1,"PARENT").stage().name());
    }
    @Test void absentActionHistoryAfterAttemptReconcilesInsteadOfResubmittingMarks()throws Exception {
        pending=true;
        assertEquals("OZON_ACCEPT_KIZ_PENDING",assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true)).getMessage());
        var jobs=new OzonExemplarJobRepository();
        assertNotNull(jobs.find(1,"PARENT").mutationAttemptedAt());
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.executeUpdate("DELETE FROM ozon_action_log WHERE shop_id=1 AND posting_number='PARENT' AND action_type='accept_kiz'");
        }
        assertNull(jobs.latestActionStatus(1,"accept_kiz","PARENT"));
        pending=false;
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true).status());
        assertEquals(1,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count(),"Do not resend marks after an uncertain prior attempt");
        assertEquals(1,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/create-or-get")).count());
        assertEquals(1,trace.stream().filter(p->p.equals("/v4/posting/fbs/ship")).count());
        assertEquals("ACCEPTED",jobs.find(1,"PARENT").stage().name());
    }
    @Test void alreadyReservedLocalKizWithoutAcceptanceHistoryIsReused()throws Exception {
        var jobs=new OzonExemplarJobRepository();
        var preparation=new OzonExemplarService(new OzonPostingRepository(),new OzonProductGtinMappingRepository(),jobs,
                (id,credentials)->new OzonApiClient(id,credentials,server.url("/"),new OkHttpClient.Builder().readTimeout(1,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))));
        assertEquals("VALIDATED",preparation.stageForPrint(shop,"PARENT").stage());
        var job=jobs.find(1,"PARENT");var before=jobs.bindings(job.id());
        assertEquals(1,before.size());assertNull(jobs.latestActionStatus(1,"accept_kiz","PARENT"));

        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true).status());
        var after=jobs.bindings(job.id());assertEquals(1,after.size());
        assertEquals(before.getFirst().kizId(),after.getFirst().kizId(),"The original reservation must be used");
        assertEquals(before.getFirst().rawCode(),after.getFirst().rawCode());
        assertEquals(1,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        assertEquals(1,trace.stream().filter(p->p.equals("/v4/posting/fbs/ship")).count());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT SUM(status='CONSUMED'),SUM(status='AVAILABLE'),SUM(status='RESERVED') FROM kiz_codes WHERE shop_id=1")) {
            assertTrue(r.next());assertEquals(1,r.getInt(1));assertEquals(1,r.getInt(2));assertEquals(0,r.getInt(3));
        }
    }
    @Test void batchOf101ExistingJobsWithoutHistoryAssignsDistinctKizAndAcceptsEveryOrder()throws Exception {
        var numbers=java.util.stream.IntStream.rangeClosed(1,101).mapToObj(i->"BATCH-"+i).toList();
        try(var c=Database.getConnection()) {
            c.setAutoCommit(false);
            try(var update=c.createStatement();var insert=c.prepareStatement("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) VALUES(?,1,1,?,?, '04600000000001','AVAILABLE','IN_CIRCULATION','2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')")) {
                update.executeUpdate("UPDATE kiz_orders SET quantity=101 WHERE id=1");
                for(int i=3;i<=101;i++){insert.setInt(1,i);insert.setString(2,"010460000000000121FIXTURE"+i);insert.setString(3,"fixture-"+i);insert.addBatch();}
                insert.executeBatch();c.commit();
            }
        }
        var repository=new OzonPostingRepository();
        var postings=new ArrayList<OzonPostingDto>();
        for(String number:numbers)postings.add(OzonJson.requirePostingDetail(JsonParser.parseString(posting(number)).getAsJsonObject(),number));
        repository.upsertPage(1,postings);
        try(var c=Database.getConnection();var insert=c.prepareStatement("INSERT INTO ozon_exemplar_jobs(shop_id,posting_number,stage,created_at,updated_at) VALUES(1,?,'CREATED','2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')")) {
            for(String number:numbers){insert.setString(1,number);insert.addBatch();}insert.executeBatch();
        }
        var jobs=new OzonExemplarJobRepository();var assigned=new HashSet<Long>();var shipper=service();
        long started=System.nanoTime();
        for(String number:numbers) {
            assertNull(jobs.latestActionStatus(1,"accept_kiz",number));
            assertEquals("awaiting_deliver",shipper.shipPerUnit(shop,number,true).status(),number);
            var job=jobs.find(1,number);assertEquals(OzonExemplarJobStage.ACCEPTED,job.stage());
            var bindings=jobs.bindings(job.id());assertEquals(1,bindings.size());
            assertTrue(assigned.add(bindings.getFirst().kizId()),"A KIZ must not be reused across orders");
        }
        assertEquals(101,assigned.size());assertEquals(101,sent.size());assertEquals(101,shipped.size());
        assertEquals(101,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        assertEquals(101,trace.stream().filter(p->p.equals("/v4/posting/fbs/ship")).count());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT SUM(status='CONSUMED'),SUM(status='AVAILABLE'),SUM(status='RESERVED') FROM kiz_codes WHERE shop_id=1")) {
            assertTrue(r.next());assertEquals(101,r.getInt(1));assertEquals(0,r.getInt(2));assertEquals(0,r.getInt(3));
        }
        System.out.println("OZON_ACCEPTANCE_FIXTURE orders=101 unique_kiz=101 elapsed_ms="+(System.nanoTime()-started)/1_000_000);
    }
    @Test void optionalMarksAreAlsoAssignedBeforeAcceptanceLikeTheRealOzonResponse()throws Exception {
        optional=true;assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true).status());
        assertEquals(1,sent.size());assertTrue(trace.indexOf("/v6/fbs/posting/product/exemplar/set")<trace.indexOf("/v4/posting/fbs/ship"));
    }
    @Test void sellerExemptionSkipsOptionalMarksButNeverMandatoryMarks()throws Exception {
        var mandatory=OzonJson.requirePostingDetail(JsonParser.parseString(posting("PARENT")).getAsJsonObject(),"PARENT");
        new OzonProductKizPolicyRepository().setRequired(1,"101",false);
        assertTrue(OzonAcceptanceKizService.requiredBeforeAccept(shop,mandatory));
        optional=true;var optionalPosting=OzonJson.requirePostingDetail(JsonParser.parseString(posting("PARENT")).getAsJsonObject(),"PARENT");
        assertFalse(OzonAcceptanceKizService.requiredBeforeAccept(shop,optionalPosting));
    }
    @Test void processingKizNeverShipsOrResubmitsTheSameSet()throws Exception {
        pending=true;
        assertEquals("OZON_ACCEPT_KIZ_PENDING",assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true)).getMessage());
        assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true));
        assertEquals(1,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());assertTrue(shipped.isEmpty());
    }
    @Test void rejectedKizCannotBeReportedAsAnAcceptedOrder()throws Exception {
        rejected=true;
        assertEquals("OZON_ACCEPT_KIZ_REJECTED",assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true)).getMessage());
        assertTrue(shipped.isEmpty());
    }
    @Test void acceptanceResumesWhenPendingMarksPassWithoutSendingThemAgain()throws Exception {
        pending=true;
        assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true));
        pending=false;
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true).status());
        assertEquals(1,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        assertEquals(1,trace.stream().filter(p->p.equals("/v4/posting/fbs/ship")).count());
        assertEquals(1,shipped.size());
    }
    @Test void mandatoryMultiUnitOrderSplitsBeforeAssigningKizToEachNativeChild()throws Exception {
        multi=true;var result=service().shipPerUnit(shop,"PARENT",true);
        assertEquals(List.of("CHILD-A","CHILD-B"),result.resultingPostings());assertEquals(Set.of("CHILD-A","CHILD-B"),sent.keySet());
        assertTrue(trace.indexOf("/v1/posting/fbs/split")<trace.indexOf("/v6/fbs/posting/product/exemplar/set"));
        assertEquals(2,shipped.size());assertEquals("COMPLETE",new OzonUnitShipRepository().find(shop,"PARENT").state());
        service().shipPerUnit(shop,"PARENT",true);
        assertEquals(1,trace.stream().filter(p->p.equals("/v1/posting/fbs/split")).count());
        assertEquals(2,trace.stream().filter(p->p.equals("/v4/posting/fbs/ship")).count());
    }
    @Test void markedSplitReadsIndependentChildrenTogetherBeforeAssigningAnyKiz()throws Exception {
        multi=true;concurrentChildReads=new CountDownLatch(2);
        var result=service().shipPerUnit(shop,"PARENT",true);
        assertTrue(childReadsOverlapped.get(),"Both read-only child requests should be in flight together");
        assertEquals(List.of("CHILD-A","CHILD-B"),result.resultingPostings());
        assertEquals(Set.of("CHILD-A","CHILD-B"),sent.keySet());
        assertEquals(Set.of("CHILD-A","CHILD-B"),shipped);
        assertEquals("COMPLETE",new OzonUnitShipRepository().find(shop,"PARENT").state());
    }
    @Test void foreignSplitChildStopsBeforeAnyKizOrShipMutation()throws Exception {
        multi=true;foreign=true;assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true));assertTrue(sent.isEmpty());assertTrue(shipped.isEmpty());
    }
    @Test void acceptanceShortageProposalUsesRefreshedOzonRequirementsAndCancellationNeverBuys()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM kiz_codes WHERE shop_id=1");}
        var preflight=new OzonAcceptanceStockReplenishment(owner->new OzonApiClient(owner.getId(),new OzonCredentials(owner.getClientId(),owner.getApiKey()),server.url("/"),new OkHttpClient.Builder().readTimeout(1,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))));
        var proposal=new java.util.concurrent.atomic.AtomicReference<com.vncode.app.features.kiz.KizPrintPurchaseSession.Proposal>();
        assertThrows(CancellationException.class,()->preflight.run(shop,List.of("PARENT"),new com.vncode.app.features.kiz.KizSourceSelection(true,true),p->{proposal.set(p);return false;},s->{},()->false));
        assertEquals(1,proposal.get().quantity());assertEquals(1,proposal.get().rows().getFirst().required());
        assertEquals(List.of("/v3/posting/fbs/get"),trace);assertTrue(sent.isEmpty());assertTrue(shipped.isEmpty());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM znack_purchase_pipelines")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }
    @Test void acceptancePurchaseDoesNotReadOrBuyWhenSellerDisabledBoughtSource()throws Exception {
        var preflight=new OzonAcceptanceStockReplenishment(owner->{fail("Must not connect when purchase source was not selected");return null;});
        assertEquals("OZON_ACCEPT_KIZ_STOCK_REQUIRED",assertThrows(java.io.IOException.class,()->preflight.run(shop,List.of("PARENT"),com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY,p->true,s->{},()->false)).getMessage());
        assertTrue(trace.isEmpty());
    }
    @Test void alreadySplitPostingProposesOnlyVerifiedChildrenWithoutSplittingAgain()throws Exception {
        multi=true;
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM kiz_codes WHERE shop_id=1");}
        assertEquals("OZON_ACCEPT_KIZ_STOCK_REQUIRED",assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true,new com.vncode.app.features.kiz.KizSourceSelection(true,true))).getMessage());
        assertEquals(1,trace.stream().filter("/v1/posting/fbs/split"::equals).count());trace.clear();
        var preflight=new OzonAcceptanceStockReplenishment(owner->new OzonApiClient(owner.getId(),new OzonCredentials(owner.getClientId(),owner.getApiKey()),server.url("/"),new OkHttpClient.Builder().readTimeout(1,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))));
        var proposal=new java.util.concurrent.atomic.AtomicReference<com.vncode.app.features.kiz.KizPrintPurchaseSession.Proposal>();
        assertThrows(CancellationException.class,()->preflight.run(shop,List.of("PARENT","CHILD-A"),new com.vncode.app.features.kiz.KizSourceSelection(true,true),p->{proposal.set(p);return false;},s->{},()->false));
        assertEquals(2,proposal.get().quantity(),"A child already present alongside its parent must not be bought twice");assertEquals(2,trace.stream().filter("/v3/posting/fbs/get"::equals).count());
        assertFalse(trace.contains("/v1/posting/fbs/split"));assertTrue(sent.isEmpty());assertTrue(shipped.isEmpty());
        trace.clear();
        preflight.run(shop,List.of("CHILD-A","PARENT"),new com.vncode.app.features.kiz.KizSourceSelection(false,true),p->{fail("A resumed split must retain its original source selection");return false;},s->{},()->false);
        assertTrue(sent.isEmpty());assertTrue(shipped.isEmpty());
        trace.clear();
        preflight.run(shop,List.of("CHILD-A"),new com.vncode.app.features.kiz.KizSourceSelection(false,true),p->{fail("A child belonging to a pending parent cannot buy from another source");return false;},s->{},()->false);
        assertTrue(trace.isEmpty());assertTrue(sent.isEmpty());assertTrue(shipped.isEmpty());
    }
    @Test void unmappedOrderDoesNotBlockProposalForAnotherMappedOrder()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM kiz_codes WHERE shop_id=1");}
        var preflight=new OzonAcceptanceStockReplenishment(owner->new OzonApiClient(owner.getId(),new OzonCredentials(owner.getClientId(),owner.getApiKey()),server.url("/"),new OkHttpClient.Builder().readTimeout(1,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))));
        var proposal=new java.util.concurrent.atomic.AtomicReference<com.vncode.app.features.kiz.KizPrintPurchaseSession.Proposal>();
        assertThrows(CancellationException.class,()->preflight.run(shop,List.of("UNMAPPED","PARENT"),new com.vncode.app.features.kiz.KizSourceSelection(true,true),p->{proposal.set(p);return false;},s->{},()->false));
        assertEquals(1,proposal.get().quantity());assertEquals("04600000000001",proposal.get().rows().getFirst().gtin());
        assertEquals(2,trace.stream().filter("/v3/posting/fbs/get"::equals).count());assertTrue(sent.isEmpty());assertTrue(shipped.isEmpty());
    }
    @Test void purchasedOnlyProposalDoesNotCountSellerCategoryStock()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM kiz_codes WHERE shop_id=1");}
        new OzonCatalogRepository().upsertPage(1,List.of(new OzonProductDto("9001","fixture","101","Fixture","",false,"",List.of())),"cursor");
        com.vncode.app.testsupport.CategoryStockFixture.add(shop,"9001",directory,1);
        var preflight=new OzonAcceptanceStockReplenishment(owner->new OzonApiClient(owner.getId(),new OzonCredentials(owner.getClientId(),owner.getApiKey()),server.url("/"),new OkHttpClient.Builder().readTimeout(1,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))));
        var proposal=new java.util.concurrent.atomic.AtomicReference<com.vncode.app.features.kiz.KizPrintPurchaseSession.Proposal>();
        assertThrows(CancellationException.class,()->preflight.run(shop,List.of("PARENT"),new com.vncode.app.features.kiz.KizSourceSelection(false,true),p->{proposal.set(p);return false;},s->{},()->false));
        assertEquals(1,proposal.get().quantity(),"Seller stock must not reduce a purchased-only proposal");
        preflight.run(shop,List.of("PARENT"),new com.vncode.app.features.kiz.KizSourceSelection(true,true),p->{fail("Seller category covers this order");return false;},s->{},()->false);
        assertTrue(sent.isEmpty());assertTrue(shipped.isEmpty());
    }
    private OzonShipService service(){return new OzonShipService(new OzonPostingRepository(),new OzonProductGtinMappingRepository(),new OzonExemplarJobRepository(),(id,credentials)->new OzonApiClient(id,credentials,server.url("/"),new OkHttpClient.Builder().readTimeout(1,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))));}
    private MockResponse respond(RecordedRequest request) {
        String path=request.getPath();trace.add(path);var body=JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();String number=OzonJson.text(body,"posting_number");
        if(path.equals("/v3/posting/fbs/get"))return json(posting(number));
        if(path.equals("/v1/posting/fbs/split"))return json("{\"parent_posting\":{\"posting_number\":\"PARENT\",\"products\":[]},\"postings\":[{\"posting_number\":\"CHILD-A\"},{\"posting_number\":\"CHILD-B\"}]}");
        if(path.equals("/v6/fbs/posting/product/exemplar/create-or-get"))return json("{\"result\":{\"products\":[{\"product_id\":101,\"exemplars\":[{\"exemplar_id\":7001}]}]}}");
        if(path.equals("/v5/fbs/posting/product/exemplar/validate"))return json("{\"products\":[{\"exemplars\":[{\"valid\":true}]}]}");
        if(path.equals("/v6/fbs/posting/product/exemplar/set")){sent.put(number,body);return json("{}");}
        if(path.equals("/v5/fbs/posting/product/exemplar/status")) {
            var raw=sent.get(number).deepCopy();raw.addProperty("status",pending?"validation_in_process":"ship_available");
            for(var p:raw.getAsJsonArray("products"))for(var e:p.getAsJsonObject().getAsJsonArray("exemplars"))for(var m:e.getAsJsonObject().getAsJsonArray("marks")){
                var mark=m.getAsJsonObject();mark.addProperty("check_status",pending?"processing":rejected?"failed":"passed");var errors=new JsonArray();if(rejected)errors.add("NOT_IN_CIRCULATION");mark.add("error_codes",errors);
            }
            if(!pending&&!rejected)accepted.add(number);return json(raw.toString());
        }
        if(path.equals("/v4/posting/fbs/ship")) {assertTrue(accepted.contains(number));shipped.add(number);return json("{\"result\":[\""+number+"\"]}");}
        return new MockResponse().setResponseCode(404);
    }
    private String posting(String number) {
        String status=shipped.contains(number)?"awaiting_deliver":"awaiting_packaging";
        int product=number.equals("UNMAPPED")?202:101;
        return "{\"result\":{\"posting_number\":\""+number+"\",\"order_id\":\""+(foreign&&number.equals("CHILD-B")?"FOREIGN":"ORDER")+"\",\"delivery_method\":{\"warehouse_id\":\"WH\"},\"status\":\""+status+"\",\"products\":[{\"sku\":"+product+",\"offer_id\":\"fixture\",\"name\":\"Fixture\",\"quantity\":"+(multi&&number.equals("PARENT")?2:1)+"}],"+(optional?"\"requirements\":{},\"optional\":{\"products_with_possible_mandatory_mark\":["+product+"]},":"\"requirements\":{\"products_requiring_mandatory_mark\":[\""+product+"\"]},")+"\"available_actions\":"+(accepted.contains(number)?"[\"ship\"]":"[]")+",\"ship_available\":"+accepted.contains(number)+"}}";
    }
    private static MockResponse json(String value){return new MockResponse().setHeader("Content-Type","application/json").setBody(value);}
}
