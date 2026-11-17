package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.*;
import com.vncode.app.integration.znack.ZnackPurchaseBatchStore.Approved;
import com.vncode.app.integration.znack.ZnackPurchaseBatchStore.Batch;
import com.vncode.app.integration.znack.signature.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real protocol services and isolated SQLite; all credentials, signatures and responses are synthetic. */
class Wb1326IntroductionMethodTest {
    private static final String GTIN="04600000000015", OWNER="7700000001", PARTICIPANT="7700000002";
    private static final String RAW="01"+GTIN+"21METHOD0000001\u001d91ABCD\u001d92synthetic-crypto";
    @TempDir Path directory;
    private ZnackRepository repository;
    private ZnackPurchaseBatchStore store;
    private final Settings original=settings("original",OWNER);
    private final Remote remote=new Remote();

    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());
        Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Method fixture','fixture')");
        }
        repository=new ZnackRepository(new ShopContext(1,"Method fixture"));
        repository.saveSettings(original);
        store=new ZnackPurchaseBatchStore(repository);
        assertTrue(ZnackSigningSession.authorizeShopExplicitly(repository),"Synthetic positive fixture must explicitly allow signing");
    }
    @AfterEach void cleanup() {
        ZnackSigningSession.resetForTests();
        System.clearProperty("vncode.appdata.dir");
    }

    @ParameterizedTest(name="{0} / {1}")
    @CsvSource({"LP,PRODUCTION","FOOTWEAR,PRODUCTION","LP,COMMISSION","FOOTWEAR,COMMISSION"})
    void persistedPurchaseContextControlsTheLegalDocumentAfterPreferencesChange(
            ZnackProductGroup group,ZnackPurchaseMethod method)throws Exception {
        Batch batch=paid(group,method);
        new ZnackPurchasePreferences(1).saveMethod(method==ZnackPurchaseMethod.PRODUCTION
                ?ZnackPurchaseMethod.COMMISSION:ZnackPurchaseMethod.PRODUCTION);
        long id=submit(batch,settings("different-caller","7800000003"));
        assertEquals(List.of(group.apiCode()),remote.documentGroups);
        assertEquals(method.documentType(),remote.requests.getFirst().get("type").getAsString());
        JsonObject payload=remote.decoded.getFirst();
        assertEquals(PARTICIPANT,payload.get("participant_inn").getAsString());
        if(method==ZnackPurchaseMethod.COMMISSION) {
            assertEquals(0,remote.registryOwners.size(),"Commission must not query production declarations");
            assertFalse(payload.has("products"));
            assertEquals(ZnackCisNormalizer.forTrueApi(RAW),payload.getAsJsonArray("products_list")
                    .get(0).getAsJsonObject().get("uit").getAsString());
            assertEquals(batch.attemptedAt().atZone(ZoneId.systemDefault()).toLocalDate()+"T00:00:00.000Z",
                    payload.get("product_receiving_date").getAsString());
        } else {
            assertEquals(List.of(OWNER),remote.registryOwners);
            assertEquals(OWNER,payload.get("owner_inn").getAsString());
            assertEquals(ZnackCisNormalizer.forTrueApi(RAW),payload.getAsJsonArray("products")
                    .get(0).getAsJsonObject().get("uit_code").getAsString());
            assertTrue(payload.getAsJsonArray("products").get(0).getAsJsonObject()
                    .getAsJsonArray("certificate_document_data").size()>0);
            if(group==ZnackProductGroup.FOOTWEAR)
                assertEquals(batch.attemptedAt().atZone(ZoneId.of("Europe/Moscow")).toLocalDate().toString(),
                        payload.get("production_date").getAsString());
        }
        assertEquals(id,repository.findLatestDocument(order(batch)).orElseThrow().id());
        assertEquals(OrderStatus.INTRO_SENT,repository.findOrder(order(batch)).orElseThrow().localStatus());
        assertEquals(List.of(RAW),repository.findCodes(order(batch)).stream().map(KizCode::rawCode).toList());
        assertEquals(1,remote.signatures);
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT document_type FROM znack_documents WHERE id=?")) {
            p.setLong(1,id);try(var row=p.executeQuery()){assertTrue(row.next());assertEquals(method.documentType(),row.getString(1));}
        }
    }

    @Test void commissionRetainsOriginalPaidIdentityAndGroupAfterSavedProfileAndProductChange()throws Exception {
        Batch batch=paid(ZnackProductGroup.FOOTWEAR,ZnackPurchaseMethod.COMMISSION);
        repository.upsertProducts(List.of(new Product(GTIN,"Changed metadata","6103100000",null,null,null,null)));
        Settings replacement=settings("replacement","7800000003");
        repository.saveSettings(replacement);
        new ZnackPurchasePreferences(1).saveMethod(ZnackPurchaseMethod.PRODUCTION);
        long pipeline=batch.lines().getFirst().pipelineId();
        ZnackSigningSession.authorizePipeline(1,pipeline,original);
        assertEquals(ZnackProductGroup.LP,ZnackProductGroup.fromProduct(repository.findProduct(GTIN).orElseThrow()));
        try(var ignored=ZnackSigningSession.openPipeline(1,pipeline)) {
            remote.introduction().submit(replacement,repository.findOrder(order(batch)).orElseThrow(),
                    new Product(GTIN,"Metadata without TN VED",null,null,null,null,null),repository.findCodes(order(batch)));
        }
        assertEquals(List.of("shoes"),remote.documentGroups);
        assertEquals("LK_INDI_COMMISSIONING",remote.requests.getFirst().get("type").getAsString());
        assertTrue(remote.registryOwners.isEmpty());
        assertEquals(replacement.signerCertificate(),repository.getSettings().signerCertificate(),
                "Continuing an old paid batch must not rewrite the current shop configuration");
        assertEquals(original,store.contextForOrder(order(batch)).settings());
    }

    @Test void productionCannotBeRetargetedToAnotherGroupByChangedProductMetadata()throws Exception {
        Batch batch=paid(ZnackProductGroup.FOOTWEAR,ZnackPurchaseMethod.PRODUCTION);
        repository.upsertProducts(List.of(new Product(GTIN,"Changed metadata","6103100000",null,null,null,null)));
        var error=assertThrows(ZnackIntroductionService.PermitDocumentsUnavailableException.class,
                ()->submit(batch,original));
        assertFalse(error.confirmedMissing(),"Changed group is a metadata conflict, not proof of missing documents");
        assertEquals(0,remote.signatures);assertEquals(0,remote.authentication);
        assertTrue(remote.registryOwners.isEmpty());assertTrue(remote.requests.isEmpty());
        assertTrue(repository.findLatestDocument(order(batch)).isEmpty());
        assertEquals(KizLegalStatus.RECEIVED,repository.findCodes(order(batch)).getFirst().legalStatus());
    }

    @Test void correctedFootwearDocumentKeepsThePreviouslySignedProductionDate()throws Exception {
        Batch batch=paid(ZnackProductGroup.FOOTWEAR,ZnackPurchaseMethod.PRODUCTION);
        long previous=repository.createDocument(order(batch),"LP_INTRODUCE_GOODS","{\"production_date\":\"2025-12-31\"}");
        repository.updateDocument(previous,null,"REJECTED","HTTP 422: synthetic correctable rejection");
        submit(batch,original);
        assertEquals("2025-12-31",remote.decoded.getFirst().get("production_date").getAsString(),
                "Retrying after a definite rejection must retain the original legal date");
        assertNotEquals(batch.attemptedAt().atZone(ZoneId.of("Europe/Moscow")).toLocalDate().toString(),"2025-12-31");
        assertEquals(1,remote.requests.size());
    }

    @Test void productionLooksUpActiveDocumentsOnlyAfterDownloadAndFallsBackWithoutInventingADeclaration()throws Exception {
        Batch batch=paid(ZnackProductGroup.LP,ZnackPurchaseMethod.PRODUCTION);
        remote.documentsOnlyWithoutOwner=true;
        submit(batch,original);
        assertEquals(List.of(OWNER,PARTICIPANT,""),remote.registryOwners,
                "Owner-filtered empty results must permit participant and unfiltered authoritative lookups");
        assertEquals(1,remote.requests.size());
        assertEquals("SYNTHETIC-DECLARATION",remote.decoded.getFirst().getAsJsonArray("products")
                .get(0).getAsJsonObject().getAsJsonArray("certificate_document_data")
                .get(0).getAsJsonObject().get("certificate_number").getAsString());
    }

    @ParameterizedTest @ValueSource(strings={"foreign-owner","foreign-serial"})
    void checkedFootwearDocumentStillRequiresExactOwnedCisBeforeStockBecomesAvailable(String fault)throws Exception {
        Batch batch=paid(ZnackProductGroup.FOOTWEAR,ZnackPurchaseMethod.COMMISSION);
        submit(batch,original);
        remote.readbackFault=fault;
        var first=remote.introduction().confirm(settings("new-caller","7800000003"),
                repository.findOrder(order(batch)).orElseThrow(),repository.findCodes(order(batch)));
        assertEquals(ZnackIntroductionService.ConfirmStatus.PENDING,first.status());
        assertEquals(OrderStatus.INTRO_SENT,repository.findOrder(order(batch)).orElseThrow().localStatus());
        assertEquals(KizLegalStatus.INTRO_SENT,repository.findCodes(order(batch)).getFirst().legalStatus());
        assertEquals(0,new ZnackGtinInventoryService().availableCount(1,GTIN));
        remote.readbackFault="";
        assertTrue(remote.introduction().confirm(original,repository.findOrder(order(batch)).orElseThrow(),
                repository.findCodes(order(batch))).introduced());
        assertEquals(List.of("shoes","shoes"),remote.confirmationGroups);
        assertEquals(List.of("shoes","shoes"),remote.cisGroups);
        assertEquals(OrderStatus.INTRODUCED,repository.findOrder(order(batch)).orElseThrow().localStatus());
        assertEquals(1,new ZnackGtinInventoryService().availableCount(1,GTIN));
        assertEquals(1,remote.requests.size(),"Polling an existing receipt must not submit another document");
    }

    @Test void footwearReadinessUsesThePaidGroupAndKeepsEmittedCodesPendingUntilApplied()throws Exception {
        Batch batch=paid(ZnackProductGroup.FOOTWEAR,ZnackPurchaseMethod.PRODUCTION);
        var readiness=new ZnackIntroductionReadinessService(remote,remote.auth(),repository);
        remote.cisStatus="EMITTED";
        assertFalse(readiness.check(settings("different-caller","7800000003"),repository.findProduct(GTIN).orElseThrow(),
                repository.findCodes(order(batch))).ready());
        assertEquals(0,remote.cardReads,"Emission alone is not permission to introduce goods");
        remote.cisStatus="APPLIED";
        assertTrue(readiness.check(original,repository.findProduct(GTIN).orElseThrow(),
                repository.findCodes(order(batch))).ready());
        assertEquals(List.of("shoes","shoes"),remote.cisGroups);
        assertEquals(1,remote.cardReads);assertEquals(0,remote.signatures);assertTrue(remote.requests.isEmpty());
        assertEquals(KizLegalStatus.RECEIVED,repository.findCodes(order(batch)).getFirst().legalStatus());
    }

    @ParameterizedTest @ValueSource(strings={"cis","card"})
    void readonlyReadinessRefreshesOnceWithoutLosingThePaidGroup(String expiredRoute)throws Exception {
        Batch batch=paid(ZnackProductGroup.FOOTWEAR,ZnackPurchaseMethod.PRODUCTION);
        remote.cisStatus="APPLIED";remote.expireRoute=expiredRoute;
        var readiness=new ZnackIntroductionReadinessService(remote,remote.auth(),repository);
        assertTrue(readiness.check(original,repository.findProduct(GTIN).orElseThrow(),repository.findCodes(order(batch))).ready());
        assertEquals(1,remote.expirations);assertEquals(1,remote.invalidations);
        assertTrue(remote.cisGroups.stream().allMatch("shoes"::equals));
        assertEquals(0,remote.signatures);assertTrue(remote.requests.isEmpty());
    }

    @ParameterizedTest @ValueSource(strings={"foreign-owner","foreign-serial"})
    void readRefreshNeverTurnsForeignCisIntoOwnedCirculatingStock(String fault)throws Exception {
        Batch batch=paid(ZnackProductGroup.FOOTWEAR,ZnackPurchaseMethod.COMMISSION);
        remote.expireRoute="cis";remote.readbackFault=fault;
        var readiness=new ZnackIntroductionReadinessService(remote,remote.auth(),repository);
        assertFalse(readiness.checkCirculation(original,repository.findProduct(GTIN).orElseThrow(),repository.findCodes(order(batch))).allIntroduced());
        assertEquals(1,remote.expirations);assertEquals(1,remote.invalidations);
        assertEquals(0,new ZnackGtinInventoryService().availableCount(1,GTIN));
        assertEquals(KizLegalStatus.RECEIVED,repository.findCodes(order(batch)).getFirst().legalStatus());
    }

    @Test void registryAndConfirmationRefreshOnlyTheReadThatWasRejected()throws Exception {
        Batch batch=paid(ZnackProductGroup.FOOTWEAR,ZnackPurchaseMethod.PRODUCTION);
        remote.expireRoute="registry";submit(batch,original);
        assertEquals(1,remote.expirations);assertEquals(1,remote.invalidations);assertEquals(1,remote.requests.size());
        remote.expireRoute="document";
        assertTrue(remote.introduction().confirm(original,repository.findOrder(order(batch)).orElseThrow(),repository.findCodes(order(batch))).introduced());
        assertEquals(2,remote.expirations);assertEquals(2,remote.invalidations);
        assertEquals(1,remote.requests.size(),"Refreshing a document read must not submit a second document");
    }

    private Batch paid(ZnackProductGroup group,ZnackPurchaseMethod method) {
        String tnVed=group==ZnackProductGroup.FOOTWEAR?"6403990000":"6103100000";
        repository.upsertProducts(List.of(new Product(GTIN,"Synthetic product",tnVed,null,null,null,null)));
        new ZnackPurchasePreferences(1).saveMethod(method);
        var pipelines=store.enqueueApproved(original,List.of(new Approved(GTIN,1,UUID.randomUUID().toString())));
        Batch batch=store.prepare(pipelines.getFirst()).orElseThrow();
        JsonObject payload=new JsonObject();payload.addProperty("productGroup",group.apiCode());
        JsonObject attributes=new JsonObject();attributes.addProperty("releaseMethodType",method.name());payload.add("attributes",attributes);
        JsonArray products=new JsonArray();JsonObject product=new JsonObject();product.addProperty("gtin",GTIN);product.addProperty("quantity",1);
        product.addProperty("templateId",group.templateId());product.addProperty("serialNumberType","OPERATOR");product.addProperty("cisType","UNIT");
        products.add(product);payload.add("products",products);
        store.savePayload(batch.id(),payload.toString());assertTrue(store.beginSubmission(batch.id()));
        store.acceptRemoteOrder(batch.id(),"synthetic-paid-order");batch=store.find(batch.id()).orElseThrow();
        repository.insertCodes(order(batch),GTIN,new DownloadedCodes(List.of(RAW),"synthetic-block"));
        repository.updateOrder(order(batch),null,"READY",OrderStatus.CODES_DOWNLOADED,null);
        return batch;
    }
    private long submit(Batch batch,Settings caller)throws Exception {
        return remote.introduction().submit(caller,repository.findOrder(order(batch)).orElseThrow(),
                repository.findProduct(GTIN).orElseThrow(),repository.findCodes(order(batch)));
    }
    private static long order(Batch batch){return batch.lines().getFirst().orderId();}
    private static Settings settings(String prefix,String owner) {
        return new Settings("https://"+prefix+".invalid","https://"+prefix+"-suz.invalid",prefix+"-oms",prefix+"-connection",
                PARTICIPANT,PARTICIPANT,owner,"",prefix+"-certificate","[]","","","",true,"","[]","{}",Instant.parse("2026-01-01T00:00:00Z"));
    }

    private final class Remote extends ZnackApiClient {
        final List<String> registryOwners=new ArrayList<>(),documentGroups=new ArrayList<>(),confirmationGroups=new ArrayList<>(),cisGroups=new ArrayList<>();
        final List<JsonObject> requests=new ArrayList<>(),decoded=new ArrayList<>();
        boolean documentsOnlyWithoutOwner;
        String readbackFault="",cisStatus="INTRODUCED",expireRoute="";
        int signatures,authentication,cardReads,expirations,invalidations;
        final ZnackSignatureProvider signer=(bytes,context)->{
            signatures++;assertEquals(ZnackSignatureContext.TRUE_API_DOCUMENT,context);
            return new CryptoProSigningResult(new byte[]{0x30,0x02,0x01,0x00},"");
        };
        ZnackAuthService auth(){return new ZnackAuthService(this,signer) {
            @Override public String trueApiToken(Settings s){authentication++;assertEquals(original,s,"Auth must use the paid profile");return "synthetic-token-"+authentication;}
            @Override public synchronized boolean invalidateTrueApiToken(Settings s,String rejected){assertEquals(original,s);assertTrue(rejected.startsWith("synthetic-token-"));invalidations++;return true;}
            @Override public String resolvedParticipantInn(Settings s){assertEquals(original,s);return PARTICIPANT;}
        };}
        ZnackIntroductionService introduction(){return new ZnackIntroductionService(this,auth(),signer,repository);}
        private void expire(String route)throws java.io.IOException {
            if(route.equals(expireRoute)){expireRoute="";expirations++;throw new ZnackApiException("synthetic expired read",401,"{}");}
        }
        @Override public JsonElement permitDocuments(String base,String token,String gtin,String inn)throws java.io.IOException {
            assertEquals(original.resolvedTrueApiBaseUrl(),base);assertEquals(GTIN,gtin);
            assertFalse(repository.findCodes(repository.findOrders().getFirst().id()).isEmpty(),"Registry lookup belongs after code delivery");
            registryOwners.add(inn);
            expire("registry");
            return JsonParser.parseString(documentsOnlyWithoutOwner&&!inn.isEmpty()
                    ?"{\"result\":{\"documents\":[]}}"
                    :"{\"result\":{\"documents\":[{\"attr_id\":23557,\"number\":\"SYNTHETIC-DECLARATION\",\"from_date\":\"2026-01-10\",\"status_group\":1}]}}");
        }
        @Override public String createDocument(String base,String token,JsonObject request,String group) {
            assertEquals("synthetic-token-"+authentication,token,"Mutation must use the latest token after read-only registry recovery");
            assertEquals(original.resolvedTrueApiBaseUrl(),base);documentGroups.add(group);requests.add(request.deepCopy());
            decoded.add(JsonParser.parseString(new String(Base64.getDecoder().decode(request.get("product_document").getAsString()),StandardCharsets.UTF_8)).getAsJsonObject());
            return "synthetic-document";
        }
        @Override public JsonElement document(String base,String token,String id,String group)throws java.io.IOException {
            assertEquals(original.resolvedTrueApiBaseUrl(),base);assertEquals("synthetic-document",id);confirmationGroups.add(group);
            expire("document");
            return JsonParser.parseString("{\"document_id\":\"synthetic-document\",\"status\":\"CHECKED_OK\"}");
        }
        @Override public JsonElement cisesInfo(String base,String token,JsonElement request,String group)throws java.io.IOException {
            assertEquals(original.resolvedTrueApiBaseUrl(),base);cisGroups.add(group);
            assertEquals(List.of(ZnackCisNormalizer.forTrueApi(RAW)),request.getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList());
            expire("cis");
            String cis=ZnackCisNormalizer.forTrueApi(RAW);
            if(readbackFault.equals("foreign-serial"))cis=cis.substring(0,cis.length()-1)+"9";
            JsonObject info=new JsonObject();info.addProperty("requestedCis",cis);info.addProperty("cis",cis);
            info.addProperty("gtin",GTIN);info.addProperty("status",cisStatus);info.addProperty("statusEx","EMPTY");
            info.addProperty("ownerInn",readbackFault.equals("foreign-owner")?"7800000003":OWNER);info.addProperty("productName","Synthetic product");
            JsonObject row=new JsonObject();row.add("cisInfo",info);JsonArray rows=new JsonArray();rows.add(row);return rows;
        }
        @Override public JsonElement productCards(String base,String token,String gtins)throws java.io.IOException {
            assertEquals(original.resolvedTrueApiBaseUrl(),base);assertEquals(GTIN,gtins);cardReads++;
            expire("card");
            return JsonParser.parseString("{\"result\":[{\"gtin\":\""+GTIN+"\",\"goodMarkFlag\":true,\"goodTurnFlag\":true,\"goodStatus\":\"published\"}]}");
        }
    }
}
