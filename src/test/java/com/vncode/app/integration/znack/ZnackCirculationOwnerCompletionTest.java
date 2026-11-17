package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual shared purchase completion with synthetic CIS responses and isolated SQLite. */
class ZnackCirculationOwnerCompletionTest {
    private static final String GTIN="04601234567890",OWNER="7700000001",FOREIGN="7800000002";
    @TempDir Path directory;
    private ZnackRepository repository;
    private Settings original;
    private long order,pipeline,document;
    private final AtomicInteger reads=new AtomicInteger(),documents=new AtomicInteger();
    private List<String> rawCodes;

    @BeforeEach void setup() {
        System.setProperty("vncode.appdata.dir",directory.toString());
        Database.initDatabase();
    }
    @AfterEach void cleanup() {
        ZnackPurchaseCoordinator.cancelPendingPolls();
        ZnackPipelineQueueExecutor.shutdownAll();
        ZnackSigningSession.resetForTests();
        System.clearProperty("vncode.appdata.dir");
    }
    static Stream<Object[]> ownership() {
        return Stream.of("WILDBERRIES","OZON").flatMap(marketplace ->
                Stream.of("missing","foreign","numeric","mixed","outer-conflict","exact")
                        .map(owner -> new Object[]{marketplace,owner}));
    }

    @ParameterizedTest(name="{0}: automatic completion / {1}") @MethodSource("ownership")
    void automaticPurchaseCannotMakeForeignOrUnverifiedOwnerAvailable(String marketplace,String owner)throws Exception {
        prepare(marketplace,PurchaseStage.WAITING_INTRODUCTION_READINESS);
        coordinator(owner).advance(original.withAutoIntroduction(true),pipeline);
        assertOutcome(owner.equals("exact"),PurchaseStage.WAITING_INTRODUCTION_READINESS,KizLegalStatus.RECEIVED);
        assertEquals(1,reads.get(),"Ownership proof must use the same CIS response, not another API request");
    }

    @ParameterizedTest(name="{0}: document completion / {1}") @MethodSource("ownership")
    void checkedDocumentCannotMakeForeignOrUnverifiedOwnerAvailable(String marketplace,String owner)throws Exception {
        prepare(marketplace,PurchaseStage.POLLING_INTRODUCTION);
        coordinator(owner).advance(original.withAutoIntroduction(true),pipeline);
        assertOutcome(owner.equals("exact"),PurchaseStage.POLLING_INTRODUCTION,KizLegalStatus.INTRO_SENT);
        assertEquals(owner.equals("exact")?"CHECKED_OK":"SUBMITTED",repository.findLatestDocument(order).orElseThrow().status());
        assertEquals(1,reads.get(),"A matching document is insufficient without an owned exact CIS readback");
        if(!owner.equals("exact"))assertEquals("KIZ_CIRCULATION_OWNER_UNVERIFIED",
                repository.findPipeline(pipeline).orElseThrow().errorMessage(),"The purchase report must retain the reason for pending circulation");
    }

    @ParameterizedTest @ValueSource(strings={"WILDBERRIES","OZON"})
    void missingConfiguredOwnerCannotBeInventedFromTheRemoteCode(String marketplace)throws Exception {
        prepare(marketplace,PurchaseStage.WAITING_INTRODUCTION_READINESS,settings("","", "{}"));
        coordinator("exact").advance(original.withAutoIntroduction(true),pipeline);
        assertOutcome(false,PurchaseStage.WAITING_INTRODUCTION_READINESS,KizLegalStatus.RECEIVED);
    }

    @ParameterizedTest @ValueSource(strings={"WILDBERRIES","OZON"})
    void originalParticipantCanSupplyOwnerWhenExplicitOwnerIsEmpty(String marketplace)throws Exception {
        prepare(marketplace,PurchaseStage.WAITING_INTRODUCTION_READINESS,settings("",OWNER,"{}"));
        coordinator("exact").advance(original.withAutoIntroduction(true),pipeline);
        assertOutcome(true,PurchaseStage.WAITING_INTRODUCTION_READINESS,KizLegalStatus.RECEIVED);
        assertEquals(1,reads.get());
    }

    @ParameterizedTest @ValueSource(strings={"WILDBERRIES","OZON"})
    void originalCertificateCanSupplyOwnerWithoutUsingCurrentShopConfiguration(String marketplace)throws Exception {
        prepare(marketplace,PurchaseStage.WAITING_INTRODUCTION_READINESS,settings("","","{\"inn\":\""+OWNER+"\"}"));
        repository.saveSettings(settings(FOREIGN,FOREIGN,"{}"));
        coordinator("exact").advance(original.withAutoIntroduction(true),pipeline);
        assertOutcome(true,PurchaseStage.WAITING_INTRODUCTION_READINESS,KizLegalStatus.RECEIVED);
        assertEquals(1,reads.get());
        assertEquals(FOREIGN,repository.getSettings().ownerInn(),"Readback cannot rewrite the selected shop profile");
    }

