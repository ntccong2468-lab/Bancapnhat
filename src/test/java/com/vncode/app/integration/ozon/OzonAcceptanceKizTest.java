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
    MockWebServer server;Shop shop;boolean multi,pending,rejected,foreign,optional,optionalNoMarks,requirementsMissing,validationRejected,firstChildRejected;
    final Map<String,JsonObject> sent=new ConcurrentHashMap<>();
    final Set<String> shipped=ConcurrentHashMap.newKeySet(),accepted=ConcurrentHashMap.newKeySet();
    final List<String> trace=new CopyOnWriteArrayList<>();
    boolean rejectFirstImportedMark,rejectFirstImportedValidation,holdReplacementValidation,foreignValidationMark,invalidGtd,statusMetadata,rejectSecondCandidateValidation;
    String firstImportedMark,secondImportedMark;
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
    @Test void sellerImportedCategoryAcceptsWithoutProductGtinAndKeepsPurchasedCodesUnused()throws Exception {
        new OzonCatalogRepository().upsertPage(1,List.of(new OzonProductDto("9001","fixture","101","Fixture","",false,"",List.of())),"");
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM ozon_product_gtin_mappings");}
        com.vncode.app.testsupport.CategoryStockFixture.add(shop,"9001",directory,1);
        var result=service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY);
        assertEquals("awaiting_deliver",result.status());
        assertEquals(Set.of("PARENT"),shipped);
        assertEquals(1,sent.size());
        try(var c=Database.getConnection();var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT k.gtin,k.status FROM ozon_exemplars e JOIN kiz_codes k ON k.id=e.kiz_id")) {
                assertTrue(r.next());assertEquals(com.vncode.app.testsupport.CategoryStockFixture.GTIN,r.getString(1));
                assertEquals("CONSUMED",r.getString(2));
            }
            try(var r=s.executeQuery("SELECT count(*) FROM kiz_codes WHERE order_id=1 AND status='AVAILABLE'")) {
                assertTrue(r.next());assertEquals(2,r.getInt(1));
            }
        }
    }
    @Test void rejectedImportedMarkIsReplacedFromTheSameCategoryBeforeReceipt()throws Exception {
        importedCategoryStock(2);rejectFirstImportedMark=true;
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true,
                com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY).status());
        assertEquals(2,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        assertEquals(1,shipped.size());
        var bound=new OzonExemplarJobRepository().bindings(new OzonExemplarJobRepository().find(1,"PARENT").id());
        assertNotEquals(firstImportedMark,bound.getFirst().rawCode());
        var observations=new com.vncode.app.features.kiz.status.Kn3Repository().read(shop,"PARENT");
        assertEquals(1,observations.size());
        assertEquals(com.vncode.app.features.kiz.status.Kn3Mark.State.ACCEPTED,observations.getFirst().state(),
                "A successful automatic replacement must remove the stale red KN3 target");
        assertEquals(com.vncode.app.features.kiz.status.Kn3Mark.fingerprint(bound.getFirst().rawCode()),observations.getFirst().fingerprint());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM kiz_rejected_stock WHERE shop_id=1")){
            assertTrue(r.next());assertEquals(1,r.getInt(1));
        }
    }
    @Test void rejectedStockYieldsAfterABoundedPassAndAFreshAcceptedReadNeverBurnsAnotherCode()throws Exception {
        importedCategoryStock(20);rejected=true;
        var progress=new ArrayList<OzonKizProgress.Stage>();
        try(var scope=OzonKizProgress.listen(progress::add)){
            assertEquals("OZON_ACCEPT_KIZ_REJECTED",assertThrows(java.io.IOException.class,()->
                    service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY)).getMessage());
        }
        assertEquals(OzonAcceptanceKizService.MAX_MARK_ATTEMPTS,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        assertTrue(progress.containsAll(List.of(OzonKizProgress.Stage.READ,OzonKizProgress.Stage.VALIDATE,
                OzonKizProgress.Stage.REPLACE,OzonKizProgress.Stage.VERIFY)));
        var jobs=new OzonExemplarJobRepository();var job=jobs.find(1,"PARENT");
        assertNull(new OzonAcceptanceKizReplacement().active(shop,job),"No unsent extra candidate may be held at the pass boundary");
        assertThrows(IllegalStateException.class,()->jobs.consumeAccepted(job),"A retired code cannot be accepted without fresh exact readback");
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM kiz_codes WHERE id IN (SELECT code_id FROM kiz_import_entries) AND status='AVAILABLE'")){
            assertTrue(r.next());assertEquals(20-OzonAcceptanceKizService.MAX_MARK_ATTEMPTS,r.getInt(1));
        }
        rejected=false;
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY).status());
        assertEquals(OzonAcceptanceKizService.MAX_MARK_ATTEMPTS,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        assertEquals(com.vncode.app.features.kiz.status.Kn3Mark.State.ACCEPTED,
                new com.vncode.app.features.kiz.status.Kn3Repository().read(shop,"PARENT").getFirst().state());
    }
    @Test void invalidImportedMarkInValidationIsReplacedWithoutSubmittingIt()throws Exception {
        importedCategoryStock(2);rejectFirstImportedValidation=true;
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true,
                com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY).status());
        assertEquals(2,trace.stream().filter(p->p.equals("/v5/fbs/posting/product/exemplar/validate")).count());
        assertEquals(1,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        assertNotEquals(firstImportedMark,sent.get("PARENT").getAsJsonArray("products").get(0).getAsJsonObject()
                .getAsJsonArray("exemplars").get(0).getAsJsonObject().getAsJsonArray("marks").get(0).getAsJsonObject().get("mark").getAsString());
    }
    @Test void printAutomaticallyRepairsARejectedMarkAfterReceiptWithoutShippingAgain()throws Exception {
        importedCategoryStock(3);
        service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY);
        var jobs=new OzonExemplarJobRepository();var original=jobs.bindings(jobs.find(1,"PARENT").id()).getFirst();
        firstImportedMark=original.rawCode();rejectFirstImportedMark=true;optional=true;optionalNoMarks=true;
        new com.vncode.app.features.kiz.status.Kn3Repository().observe(shop,"PARENT",List.of(
                new com.vncode.app.features.kiz.status.Kn3Mark("PARENT",original.productId(),original.exemplarId(),original.rawCode(),
                        com.vncode.app.features.kiz.status.Kn3Mark.State.REJECTED,"invalid_mark")));
        var batch=exemplarService().openPrintBatch(shop,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY);
        assertTrue(batch.repairRejectedForPrint("PARENT"));
        var replacement=jobs.bindings(jobs.find(1,"PARENT").id()).getFirst();
        assertNotEquals(original.kizId(),replacement.kizId());
        assertEquals(original.productId(),replacement.productId());assertEquals(original.exemplarId(),replacement.exemplarId());
        assertEquals("ACCEPTED",batch.stageForPrint("PARENT").stage());
        assertEquals(2,trace.stream().filter("/v6/fbs/posting/product/exemplar/set"::equals).count());
        assertEquals(1,trace.stream().filter("/v4/posting/fbs/ship"::equals).count());
        assertEquals(com.vncode.app.features.kiz.status.Kn3Mark.State.ACCEPTED,
                new com.vncode.app.features.kiz.status.Kn3Repository().read(shop,"PARENT").getFirst().state());
        int reads=trace.size();assertFalse(batch.repairRejectedForPrint("PARENT"));assertEquals(reads,trace.size());
    }
    @Test void staleRedPrintStateReadsFreshOzonBeforeReservingAReplacement()throws Exception {
        importedCategoryStock(3);
        service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY);
        var jobs=new OzonExemplarJobRepository();var original=jobs.bindings(jobs.find(1,"PARENT").id()).getFirst();
        new com.vncode.app.features.kiz.status.Kn3Repository().observe(shop,"PARENT",List.of(
                new com.vncode.app.features.kiz.status.Kn3Mark("PARENT",original.productId(),original.exemplarId(),original.rawCode(),
                        com.vncode.app.features.kiz.status.Kn3Mark.State.REJECTED,"invalid_mark")));
        assertTrue(exemplarService().openPrintBatch(shop,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY).repairRejectedForPrint("PARENT"));
        assertEquals(original.kizId(),jobs.bindings(jobs.find(1,"PARENT").id()).getFirst().kizId());
        assertEquals(1,trace.stream().filter("/v6/fbs/posting/product/exemplar/set"::equals).count());
        assertEquals(com.vncode.app.features.kiz.status.Kn3Mark.State.ACCEPTED,
                new com.vncode.app.features.kiz.status.Kn3Repository().read(shop,"PARENT").getFirst().state());
    }
    @Test void exhaustedImportedCategoryNeverUsesPurchasedStockOrResubmitsTheDiscardedCode()throws Exception {
        importedCategoryStock(1);rejected=true;
        for(int attempt=0;attempt<2;attempt++)assertEquals("OZON_ACCEPT_KIZ_REJECTED",assertThrows(java.io.IOException.class,
                ()->service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY)).getMessage());
        assertTrue(shipped.isEmpty());assertEquals(1,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM kiz_codes WHERE order_id=1 AND status='AVAILABLE'")){
            assertTrue(r.next());assertEquals(2,r.getInt(1));
        }
    }
    @Test void pendingImportedCodeIsReconciledWithoutReplacingOrResubmittingIt()throws Exception {
        importedCategoryStock(2);pending=true;
        for(int attempt=0;attempt<2;attempt++)assertEquals("OZON_ACCEPT_KIZ_PENDING",assertThrows(java.io.IOException.class,
                ()->service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY)).getMessage());
        assertEquals(1,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM kiz_rejected_stock")){
            assertTrue(r.next());assertEquals(0,r.getInt(1));
        }
    }
    @Test void preparedImportedReplacementSurvivesANewServiceInstance()throws Exception {
        importedCategoryStock(3);rejectFirstImportedValidation=true;holdReplacementValidation=true;
        assertEquals("OZON_ACCEPT_KIZ_PENDING",assertThrows(java.io.IOException.class,
                ()->service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY)).getMessage());
        var jobs=new OzonExemplarJobRepository();var job=jobs.find(1,"PARENT");long held=jobs.bindings(job.id()).getFirst().kizId();
        assertEquals("PREPARED",new OzonAcceptanceKizReplacement().active(shop,job).state());
        Database.initDatabase(); // Re-open an existing database as startup does; retain the unsent intent.
        holdReplacementValidation=false;
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY).status());
        assertEquals(held,jobs.bindings(job.id()).getFirst().kizId());
        assertEquals(1,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        assertNull(new OzonAcceptanceKizReplacement().active(shop,job));
    }
    @Test void validationForAnotherProductCannotDiscardOrReplaceTheLocalMark()throws Exception {
        importedCategoryStock(2);rejectFirstImportedValidation=true;foreignValidationMark=true;
        assertEquals("OZON_ACCEPT_KIZ_REJECTED",assertThrows(java.io.IOException.class,
                ()->service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY)).getMessage());
        assertTrue(sent.isEmpty());assertEquals(1,trace.stream().filter(p->p.equals("/v5/fbs/posting/product/exemplar/validate")).count());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM kiz_rejected_stock")){
            assertTrue(r.next());assertEquals(0,r.getInt(1));
        }
    }
    @Test void invalidGtdCannotRetireAValidImportedMark()throws Exception {
        importedCategoryStock(2);invalidGtd=true;
        assertEquals("OZON_ACCEPT_KIZ_REJECTED",assertThrows(java.io.IOException.class,
                ()->service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY)).getMessage());
        assertTrue(sent.isEmpty());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM kiz_rejected_stock")){
            assertTrue(r.next());assertEquals(0,r.getInt(1));
        }
    }
    @Test void interruptedReplacementAfterTheMutationBoundaryIsReadBackWithoutAnotherSet()throws Exception {
        importedCategoryStock(3);rejectFirstImportedValidation=true;holdReplacementValidation=true;
        assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY));
        var jobs=new OzonExemplarJobRepository();var job=jobs.find(1,"PARENT");var replacements=new OzonAcceptanceKizReplacement();
        var ticket=replacements.active(shop,job);long code=jobs.bindings(job.id()).getFirst().kizId();
        assertTrue(replacements.claim(shop,job,ticket,com.vncode.app.integration.listing.ListingIdentity.hash(ticket.payload().toString())));
        var oldRemote=ticket.payload().deepCopy();oldRemote.getAsJsonArray("products").get(0).getAsJsonObject()
                .getAsJsonArray("exemplars").get(0).getAsJsonObject().getAsJsonArray("marks").get(0).getAsJsonObject().addProperty("mark",firstImportedMark);
        sent.put("PARENT",oldRemote);rejectFirstImportedMark=true;holdReplacementValidation=false;
        Database.initDatabase();
        assertEquals("OZON_ACCEPT_KIZ_PENDING",assertThrows(java.io.IOException.class,
                ()->service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY)).getMessage());
        assertEquals(0,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        assertEquals(code,jobs.bindings(job.id()).getFirst().kizId());assertEquals("SENDING",replacements.active(shop,job).state());
    }
    @Test void replacementKeepsOtherRemoteMarksAndExemplarMetadata()throws Exception {
        importedCategoryStock(2);rejectFirstImportedMark=true;statusMetadata=true;
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY).status());
        var exemplar=sent.get("PARENT").getAsJsonArray("products").get(0).getAsJsonObject().getAsJsonArray("exemplars").get(0).getAsJsonObject();
        assertEquals("GTD-ORIGINAL",exemplar.get("gtd").getAsString());assertEquals("RNPT-ORIGINAL",exemplar.get("rnpt").getAsString());
        assertEquals(2,exemplar.getAsJsonArray("marks").size());
        assertEquals("SIBLING-ORIGINAL",exemplar.getAsJsonArray("marks").get(1).getAsJsonObject().get("mark").getAsString());
    }
    @Test void exhaustedGiletPoolCannotTakeAnImportedMarkAssignedToAnotherProductCategory()throws Exception {
        importedCategoryStock(1);rejected=true;
        new OzonCatalogRepository().upsertPage(1,List.of(new OzonProductDto("9002","other","202","Other","",false,"",List.of())),"");
        var categories=new com.vncode.app.features.kiz.KizStockCategoryRepository();var other=categories.create(shop,"Other category");
        categories.assignProducts(shop,other.id(),List.of(new com.vncode.app.features.products.ProductKey(1,Marketplace.OZON,"9002","other")));
        var file=directory.resolve("other-stock.txt");java.nio.file.Files.writeString(file,"01"+com.vncode.app.testsupport.CategoryStockFixture.GTIN+"210000000009999\u001d91ABCD\u001d92RklYVFVSRS1OT1QtQS1SRUFMLUNSWVBUTy1TSUdOQVRVUkU=\n");
        new com.vncode.app.features.kiz.KizWarehouseRepository().commitToCategory(shop,com.vncode.app.features.kiz.KizImportReader.read(file),other.id());
        assertEquals("OZON_ACCEPT_KIZ_REJECTED",assertThrows(java.io.IOException.class,
                ()->service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY)).getMessage());
        assertEquals(1,categories.list(shop).stream().filter(c->c.id().equals(other.id())).findFirst().orElseThrow().ready());
        assertEquals(1,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());assertTrue(shipped.isEmpty());
    }
    @Test void aCandidateRejectedInValidationAfterARemoteRejectionKeepsTheOriginalRemoteSlot()throws Exception {
        importedCategoryStock(3);rejectFirstImportedMark=true;rejectSecondCandidateValidation=true;
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY).status());
        assertEquals(2,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        var jobs=new OzonExemplarJobRepository();String finalCode=jobs.bindings(jobs.find(1,"PARENT").id()).getFirst().rawCode();
        assertNotEquals(firstImportedMark,finalCode);assertNotEquals(secondImportedMark,finalCode);
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM kiz_rejected_stock")){assertTrue(r.next());assertEquals(2,r.getInt(1));}
    }
    @Test void aPreviouslyPrintedImportedMarkCanBeReplacedAfterAnExactOzonRejection()throws Exception {
        importedCategoryStock(2);rejectFirstImportedMark=true;
        var posting=OzonJson.requirePostingDetail(JsonParser.parseString(posting("PARENT")).getAsJsonObject(),"PARENT");
        new OzonPostingRepository().upsertDetail(1,posting);var jobs=new OzonExemplarJobRepository();
        var plan=OzonRequirementGuard.plan(posting,Map.of(),Set.of(),Set.of("101"));
        var local=jobs.stageLocalForPrint(1,plan,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY,()->{});
        assertEquals(OzonExemplarJobStage.ACCEPTED,jobs.consumePrinted(local.job()).stage());
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY).status());
        assertEquals(2,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        assertNotEquals(firstImportedMark,jobs.bindings(jobs.find(1,"PARENT").id()).getFirst().rawCode());
    }
    @Test void exhaustedOptionalImportedMarksStillAllowReceiptWhenOzonExplicitlyPermitsIt()throws Exception {
        importedCategoryStock(3);optional=true;optionalNoMarks=true;rejected=true;
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true,com.vncode.app.features.kiz.KizSourceSelection.SELLER_ONLY).status());
        assertEquals(3,trace.stream().filter(p->p.equals("/v6/fbs/posting/product/exemplar/set")).count());
        assertEquals(1,shipped.size());
        var jobs=new OzonExemplarJobRepository();assertEquals("skipped",jobs.latestActionStatus(1,"accept_kiz_optional","PARENT"));
        var bound=jobs.bindings(jobs.find(1,"PARENT").id()).getFirst();
        try(var c=Database.getConnection()){
            assertTrue(OzonExemplarJobRepository.hasRejectedPrintObservation(c,jobs.find(1,"PARENT"),bound.productId(),bound.exemplarId(),bound.rawCode()));
        }
    }
    private void importedCategoryStock(int count)throws Exception {
        new OzonCatalogRepository().upsertPage(1,List.of(new OzonProductDto("9001","fixture","101","Fixture","",false,"",List.of())),"");
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM ozon_product_gtin_mappings");}
        com.vncode.app.testsupport.CategoryStockFixture.add(shop,"9001",directory,count);
    }
    @Test void purchasedAcceptanceStillRequiresProductGtinEvenWhenImportedCategoryHasStock()throws Exception {
        new OzonCatalogRepository().upsertPage(1,List.of(new OzonProductDto("9001","fixture","101","Fixture","",false,"",List.of())),"");
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM ozon_product_gtin_mappings");}
        com.vncode.app.testsupport.CategoryStockFixture.add(shop,"9001",directory,1);
        assertThrows(OzonRequirementGuard.MissingMappingException.class,()->
                service().shipPerUnit(shop,"PARENT",true,new com.vncode.app.features.kiz.KizSourceSelection(false,true)));
        assertTrue(sent.isEmpty());assertTrue(shipped.isEmpty());
        assertFalse(trace.contains("/v6/fbs/posting/product/exemplar/set"));
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
    @Test void optionalMarkWithoutChosenSourceDoesNotBlockOrAllocateKiz()throws Exception {
        optional=true;optionalNoMarks=true;
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true,new com.vncode.app.features.kiz.KizSourceSelection(false,false)).status());
        assertTrue(sent.isEmpty());assertEquals(1,shipped.size());
        assertEquals(new com.vncode.app.features.kiz.KizSourceSelection(false,false),new OzonUnitShipRepository().find(shop,"PARENT").sources());
    }
    @Test void mandatoryMarkWithoutChosenSourceStopsOnlyThatPosting()throws Exception {
        var failure=assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true,new com.vncode.app.features.kiz.KizSourceSelection(false,false)));
        assertEquals("OZON_ACCEPT_KIZ_SOURCE_REQUIRED",failure.getMessage());assertTrue(sent.isEmpty());assertTrue(shipped.isEmpty());
    }
    @Test void optionalRejectedMarkStillAcceptsWhenFreshOzonResponseAllowsNoMark()throws Exception {
        optional=true;optionalNoMarks=true;rejected=true;
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true).status());
        assertEquals(Set.of("PARENT"),shipped);assertTrue(accepted.isEmpty());
        var jobs=new OzonExemplarJobRepository();
        assertEquals("rejected",jobs.latestActionStatus(1,"accept_kiz","PARENT"));
        assertEquals("skipped",jobs.latestActionStatus(1,"accept_kiz_optional","PARENT"));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM kiz_codes WHERE status='CONSUMED'")){
            assertTrue(r.next());assertEquals(1,r.getInt(1),"An exactly rejected code is retired, never returned to stock");
        }
        assertEquals(com.vncode.app.features.kiz.status.Kn3Mark.State.REJECTED,
                new com.vncode.app.features.kiz.status.Kn3Repository().read(shop,"PARENT").getFirst().state(),
                "Receipt success must not become a green KN3 verdict");
        var before=jobs.bindings(jobs.find(1,"PARENT").id());
        var preparation=new OzonExemplarService(new OzonPostingRepository(),new OzonProductGtinMappingRepository(),jobs,
                (id,credentials)->new OzonApiClient(id,credentials,server.url("/"),new OkHttpClient.Builder().build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))));
        assertEquals("VALIDATED",preparation.stageForPrint(shop,"PARENT").stage());
        var after=jobs.bindings(jobs.find(1,"PARENT").id());assertEquals(before.size(),after.size());
        assertEquals(before.getFirst().kizId(),after.getFirst().kizId());
        assertEquals(before.getFirst().rawCode(),after.getFirst().rawCode());
        assertEquals(before.getFirst().exemplarId(),after.getFirst().exemplarId());
        assertEquals("rejected",jobs.latestActionStatus(1,"accept_kiz","PARENT"));
        assertEquals(1,sent.size());
    }
    @Test void optionalValidationRejectionShipsWithoutSubmittingInvalidMarks()throws Exception {
        optional=true;optionalNoMarks=true;validationRejected=true;
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true).status());
        assertEquals(Set.of("PARENT"),shipped);assertTrue(sent.isEmpty());
        assertEquals("rejected",new OzonExemplarJobRepository().latestActionStatus(1,"accept_kiz","PARENT"));
    }
    @Test void optionalMissingMappingDoesNotBlockAnOrderThatOzonAllowsToShip()throws Exception {
        optional=true;optionalNoMarks=true;
        try(var c=Database.getConnection();var s=c.createStatement()){s.executeUpdate("DELETE FROM ozon_product_gtin_mappings");}
        assertEquals("awaiting_deliver",service().shipPerUnit(shop,"PARENT",true).status());
        assertEquals(Set.of("PARENT"),shipped);assertTrue(sent.isEmpty());
    }
    @Test void pendingOptionalMarksCannotBeTreatedAsRejectedOrResent()throws Exception {
        optional=true;optionalNoMarks=true;pending=true;
        assertEquals("OZON_ACCEPT_KIZ_PENDING",assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true)).getMessage());
        assertTrue(shipped.isEmpty());assertNull(new OzonExemplarJobRepository().latestActionStatus(1,"accept_kiz_optional","PARENT"));
    }
    @Test void optionalRejectedMarkStillStopsWhenOzonDoesNotAllowShipping()throws Exception {
        optional=true;rejected=true;
        assertEquals("OZON_ACCEPT_KIZ_REJECTED",assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true)).getMessage());
        assertTrue(shipped.isEmpty());
    }
    @Test void missingRequirementsAreNeverTreatedAsProofOfNoMarking()throws Exception {
        requirementsMissing=true;optionalNoMarks=true;
        var failure=assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true,new com.vncode.app.features.kiz.KizSourceSelection(false,false)));
        assertEquals("OZON_ACCEPT_KIZ_REQUIREMENTS_UNVERIFIED",failure.getMessage());assertTrue(sent.isEmpty());assertTrue(shipped.isEmpty());
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
    @Test void oneRejectedChildDoesNotStopTheOtherChildFromBeingReceived()throws Exception {
        multi=true;firstChildRejected=true;
        assertEquals("OZON_ACCEPT_KIZ_REJECTED",assertThrows(java.io.IOException.class,()->service().shipPerUnit(shop,"PARENT",true)).getMessage());
        assertEquals(Set.of("CHILD-A","CHILD-B"),sent.keySet());assertEquals(Set.of("CHILD-B"),shipped);
        assertNotEquals("COMPLETE",new OzonUnitShipRepository().find(shop,"PARENT").state());
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
    private OzonExemplarService exemplarService(){return new OzonExemplarService(new OzonPostingRepository(),new OzonProductGtinMappingRepository(),new OzonExemplarJobRepository(),(id,credentials)->new OzonApiClient(id,credentials,server.url("/"),new OkHttpClient.Builder().readTimeout(1,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))));}
    private OzonShipService service(){return new OzonShipService(new OzonPostingRepository(),new OzonProductGtinMappingRepository(),new OzonExemplarJobRepository(),(id,credentials)->new OzonApiClient(id,credentials,server.url("/"),new OkHttpClient.Builder().readTimeout(1,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1))));}
    private MockResponse respond(RecordedRequest request) {
        String path=request.getPath();trace.add(path);var body=JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();String number=OzonJson.text(body,"posting_number");
        if(path.equals("/v3/posting/fbs/get"))return json(posting(number));
        if(path.equals("/v1/posting/fbs/split"))return json("{\"parent_posting\":{\"posting_number\":\"PARENT\",\"products\":[]},\"postings\":[{\"posting_number\":\"CHILD-A\"},{\"posting_number\":\"CHILD-B\"}]}");
        if(path.equals("/v6/fbs/posting/product/exemplar/create-or-get"))return json("{\"result\":{\"products\":[{\"product_id\":101,\"exemplars\":[{\"exemplar_id\":7001}]}]}}");
        if(path.equals("/v5/fbs/posting/product/exemplar/validate")){
            if(invalidGtd)return json("{\"products\":[{\"product_id\":101,\"error\":\"GTD_INVALID\",\"exemplars\":[{\"valid\":false,\"marks\":[{\"valid\":true,\"mark_type\":\"mandatory_mark\",\"mark\":\"unrelated\",\"errors\":[]}]}]}]}");
            if(rejectFirstImportedValidation||rejectSecondCandidateValidation&&firstImportedMark!=null){
                var result=body.deepCopy();var product=result.getAsJsonArray("products").get(0).getAsJsonObject();
                var exemplar=product.getAsJsonArray("exemplars").get(0).getAsJsonObject();
                var mark=exemplar.getAsJsonArray("marks").get(0).getAsJsonObject();String value=mark.get("mark").getAsString();
                if(rejectFirstImportedValidation&&firstImportedMark==null)firstImportedMark=value;
                if(rejectSecondCandidateValidation&&!value.equals(firstImportedMark)&&secondImportedMark==null)secondImportedMark=value;
                boolean valid=!value.equals(rejectSecondCandidateValidation?secondImportedMark:firstImportedMark);
                if(valid&&holdReplacementValidation)return json("{\"products\":[]}");
                if(foreignValidationMark)product.addProperty("product_id",999);
                mark.addProperty("valid",valid);exemplar.addProperty("valid",valid);
                var errors=new JsonArray();if(!valid)errors.add("INVALID_MARK");mark.add("errors",errors);
                return json(result.toString());
            }
            return json("{\"products\":[{\"exemplars\":[{\"valid\":"+!validationRejected+"}]}]}");
        }
        if(path.equals("/v6/fbs/posting/product/exemplar/set")){sent.put(number,body);return json("{}");}
        if(path.equals("/v5/fbs/posting/product/exemplar/status")) {
            boolean rejected=this.rejected||firstChildRejected&&number.equals("CHILD-A");
            var raw=sent.get(number).deepCopy();raw.addProperty("status",pending?"validation_in_process":"ship_available");
            if(statusMetadata)for(var p:raw.getAsJsonArray("products"))for(var e:p.getAsJsonObject().getAsJsonArray("exemplars")){
                var exemplar=e.getAsJsonObject();exemplar.addProperty("gtd","GTD-ORIGINAL");exemplar.addProperty("rnpt","RNPT-ORIGINAL");
                if(exemplar.getAsJsonArray("marks").size()==1){var sibling=new JsonObject();sibling.addProperty("mark_type","other_mark");sibling.addProperty("mark","SIBLING-ORIGINAL");exemplar.getAsJsonArray("marks").add(sibling);}
            }
            for(var p:raw.getAsJsonArray("products"))for(var e:p.getAsJsonObject().getAsJsonArray("exemplars"))for(var m:e.getAsJsonObject().getAsJsonArray("marks")){
                var mark=m.getAsJsonObject();
                if(rejectFirstImportedMark){if(firstImportedMark==null)firstImportedMark=mark.get("mark").getAsString();rejected=firstImportedMark.equals(mark.get("mark").getAsString());}
                mark.addProperty("check_status",pending?"processing":rejected?"failed":"passed");var errors=new JsonArray();if(rejected)errors.add("NOT_IN_CIRCULATION");mark.add("error_codes",errors);
            }
            if(!pending&&!rejected)accepted.add(number);return json(raw.toString());
        }
        if(path.equals("/v4/posting/fbs/ship")) {assertTrue(accepted.contains(number)||optionalNoMarks);shipped.add(number);return json("{\"result\":[\""+number+"\"]}");}
        return new MockResponse().setResponseCode(404);
    }
    private String posting(String number) {
        String status=shipped.contains(number)?"awaiting_deliver":"awaiting_packaging";
        int product=number.equals("UNMAPPED")?202:101;
        return "{\"result\":{\"posting_number\":\""+number+"\",\"order_id\":\""+(foreign&&number.equals("CHILD-B")?"FOREIGN":"ORDER")+"\",\"delivery_method\":{\"warehouse_id\":\"WH\"},\"status\":\""+status+"\",\"products\":[{\"sku\":"+product+",\"offer_id\":\"fixture\",\"name\":\"Fixture\",\"quantity\":"+(multi&&number.equals("PARENT")?2:1)+"}],"+(requirementsMissing?"":optional?"\"requirements\":{\"products_requiring_mandatory_mark\":[]},\"optional\":{\"products_with_possible_mandatory_mark\":["+product+"]},":"\"requirements\":{\"products_requiring_mandatory_mark\":[\""+product+"\"]},")+"\"available_actions\":"+(accepted.contains(number)||optionalNoMarks?"[\"ship\"]":"[]")+",\"ship_available\":"+(accepted.contains(number)||optionalNoMarks)+"}}";
    }
    private static MockResponse json(String value){return new MockResponse().setHeader("Content-Type","application/json").setBody(value);}
}
