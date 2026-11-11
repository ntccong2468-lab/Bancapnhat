package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic persisted purchases and True API readbacks only; never buys or signs remotely. */
class ZnackCirculationPolicyTest {
    static final String GTIN="04601234567890",OTHER_GTIN="04601234567891",OWNER="7700000001";
    @TempDir Path directory;
    ZnackRepository repository;
    Settings original;
    String cryptcp;
    final AtomicInteger readbacks=new AtomicInteger(),submissions=new AtomicInteger();
    final AtomicInteger cardReads=new AtomicInteger();
    boolean forbidCardRead;
    final List<Settings> observedProfiles=new ArrayList<>();
    final CountDownLatch submitted=new CountDownLatch(1);

    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Synthetic one','fixture-one'),(2,'Synthetic two','fixture-two')");
        }
        repository=new ZnackRepository(new ShopContext(1,"Synthetic one"));
        String comspec=System.getenv("COMSPEC");
        if(comspec!=null&&Files.isRegularFile(Path.of(comspec)))cryptcp=comspec;
        else{var executable=directory.resolve("fixture-cryptcp");Files.writeString(executable,"#!/bin/sh\nexit 0\n");assertTrue(executable.toFile().setExecutable(true));cryptcp=executable.toString();}
        original=settings("original-oms",OWNER,false);repository.saveSettings(original);
        repository.upsertProducts(List.of(new Product(GTIN,"Synthetic product","6103100000","CONFORMITY_DECLARATION","FIXTURE-PERMIT","01.01.2026",null)));
    }
    @AfterEach void cleanup(){
        ZnackPipelineQueueExecutor.shutdownAll();ZnackPurchaseCoordinator.cancelPendingPolls();
        ZnackSigningSession.resetForTests();System.clearProperty("vncode.appdata.dir");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void everyNewPurchasePinsCirculationWithoutChangingTheGlobalPreference(boolean explicitEntry)throws Exception{
        var entered=new CountDownLatch(1);var released=new CountDownLatch(1);
        ZnackPipelineQueueExecutor.submit(1,GTIN,()->{entered.countDown();try{released.await();}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        try{
            var coordinator=new ZnackPurchaseCoordinator(repository,null,null,null){
                @Override public void validatePrerequisites(Settings s,String g,int n){assertTrue(s.autoIntroduction(),"Preflight must validate the same mandatory-circulation intent that is persisted");}
            };
            long id=explicitEntry?coordinator.enqueueForCirculation(original,GTIN,1,UUID.randomUUID().toString())
                    :coordinator.enqueue(original,GTIN,1,UUID.randomUUID().toString());
            assertTrue(repository.pipelineSettings(id).autoIntroduction());
            assertEquals(original,repository.getSettings(),"One purchase cannot rewrite global or unrelated connection preferences");
            assertEquals(original.withAutoIntroduction(true),repository.pipelineSettings(id));
        }finally{ZnackPipelineQueueExecutor.shutdownAll();released.countDown();}
    }

    @Test void downloadWithoutTheOldOptionStillReadsAndConfirmsActualCirculation()throws Exception{
        long[] ids=purchase(PurchaseStage.DOWNLOADING_CODES,1,0);
        coordinator("INTRODUCED",OWNER).advance(original,ids[0]);
        assertEquals(1,readbacks.get());assertIntroduced(ids);
        assertEquals(0,submissions.get(),"Already circulating codes must not create another legal document");
    }

    @ParameterizedTest @ValueSource(strings={"APPLIED","EMITTED"})
    void legacyDownloadContinuesReadOnlyChecksWithoutClaimingCirculationOrSubmitting(String status)throws Exception{
        long[] ids=purchase(PurchaseStage.DOWNLOADING_CODES,1,0);
        coordinator(status,OWNER).advance(original,ids[0]);
        assertEquals(1,readbacks.get());assertEquals(PurchaseStage.WAITING_INTRODUCTION_READINESS,repository.findPipeline(ids[0]).orElseThrow().stage());
        assertEquals(0,new ZnackGtinInventoryService().availableCount(1,GTIN));
        assertEquals(KizLegalStatus.RECEIVED,repository.findCodes(ids[1]).getFirst().legalStatus());assertEquals(0,submissions.get());
    }

    @Test void persistedCompletedPurchaseResumesWithItsOriginalProfileWithoutRebuyOrNewDocument()throws Exception{
        long[] ids=purchase(PurchaseStage.COMPLETED,1,1);
        Settings different=settings("new-oms","7800000002",true);repository.saveSettings(different);
        coordinator("INTRODUCED",OWNER).resume(different);
        assertIntroduced(ids);assertEquals(List.of(original),observedProfiles);
        assertEquals(original,repository.pipelineSettings(ids[0]));assertEquals(different,repository.getSettings());
        assertEquals(1,repository.findOrders().size());assertEquals(0,submissions.get());
    }

    @ParameterizedTest @ValueSource(strings={"","7800000002"})
    void completedLegacyCodesWithMissingOrForeignLiveOwnerNeverBecomeAvailable(String owner)throws Exception{
        long[] ids=purchase(PurchaseStage.COMPLETED,1,1);
        coordinator("INTRODUCED",owner).resume(original);
        assertEquals(PurchaseStage.WAITING_INTRODUCTION_READINESS,repository.findPipeline(ids[0]).orElseThrow().stage());
        assertEquals(1,readbacks.get());assertEquals(0,new ZnackGtinInventoryService().availableCount(1,GTIN));
        assertEquals(KizLegalStatus.RECEIVED,repository.findCodes(ids[1]).getFirst().legalStatus());assertEquals(0,submissions.get());
    }

    @Test void missingOriginalProfileIsDiagnosedWithoutInventingTheCurrentProfileOrPolling()throws Exception{
        long[] ids=purchase(PurchaseStage.COMPLETED,1,1);
        try(var c=Database.getConnection();var p=c.prepareStatement("DELETE FROM znack_pipeline_connections WHERE pipeline_id=?")){p.setLong(1,ids[0]);p.executeUpdate();}
        coordinator("INTRODUCED",OWNER).resume(original);
        var pipeline=repository.findPipeline(ids[0]).orElseThrow();assertEquals(PurchaseStage.COMPLETED,pipeline.stage());
        assertEquals("KIZ_CIRCULATION_ORIGINAL_PROFILE_MISSING",pipeline.errorMessage());assertEquals(0,readbacks.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT COUNT(*) FROM znack_pipeline_connections WHERE pipeline_id=?")){p.setLong(1,ids[0]);try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}}
    }

    @Test void incompleteLegacySetIsDiagnosedWithoutNetworkOrFakeAvailability()throws Exception{
        long[] ids=purchase(PurchaseStage.COMPLETED,2,1);
        coordinator("INTRODUCED",OWNER).resume(original);
        var pipeline=repository.findPipeline(ids[0]).orElseThrow();assertEquals(PurchaseStage.COMPLETED,pipeline.stage());
        assertEquals("KIZ_CIRCULATION_CODES_INCOMPLETE",pipeline.errorMessage());assertEquals(0,readbacks.get());
        assertEquals(0,new ZnackGtinInventoryService().availableCount(1,GTIN));assertEquals(1,repository.findOrders().size());
    }

    @Test void missingProfileNeverGetsInventedByLaterSettingsSaveOrAnotherPipelineFallback()throws Exception{
        long[] ids=purchase(PurchaseStage.COMPLETED,1,1);
        try(var c=Database.getConnection();var p=c.prepareStatement("DELETE FROM znack_pipeline_connections WHERE pipeline_id=?")){p.setLong(1,ids[0]);p.executeUpdate();}
        coordinator("INTRODUCED",OWNER).resume(original);
        var different=settings("new-oms","7800000002",false);repository.saveSettings(different);
        long active=repository.enqueuePipeline(GTIN,1,UUID.randomUUID().toString());
        try(var c=Database.getConnection();var p=c.prepareStatement("DELETE FROM znack_pipeline_connections WHERE pipeline_id=?")){p.setLong(1,active);p.executeUpdate();}
        assertEquals(different,repository.pipelineSettings(active),"Active legacy fallback remains supported");
        // Retire the unrelated unsent fixture so resume cannot invoke purchase services.
        repository.updatePipeline(active,null,PurchaseStage.FAILED,"synthetic-unsent");
        coordinator("INTRODUCED",OWNER).resume(different);
        assertEquals(PurchaseStage.COMPLETED,repository.findPipeline(ids[0]).orElseThrow().stage());assertEquals(0,readbacks.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT COUNT(*) FROM znack_pipeline_connections WHERE pipeline_id=?")){p.setLong(1,ids[0]);try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}}
    }

    @Test void absentLegacyConnectionTableIsDiagnosedWithoutBackfillingCurrentCredentials()throws Exception{
        long[] ids=purchase(PurchaseStage.COMPLETED,1,1);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DROP TABLE znack_pipeline_connections");}
        coordinator("INTRODUCED",OWNER).resume(original);
        assertEquals("KIZ_CIRCULATION_ORIGINAL_PROFILE_MISSING",repository.findPipeline(ids[0]).orElseThrow().errorMessage());assertEquals(0,readbacks.get());
    }

    @ParameterizedTest @ValueSource(strings={"deleted","archived","pending_registration","imported_unverified"})
    void retiredOrUnverifiedProductsCannotBeReactivatedByLegacyPurchaseObservation(String kind)throws Exception{
        long[] ids=purchase(PurchaseStage.COMPLETED,1,1);
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_products SET deleted_at=?,identity_archived_at=?,card_status=? WHERE shop_id=1 AND gtin=?")){
            p.setString(1,"deleted".equals(kind)?Instant.now().toString():null);p.setString(2,"archived".equals(kind)?Instant.now().toString():null);
            p.setString(3,kind);p.setString(4,GTIN);p.executeUpdate();
        }
        coordinator("INTRODUCED",OWNER).resume(original);
        assertEquals(PurchaseStage.COMPLETED,repository.findPipeline(ids[0]).orElseThrow().stage());assertEquals(0,readbacks.get());
        assertEquals(KizLegalStatus.RECEIVED,repository.findCodes(ids[1]).getFirst().legalStatus());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void mandatoryPerPurchaseIntentResumesAfterDocumentRepairEvenWhenTheGlobalPreferenceIsFalse(boolean async)throws Exception{
        long order=repository.createDraft(GTIN,1);repository.updateOrder(order,"synthetic-order","READY",OrderStatus.CODES_DOWNLOADED,null);insert(order,1);
        long pipeline=repository.enqueuePrintPipeline(GTIN,1,UUID.randomUUID().toString(),original);
        repository.updatePipeline(pipeline,order,PurchaseStage.WAITING_INTRODUCTION_DOCUMENTS,"No active declaration or certificate");
        repository.updateProductDocuments(GTIN,List.of(new GoodsDocument("CONFORMITY_DECLARATION","FIXTURE-PERMIT","01.01.2026")));
        var coordinator=coordinator("APPLIED",OWNER,true);
        if(async){
            coordinator.resumeEligibleIntroductionsAsync(original);
            var finished=new CountDownLatch(1);ZnackPipelineQueueExecutor.submit(1,GTIN,finished::countDown);
            assertTrue(finished.await(5,TimeUnit.SECONDS));
        }
        else coordinator.resumeEligibleIntroductions(original);
        assertEquals(1,submissions.get());assertEquals(PurchaseStage.POLLING_INTRODUCTION,repository.findPipeline(pipeline).orElseThrow().stage());
        assertEquals(original,repository.getSettings());assertTrue(repository.pipelineSettings(pipeline).autoIntroduction());
        assertEquals(0,new ZnackGtinInventoryService().availableCount(1,GTIN));
    }

    @Test void differingOnlyInPerJobIntentReusesTheSameConnectionServices()throws Exception{
        long order=repository.createDraft(GTIN,1);repository.updateOrder(order,"synthetic-order","READY",OrderStatus.CODES_DOWNLOADED,null);insert(order,1);
        long pipeline=repository.enqueuePrintPipeline(GTIN,1,UUID.randomUUID().toString(),original);
        repository.updatePipeline(pipeline,order,PurchaseStage.WAITING_INTRODUCTION_READINESS,null);
        var coordinator=coordinator("INTRODUCED",OWNER);
        var configured=ZnackPurchaseCoordinator.class.getDeclaredField("serviceSettings");configured.setAccessible(true);configured.set(coordinator,original);
        coordinator.advance(original,pipeline);
        assertEquals(1,readbacks.get(),"Changing only the circulation intent must not discard the configured services/auth session");
        assertEquals(List.of(original.withAutoIntroduction(true)),observedProfiles);assertIntroduced(new long[]{pipeline,order});
    }

    @Test void ambiguousSubmittedIntroductionIsObservedUntilCirculatingWithoutResubmitting()throws Exception{
        long order=repository.createDraft(GTIN,1);repository.updateOrder(order,"synthetic-order","READY",OrderStatus.CODES_DOWNLOADED,null);insert(order,1);
        long pipeline=repository.enqueuePrintPipeline(GTIN,1,UUID.randomUUID().toString(),original);
        long document=repository.createDocument(order,"{\"synthetic\":true}");repository.updateDocument(document,null,"FAILED","HTTP 503: outcome unknown");
        repository.updatePipeline(pipeline,order,PurchaseStage.INTRODUCTION_FAILED,"HTTP 503: outcome unknown");
        var receipt=repository.findLatestDocument(order).orElseThrow();
        coordinator("APPLIED",OWNER).resume(original);
        assertEquals(PurchaseStage.WAITING_INTRODUCTION_READINESS,repository.findPipeline(pipeline).orElseThrow().stage());
        assertEquals(receipt,repository.findLatestDocument(order).orElseThrow());assertEquals(0,submissions.get());
        coordinator("INTRODUCED",OWNER).advance(repository.pipelineSettings(pipeline),pipeline);
        assertIntroduced(new long[]{pipeline,order});assertEquals(receipt,repository.findLatestDocument(order).orElseThrow());
        assertEquals(1,repository.findOrders().size());assertEquals(0,submissions.get());
    }

    @Test void unsafeHistoricRejectedResponseIsQuarantinedAndOnlyObserved()throws Exception{
        long order=repository.createDraft(GTIN,1);repository.updateOrder(order,"synthetic-order","READY",OrderStatus.CODES_DOWNLOADED,null);insert(order,1);
        long pipeline=repository.enqueuePrintPipeline(GTIN,1,UUID.randomUUID().toString(),original);
        long document=repository.createDocument(order,"{\"synthetic\":true}");String diagnostic="HTTP 503; remote body mentions HTTP 422";
        repository.updateDocument(document,null,"REJECTED",diagnostic);repository.updatePipeline(pipeline,order,PurchaseStage.FAILED,diagnostic);
        coordinator("APPLIED",OWNER).resume(original);
        var receipt=repository.findLatestDocument(order).orElseThrow();assertEquals("FAILED",receipt.status());assertEquals(diagnostic,receipt.errorMessage());
        assertEquals("{\"synthetic\":true}",receipt.payloadJson());assertNull(receipt.externalDocumentId());
        assertEquals(PurchaseStage.WAITING_INTRODUCTION_READINESS,repository.findPipeline(pipeline).orElseThrow().stage());assertEquals(0,submissions.get());
        assertFalse(repository.latestDocumentIsLegacyHttpRejection(order),"A 5xx body mentioning422 is not definitive rejection");
    }

    @Test void suzDeliveryEvidenceSurvivesPendingAndConfirmedCirculation()throws Exception{
        long[] ids=purchase(PurchaseStage.WAITING_INTRODUCTION_READINESS,1,1);
        repository.updateOrder(ids[1],null,"EXHAUSTED",OrderStatus.CODES_DOWNLOADED,null);
        coordinator("APPLIED",OWNER).advance(original,ids[0]);
        assertEquals("EXHAUSTED",repository.findOrder(ids[1]).orElseThrow().remoteStatus());
        assertEquals(PurchaseStage.WAITING_INTRODUCTION_READINESS,repository.findPipeline(ids[0]).orElseThrow().stage());
        coordinator("INTRODUCED",OWNER).advance(original,ids[0]);
        assertEquals("EXHAUSTED",repository.findOrder(ids[1]).orElseThrow().remoteStatus());assertIntroduced(ids);
    }

    @Test void alreadyIntroducedPurchaseConfirmsWithoutTnVedOrCatalogReadback()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE znack_products SET tn_ved=NULL WHERE shop_id=1");}
        forbidCardRead=true;long[] ids=purchase(PurchaseStage.DOWNLOADING_CODES,1,0,true);
        assertDoesNotThrow(()->coordinator("INTRODUCED",OWNER).advance(original.withAutoIntroduction(true),ids[0]));
        assertIntroduced(ids);assertEquals(1,readbacks.get());assertEquals(0,cardReads.get());assertEquals(0,submissions.get());
    }

    @Test void alreadyIntroducedPurchaseConfirmsWhenTheCatalogCardEndpointIsUnavailable()throws Exception{
        forbidCardRead=true;long[] ids=purchase(PurchaseStage.DOWNLOADING_CODES,1,0,true);
        assertDoesNotThrow(()->coordinator("INTRODUCED",OWNER).advance(original.withAutoIntroduction(true),ids[0]));
        assertIntroduced(ids);assertEquals(1,readbacks.get());assertEquals(0,cardReads.get());assertEquals(0,submissions.get());
    }

    @Test void appliedCodesWithMissingTnVedRemainPendingThenMirrorLaterCirculation()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE znack_products SET tn_ved=NULL WHERE shop_id=1");}
        long[] ids=purchase(PurchaseStage.DOWNLOADING_CODES,1,0,true);
        coordinator("APPLIED",OWNER).advance(original.withAutoIntroduction(true),ids[0]);
        assertEquals(1,readbacks.get(),"Authoritative code status must be read before metadata can block a new introduction");
        assertEquals(PurchaseStage.WAITING_INTRODUCTION_READINESS,repository.findPipeline(ids[0]).orElseThrow().stage());
        assertEquals("Missing TN VED.",repository.findPipeline(ids[0]).orElseThrow().errorMessage());
        assertEquals(0,submissions.get());assertEquals(0,new ZnackGtinInventoryService().availableCount(1,GTIN));
        assertEquals(KizLegalStatus.RECEIVED,repository.findCodes(ids[1]).getFirst().legalStatus());
        forbidCardRead=true;
        coordinator("INTRODUCED",OWNER).advance(original.withAutoIntroduction(true),ids[0]);
        assertIntroduced(ids);assertEquals(2,readbacks.get());assertEquals(1,cardReads.get());assertEquals(0,submissions.get());
        assertTrue(repository.findLatestDocument(ids[1]).isEmpty());
    }

    @Test void quantityMismatchCannotBePromotedEvenIfTheInjectedReadinessClaimsSuccess()throws Exception{
        long[] ids=purchase(PurchaseStage.WAITING_INTRODUCTION_READINESS,2,1);
        // The pipeline promises one unit, but its persisted order promises two.
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_purchase_pipelines SET quantity=1 WHERE id=?")){p.setLong(1,ids[0]);p.executeUpdate();}
        var claimed=new AtomicInteger();var readiness=new ZnackIntroductionReadinessService(null,null,repository){
            @Override public Readiness check(Settings s,Product p,List<KizCode> codes){claimed.incrementAndGet();return Readiness.introduced();}
        };
        new ZnackPurchaseCoordinator(repository,null,null,null,readiness).advance(original.withAutoIntroduction(true),ids[0]);
        assertEquals(0,claimed.get(),"Local quantity/identity must be checked before trusting a remote result");
        assertEquals(PurchaseStage.WAITING_INTRODUCTION_READINESS,repository.findPipeline(ids[0]).orElseThrow().stage());
        assertEquals(0,new ZnackGtinInventoryService().availableCount(1,GTIN));
    }

    @Test void malformedLocalCodeIdentityNeverStartsAReadOnlyPoll()throws Exception{
        long[] ids=purchase(PurchaseStage.COMPLETED,1,1);
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE kiz_codes SET gtin=? WHERE order_id=?")){p.setString(1,OTHER_GTIN);p.setLong(2,ids[1]);p.executeUpdate();}
        coordinator("INTRODUCED",OWNER).resume(original);
        var pipeline=repository.findPipeline(ids[0]).orElseThrow();assertEquals(PurchaseStage.COMPLETED,pipeline.stage());
        assertEquals("KIZ_CIRCULATION_IDENTITY_UNVERIFIED",pipeline.errorMessage());assertEquals(0,readbacks.get());
    }

    private long[] purchase(PurchaseStage stage,int quantity,int count){
        return purchase(stage,quantity,count,false);
    }
    private long[] purchase(PurchaseStage stage,int quantity,int count,boolean circulation){
        long order=repository.createDraft(GTIN,quantity);repository.updateOrder(order,"synthetic-order-"+order,"READY",OrderStatus.CODES_DOWNLOADED,null);
        long pipeline=circulation?repository.enqueueCirculationPipeline(GTIN,quantity,UUID.randomUUID().toString(),original)
                :repository.enqueuePipeline(GTIN,quantity,UUID.randomUUID().toString());repository.updatePipeline(pipeline,order,stage,null);
        if(count>0)insert(order,count);return new long[]{pipeline,order};
    }
    private int insert(long order,int quantity){
        var codes=new ArrayList<String>();for(int i=0;i<quantity;i++)codes.add("01"+GTIN+"21"+String.format(Locale.ROOT,"%013d",order*10+i)+"\u001d91ABCD\u001d92fixture");
        return repository.insertCodes(order,GTIN,new DownloadedCodes(codes,"synthetic-block"));
    }
    private ZnackPurchaseCoordinator coordinator(String status,String owner){
        return coordinator(status,owner,false);
    }
    private ZnackPurchaseCoordinator coordinator(String status,String owner,boolean permitSubmission){
        var api=new ZnackApiClient(){
            @Override public JsonElement productCards(String base,String token,String gtin)throws java.io.IOException{
                cardReads.incrementAndGet();if(forbidCardRead)throw new java.io.IOException("Synthetic catalog endpoint unavailable");
                return JsonParser.parseString("{\"result\":[{\"gtin\":\""+GTIN+"\",\"goodMarkFlag\":true,\"goodTurnFlag\":true}]}");
            }
            @Override public JsonElement cisesInfo(String base,String token,JsonElement body){
                readbacks.incrementAndGet();var result=new JsonArray();
                for(var request:body.getAsJsonArray()){
                    var info=new JsonObject();info.addProperty("requestedCis",request.getAsString());info.addProperty("cis",request.getAsString());info.addProperty("gtin",GTIN);
                    info.addProperty("status",status);info.addProperty("statusEx","EMPTY");info.addProperty("productName","Synthetic product");
                    if(!owner.isBlank())info.addProperty("ownerInn",owner);var row=new JsonObject();row.add("cisInfo",info);result.add(row);
                }return result;
            }
        };
        var auth=new ZnackAuthService(api,(a,b)->null){@Override public String trueApiToken(Settings s){observedProfiles.add(s);return "synthetic-token";}};
        var introduction=new ZnackIntroductionService(null,null,null,repository){
            @Override public long submit(Settings s,KizOrder o,Product p,List<KizCode> codes){submissions.incrementAndGet();if(!permitSubmission)throw new AssertionError("Legacy read-only monitoring cannot submit a document");submitted.countDown();return 1;}
        };
        var codes=new ZnackKizCodeService(null,null,repository){@Override public int download(Settings s,long id){return insert(id,repository.findOrder(id).orElseThrow().quantity());}};
        return new ZnackPurchaseCoordinator(repository,null,codes,introduction,new ZnackIntroductionReadinessService(api,auth,repository)){
            @Override void schedule(long ignored){}
        };
    }
    private void assertIntroduced(long[] ids){
        assertEquals(PurchaseStage.INTRODUCED,repository.findPipeline(ids[0]).orElseThrow().stage());
        assertEquals(OrderStatus.INTRODUCED,repository.findOrder(ids[1]).orElseThrow().localStatus());
        assertEquals(KizLegalStatus.IN_CIRCULATION,repository.findCodes(ids[1]).getFirst().legalStatus());
        assertEquals(1,new ZnackGtinInventoryService().availableCount(1,GTIN));
    }
    private Settings settings(String oms,String owner,boolean auto){
        return new Settings("http://127.0.0.1:9","http://127.0.0.1:9",oms,"synthetic-connection",owner,"",owner,"","synthetic-certificate","[]","FIXTURE-PERMIT","01.01.2026","",auto,"","[]","{}",Instant.parse("2026-01-01T00:00:00Z"),"",cryptcp,"",60,"31.12.2030","CONFORMITY_DECLARATION");
    }
}