    private void prepare(String marketplace,PurchaseStage stage)throws Exception {
        prepare(marketplace,stage,settings(OWNER,OWNER,"{}"));
    }
    private void prepare(String marketplace,PurchaseStage stage,Settings purchasedProfile)throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic',?,'fixture','fixture')")) {
            p.setString(1,marketplace);p.executeUpdate();
        }
        repository=new ZnackRepository(new ShopContext(1,"Synthetic"));original=purchasedProfile;repository.saveSettings(original);
        repository.upsertProducts(List.of(new Product(GTIN,"Synthetic product","6103100000",null,null,null,null)));
        order=repository.createDraft(GTIN,2);repository.updateOrder(order,"synthetic-paid-order","READY",OrderStatus.CODES_DOWNLOADED,null);
        rawCodes=List.of("01"+GTIN+"21OWNER00000001\u001d91ABCD\u001d92fixture-one",
                "01"+GTIN+"21OWNER00000002\u001d91ABCD\u001d92fixture-two");
        repository.insertCodes(order,GTIN,new DownloadedCodes(rawCodes,"synthetic-paid-block"));
        pipeline=repository.enqueueCirculationPipeline(GTIN,2,UUID.randomUUID().toString(),original);repository.updatePipeline(pipeline,order,stage,null);
        if(stage==PurchaseStage.POLLING_INTRODUCTION) {
            document=repository.createDocument(order,"{\"saved\":true}");repository.updateDocument(document,"synthetic-document","SUBMITTED",null);
            repository.markCodes(order,KizLegalStatus.INTRO_SENT,null,document);repository.updateOrder(order,null,null,OrderStatus.INTRO_SENT,null);
        }
    }
    private ZnackPurchaseCoordinator coordinator(String owner) {
        var api=new ZnackApiClient() {
            @Override public JsonElement document(String base,String token,String id) {
                assertEquals("synthetic-document",id);return JsonParser.parseString("{\"document_id\":\"synthetic-document\",\"status\":\"CHECKED_OK\"}");
            }
            @Override public JsonElement cisesInfo(String base,String token,JsonElement request) {
                reads.incrementAndGet();var result=new JsonArray();int index=0;
                for(var code:request.getAsJsonArray()) {
                    var info=new JsonObject();info.addProperty("requestedCis",code.getAsString());info.addProperty("cis",code.getAsString());
                    info.addProperty("gtin",GTIN);info.addProperty("status","INTRODUCED");info.addProperty("productName","Synthetic product");
                    switch(owner) {
                        case "missing" -> { }
                        case "foreign" -> info.addProperty("ownerInn",FOREIGN);
                        case "numeric" -> info.addProperty("ownerInn",7700000001L);
                        case "mixed" -> info.addProperty("ownerInn",index==0?OWNER:FOREIGN);
                        default -> info.addProperty("ownerInn",OWNER);
                    }
                    var row=new JsonObject();row.add("cisInfo",info);
                    if(owner.equals("outer-conflict"))row.addProperty("ownerInn",FOREIGN);
                    result.add(row);index++;
                }
                return result;
            }
            @Override public JsonElement productCards(String base,String token,String gtins) {
                throw new AssertionError("Already introduced codes must not require a fresh Catalog read");
            }
            @Override public String createDocument(String base,String token,JsonObject payload) {
                documents.incrementAndGet();throw new AssertionError("Readback must not submit another legal document");
            }
        };
        var auth=new ZnackAuthService(api,(bytes,context)->{throw new AssertionError("No new signature during readback");}) {
            @Override public String trueApiToken(Settings settings) {return "synthetic-token";}
        };
        var introduction=new ZnackIntroductionService(api,auth,(bytes,context)->{throw new AssertionError("No legal signature during readback");},repository);
        return new ZnackPurchaseCoordinator(repository,null,null,introduction,new ZnackIntroductionReadinessService(api,auth,repository)) {
            @Override void schedule(long ignored) { }
        };
    }
    private void assertOutcome(boolean accepted,PurchaseStage waiting,KizLegalStatus unconfirmed) {
        assertEquals(accepted?PurchaseStage.INTRODUCED:waiting,repository.findPipeline(pipeline).orElseThrow().stage());
        assertEquals(accepted?2:0,new ZnackGtinInventoryService().availableCount(1,GTIN));
        assertTrue(repository.findCodes(order).stream().allMatch(code->code.legalStatus()==(accepted?KizLegalStatus.IN_CIRCULATION:unconfirmed)));
        assertEquals(rawCodes,repository.findCodes(order).stream().map(KizCode::rawCode).toList(),"Full downloaded payload must remain unchanged");
        assertEquals(0,documents.get());assertEquals(1,repository.findOrders().size(),"An owned-status readback cannot authorize another paid order");
    }
    private Settings settings(String owner,String participant,String metadata) {
        return new Settings("http://127.0.0.1:9","http://127.0.0.1:9","synthetic-oms","synthetic-connection",participant,"",owner,
                "","synthetic-certificate","[]","","","",false,"","[]",metadata,Instant.now());
    }
}
