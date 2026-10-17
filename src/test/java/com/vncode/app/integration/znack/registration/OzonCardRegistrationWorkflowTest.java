package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinSyncRepository;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.license.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonProductGtinMappingRepository;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

class OzonCardRegistrationWorkflowTest {
    @TempDir Path root;Shop shop;OzonCardRegistrationRepository repository;FixtureApi api;
    ProductSnapshot snapshot;OzonCardRegistrationWorkflow workflow;AtomicInteger sessions;boolean onMarketplace;
    final ProductKey key=new ProductKey(2,Marketplace.OZON,"501","fixture-offer");
    Draft draft=new Draft("6204",1,"Fixture","Brand",Map.of(1L,"Fixture",ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID,"D:::2025-01-01"));
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        repository=new OzonCardRegistrationRepository();api=new FixtureApi();sessions=new AtomicInteger();snapshot=snapshot("live:review-1",true);
        workflow=workflow(Runnable::run);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    OzonCardRegistrationWorkflow workflow(java.util.concurrent.Executor executor){return new OzonCardRegistrationWorkflow(repository,s->{sessions.incrementAndGet();return session();},(s,k)->snapshot,executor,(s,k,sku,gtin)->onMarketplace);}
    ZnackCardRegistrationWorkflow.CatalogSession session(){
        var settings=new ZnackRepository(new ZnackModels.ShopContext(2,"Fixture")).getSettings();
        var auth=new ZnackAuthService(api,null){@Override public String trueApiToken(ZnackModels.Settings ignored){return "fixture-token";}};
        var catalog=new ZnackNationalCatalogService(api,auth,(bytes,context)->new CryptoProSigningResult(new byte[]{1},""),settings);
        return new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token",catalog,ZnackRegistrationScope.fromSettings(settings));
    }
    ProductSnapshot snapshot(String revision,boolean knownTnved){
        var attributes=new ArrayList<ProductAttribute>();attributes.add(new ProductAttribute("fixture","Fixture",List.of("Fixture"),null));if(knownTnved)attributes.add(new ProductAttribute("tnved","ТН ВЭД",List.of("6204"),null));
        var document=new ProductDocument("D-ID","DECLARATION","D",LocalDate.parse("2025-01-01"),LocalDate.now().plusYears(1),"Fixture","",null,Marketplace.OZON,DocumentState.VALID,"https://api-seller.ozon.ru/v1/product/certificate/list",Instant.now());
        return new ProductSnapshot(key,revision,"Fixture","","Brand","fixture-offer","1:2",List.of(),attributes,List.of(new ProductVariant("fixture-offer","fixture-offer","","","",false)),null,List.of(document),Instant.now(),Map.of("sku","9001","owner",com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop)));
    }
    OzonCardRegistrationRepository.Row row(){return repository.find(key);}
    void ready(){assertTrue(workflow.register(shop,key,draft,true,false,null));assertEquals(Status.READY_TO_SIGN,row().status());}
    void published(){ready();assertTrue(workflow.signApproved(shop,List.of(key),true,null));assertEquals(Status.PUBLISHED,row().status());}
    @Test void registeredSignedCardFlowsThroughCatalogAndRealOzonBarcodeAdapterToNativeActivation()throws Exception {
        published();var completed=PublishedCardGtinFixture.synchronize(shop,row().gtin(),key.cardId(),key.variantId(),"9001");
        var finisher=new OzonCardRegistrationWorkflow(repository,s->session(),(s,k)->snapshot,Runnable::run,
                (s,k,sku,gtin)->((com.vncode.app.integration.ozon.OzonGtinAdapter)completed.adapter()).hasBarcode(completed.key(),sku,gtin));
        assertTrue(finisher.complete(shop,key,true,null));assertEquals(row().gtin(),row().activeGtin());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(1,api.signatures.get());
    }
    @Test void registeringTheSamePendingCardResumesItsFeedWithoutAllocatingOrSubmittingAgain() {
        ready();String gtin=row().gtin(),feed=row().feedId();assertTrue(workflow.register(shop,key,draft,true,false,null));assertEquals(gtin,row().gtin());assertEquals(feed,row().feedId());assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());
    }
    @Test void changedTnvedGroupCreatesOneNewGtinAndArchivesTheOldPendingCard()throws Exception {
        ready();String old=row().gtin();var changed=new Draft("6104",1,"Fixture","Brand",draft.attributes());
        assertTrue(workflow.register(shop,key,changed,true,false,null));assertEquals("00000000000024",row().gtin());assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(2,api.allocations.get());assertEquals(2,api.submissions.get());assertEquals("",row().activeGtin());assertTrue(repository.claimedGtins().contains(old));
        try(var c=Database.getConnection();var st=c.createStatement();var r=st.executeQuery("SELECT gtin,feed_id,payload_json FROM ozon_card_registration_history")){assertTrue(r.next());assertEquals(old,r.getString(1));assertEquals("feed-1",r.getString(2));assertTrue(r.getString(3).contains("6204"));}
    }
    @Test void rejectionReasonSurvivesMonitoringAndSecretsAreNotCopiedIntoTheReport()throws Exception {
        ready();api.rejected=true;new ZnackRegistrationMonitor(null,workflow).poll(shop,3);assertEquals(Status.ERROR,row().status());assertTrue(row().error().contains("required attribute missing"));assertFalse(row().error().contains("fixture-secret"));assertEquals(1,api.submissions.get());
    }
    @Test void automaticSigningConsentSurvivesReopeningAndDoesNotRepeatTheSignature()throws Exception {
        api.pendingModeration=true;assertTrue(workflow.register(shop,key,draft.withAutoSign(true),true,false,null));assertEquals(Status.PROCESSING,row().status());assertEquals(0,api.signatures.get());
        api.pendingModeration=false;var reopened=workflow(Runnable::run);var monitor=new ZnackRegistrationMonitor(null,reopened);assertEquals(1,monitor.poll(shop,3).published());assertEquals(Status.PUBLISHED,row().status());assertEquals(1,api.signatures.get());monitor.poll(shop,3);assertEquals(1,api.signatures.get());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void automaticSigningTimeoutNeverReplaysTheSignatureInTheBackground()throws Exception {
        api.pendingModeration=true;workflow.register(shop,key,draft.withAutoSign(true),true,false,null);api.pendingModeration=false;api.signTimeout=true;var monitor=new ZnackRegistrationMonitor(null,workflow(Runnable::run));monitor.poll(shop,3);assertEquals(Status.SIGNING,row().status());assertEquals(1,api.signatures.get());monitor.poll(shop,3);assertEquals(1,api.signatures.get());api.accepted=true;assertEquals(1,monitor.poll(shop,3).published());assertEquals(1,api.signatures.get());
    }
    @Test void backgroundMonitorReadsTheNativeOzonFeedWithoutAnyNewWrite()throws Exception {
        ready();var monitor=new ZnackRegistrationMonitor(null,workflow);var waiting=monitor.poll(shop,3);
        assertEquals(1,waiting.checked());assertFalse(waiting.pending());assertEquals(0,api.signatures.get());
        api.accepted=true;var done=monitor.poll(shop,3);assertEquals(1,done.published());assertEquals(Status.PUBLISHED,row().status());assertEquals("",row().activeGtin());
        assertEquals(0,api.signatures.get());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void preparationRecognizesTheRealOzonEaeuTnvedAttributeName()throws Exception {
        var base=snapshot;snapshot=new ProductSnapshot(base.key(),base.revision(),base.title(),base.description(),base.brand(),base.vendorArticle(),base.categoryId(),base.media(),List.of(new ProductAttribute("22232","ТН ВЭД коды ЕАЭС",List.of("6204"),null)),base.variants(),base.dimensions(),base.documents(),base.checkedAt(),base.metadata());
        assertEquals("6204",workflow.prepare(shop,key).tnved());
    }
    @Test void preparationExtractsTheRealOzonDictionaryCodeWithoutConcatenatingDescriptionDigits()throws Exception {
        var base=snapshot;snapshot=new ProductSnapshot(base.key(),base.revision(),base.title(),base.description(),base.brand(),base.vendorArticle(),base.categoryId(),base.media(),List.of(new ProductAttribute("22232","ТН ВЭД коды ЕАЭС",List.of("6103420001 - Брюки и бриджи, мужские или для мальчиков, из хлопчатобумажной пряжи."),null)),base.variants(),base.dimensions(),base.documents(),base.checkedAt(),base.metadata());
        assertEquals("6103420001",workflow.prepare(shop,key).tnved());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
        assertEquals("",ProductFieldNames.tnvedCode("6103420001 / 6103430001"));
        assertEquals("",ProductFieldNames.tnvedCode("6103420001 - код 6103430001"));
    }
    @Test void preparationFetchesNativeIdentityAndReusesOneSessionForCategorySelection()throws Exception {
        api.multipleCategories=true;var prepared=workflow.prepare(shop,key);
        assertEquals("6204",prepared.tnved());assertEquals(1,prepared.categoryId());assertEquals(2,prepared.preflight().categories().size());assertFalse(prepared.required().isEmpty());
        var selected=workflow.withCategory(shop,prepared,1);assertEquals(1,selected.categoryId());assertEquals("Fixture",selected.suggestions().get(1L));assertEquals(1,sessions.get());
        assertFalse(selected.toString().contains("fixture-token"));assertFalse(selected.preflight().toString().contains("fixture-token"));
        assertThrows(IllegalArgumentException.class,()->workflow.withCategory(shop,prepared,999));assertEquals(0,api.allocations.get());
    }
    @Test void batchPreviewDoesNotWriteAndEveryApprovedNativeSkuIsSubmitted()throws Exception {
        api.knownQuota=true;var second=new ProductKey(2,Marketplace.OZON,"502","fixture-offer-2");var base=snapshot;
        var snapshots=new HashMap<ProductKey,ProductSnapshot>();snapshots.put(key,base);
        snapshots.put(second,new ProductSnapshot(second,"live:second","Second","",base.brand(),"fixture-offer-2",base.categoryId(),base.media(),base.attributes(),
                List.of(new ProductVariant("fixture-offer-2","fixture-offer-2","","","",false)),base.dimensions(),base.documents(),Instant.now(),Map.of("sku","9002","owner",com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop))));
        var batchWorkflow=new OzonCardRegistrationWorkflow(repository,s->{sessions.incrementAndGet();return session();},(s,k)->{api.activeKey=k;return snapshots.get(k);},Runnable::run,(s,k,sku,gtin)->false);
        var review=batchWorkflow.prepareBatch(shop,List.of(key,second),false,n->{});
        assertEquals(2,review.ready().size());assertEquals(1,sessions.get());assertEquals(0,api.allocations.get());
        assertEquals(2,batchWorkflow.submitBatchReviewed(shop,review.ready(),false,true,null).get(10,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(2,api.allocations.get());assertNotNull(repository.find(second));assertNotNull(repository.find(key));
    }
    @Test void unknownTnvedDoesNotAuthenticateOrAllocateBeforeExplicitEntry()throws Exception {
        snapshot=snapshot("live:missing",false);var prepared=workflow.prepare(shop,key);
        assertEquals("",prepared.tnved());assertNull(prepared.preflight());assertEquals(0,sessions.get());assertEquals(0,api.allocations.get());
        var explicit=workflow.prepare(shop,key,"6204");assertNotNull(explicit.preflight());assertEquals(1,sessions.get());
    }
    @Test void reviewedProductChangesAndDifferentPermitStopBeforeAllocation()throws Exception {
        var prepared=workflow.prepare(shop,key);snapshot=snapshot("live:changed",true);
        assertTrue(workflow.registerReviewed(shop,prepared,draft,true,false,null));assertNull(row());assertEquals(0,api.allocations.get());
        var forged=new Draft("6204",1,"Fixture","Brand",Map.of(1L,"Fixture",ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID,"FOREIGN:::2025-01-01"));
        assertTrue(workflow.register(shop,key,forged,true,false,null));assertNull(row());assertEquals(0,api.allocations.get());
    }
    @Test void oneReviewedPermitSatisfiesAlternativePermitSchemaAndSigningIsExplicit() {
        ready();assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());
        assertTrue(row().payload().contains("D:::2025-01-01"));assertFalse(row().payload().contains("23561"));assertEquals("",row().activeGtin());
        assertFalse(workflow.signApproved(shop,List.of(key),false,null));assertEquals(0,api.signatures.get());
        assertTrue(workflow.signApproved(shop,List.of(key),true,null));assertEquals(Status.PUBLISHED,row().status());assertEquals(1,api.signatures.get());
        assertTrue(new GtinSyncRepository().catalog(2,Marketplace.OZON).stream().anyMatch(g->g.gtin().equals(row().gtin())&&g.source().matches("National Catalog@[a-f0-9]{64}")));
    }
    @Test void uncertainAllocationAndFeedArePersistedAndNotRepeated() {
        api.allocationTimeout=true;assertTrue(workflow.register(shop,key,draft,true,false,null));assertEquals(Status.GTIN_REVIEW_REQUIRED,row().status());
        assertFalse(workflow.resume(shop,key,null));assertTrue(workflow.register(shop,key,draft,true,false,null));assertEquals(1,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    @Test void lostFeedResultKeepsGtinAndRequiresReadbackReview() {
        api.feedTimeout=true;assertTrue(workflow.register(shop,key,draft,true,false,null));assertEquals(Status.FEED_REVIEW_REQUIRED,row().status());assertEquals(GtinWriteTestFixture.GTIN,row().gtin());
        assertFalse(workflow.resume(shop,key,null));assertTrue(workflow.register(shop,key,draft,true,false,null));assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void ambiguousSignatureOnlyReadsBackAndNeverSignsTwice() {
        ready();api.signTimeout=true;assertTrue(workflow.signApproved(shop,List.of(key),true,null));assertEquals(Status.SIGNING,row().status());assertEquals("RECONCILE_REQUIRED",row().signatureState());
        assertTrue(workflow.signApproved(shop,List.of(key),true,null));assertEquals(1,api.signatures.get());assertEquals(Status.SIGNING,row().status());
        api.accepted=true;assertTrue(workflow.resume(shop,key,null));assertEquals(Status.PUBLISHED,row().status());assertEquals(1,api.signatures.get());assertEquals("CONFIRMED",row().signatureState());
    }
    @Test void changedSigningXmlIsKnownUnsentAndLeavesReviewedRetry() {
        ready();api.changeSigningDocument=true;assertTrue(workflow.signApproved(shop,List.of(key),true,null));
        assertEquals(0,api.signatures.get());assertEquals(Status.READY_TO_SIGN,row().status());assertEquals("PREPARED",row().signatureState());
    }
    @Test void exactMarketplaceReadbackActivatesFirstCardAndCandidateOnlyAfterCompletion() {
        published();assertTrue(workflow.complete(shop,key,true,null));assertEquals("",row().activeGtin());
        assertNull(new OzonProductGtinMappingRepository().findGtin(2,"9001"));onMarketplace=true;
        assertTrue(workflow.complete(shop,key,true,null));String old=row().gtin();assertEquals(old,row().activeGtin());assertEquals(old,new OzonProductGtinMappingRepository().findGtin(2,"9001"));
        api.accepted=false;assertTrue(workflow.register(shop,key,draft,true,true,null));assertEquals(old,row().activeGtin());assertEquals(old,new OzonProductGtinMappingRepository().findGtin(2,"9001"));
        assertTrue(workflow.signApproved(shop,List.of(key),true,null));assertEquals("00000000000024",row().gtin());assertEquals(old,row().activeGtin());
        onMarketplace=false;assertTrue(workflow.complete(shop,key,true,null));assertEquals(old,row().activeGtin());onMarketplace=true;assertTrue(workflow.complete(shop,key,true,null));assertEquals("00000000000024",row().activeGtin());assertEquals("00000000000024",new OzonProductGtinMappingRepository().findGtin(2,"9001"));
    }
    @Test void deniedLicenseWhileQueuedHasNoClaimOrApiWrite()throws Exception {
        var queued=new ArrayList<Runnable>();workflow=workflow(queued::add);assertTrue(workflow.register(shop,key,draft,true,false,null));assertTrue(OzonCardRegistrationWorkflow.hasActiveJobs());
        var prior=LicenseRuntime.service();try(var denied=LicenseDomainFixture.denied(root.resolve("denied-license"))){try{LicenseRuntime.install(denied);queued.getFirst().run();assertNull(row());assertEquals(0,api.allocations.get());}finally{LicenseRuntime.install(prior);}}
        assertFalse(OzonCardRegistrationWorkflow.hasActiveJobs());
    }
    @Test void queuedWorkCapturesShopCredentialsInsteadOfFollowingMutableUiObject()throws Exception {
        var queued=new ArrayList<Runnable>();
        workflow=new OzonCardRegistrationWorkflow(repository,s->{sessions.incrementAndGet();return session();},(s,k)->{com.vncode.app.integration.listing.ListingIdentity.require(s,k);return snapshot;},queued::add,(s,k,sku,gtin)->false);
        assertTrue(workflow.register(shop,key,draft,true,false,null));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated-fixture' WHERE id=2");}
        shop.setApiKey("rotated-fixture");queued.getFirst().run();assertNull(row());assertEquals(0,sessions.get());assertEquals(0,api.allocations.get());
    }
    @Test void licenseDenialAfterFeedClaimIsKnownUnsentAndCanResumeExactlyOnce()throws Exception {
        var prior=LicenseRuntime.service();try(var denied=LicenseDomainFixture.denied(root.resolve("denied-license"))){
            try{assertTrue(workflow.register(shop,key,draft,true,false,(state,detail)->{if(state==Status.FEED_SUBMITTING)LicenseRuntime.install(denied);}));assertEquals(Status.READY_TO_SUBMIT,row().status());assertEquals(0,api.submissions.get());}
            finally{LicenseRuntime.install(prior);}
        }
        assertTrue(workflow.resume(shop,key,null));assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void settingsChangedAfterGenerationRetainsOriginalScopeAndStopsFeed() {
        assertTrue(workflow.register(shop,key,draft,true,false,(state,detail)->{if(state==Status.GTIN_GENERATED)GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);}));
        assertEquals(1,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals(Status.READY_TO_SUBMIT,row().status());assertTrue(row().scopeJson().contains("AAAAAAAA"));
        assertTrue(workflow.resume(shop,key,null));assertEquals(0,api.submissions.get());
    }
    @Test void foreignShopAndMarketplaceKeysNeverAuthenticate() {
        assertThrows(SecurityException.class,()->workflow.prepare(shop,new ProductKey(99,Marketplace.OZON,"501","fixture-offer")));
        assertThrows(SecurityException.class,()->workflow.prepare(shop,new ProductKey(2,Marketplace.WILDBERRIES,"501","11")));assertEquals(0,sessions.get());
    }
    @Test void registryRefreshSelectsOnlyPublishedCardsBoundToCurrentEnterpriseAndCredentials()throws Exception {
        published();var scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(2,"Fixture")).getSettings());
        assertEquals(List.of(row().gtin()),NationalCatalogGtinSource.ownedPublishedGtins(shop,scope));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE ozon_card_registrations SET owner='stale-owner'");}
        assertTrue(NationalCatalogGtinSource.ownedPublishedGtins(shop,scope).isEmpty());
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_card_registrations SET owner=?,status='READY_TO_SIGN'")){p.setString(1,com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop));p.executeUpdate();}
        assertTrue(NationalCatalogGtinSource.ownedPublishedGtins(shop,scope).isEmpty());
    }
    @Test void completedNativeSkuOverridesArticleRuleWithoutChangingSiblingSize()throws Exception {
        published();onMarketplace=true;workflow.complete(shop,key,true,null);
        var mappings=new OzonProductGtinMappingRepository();
        var catalog=new com.vncode.app.integration.ozon.OzonCatalogRepository();
        catalog.upsertPage(2,List.of(new com.vncode.app.integration.ozon.OzonProductDto("501","fixture-offer","9001","Fixture","","shared-article","Black","XL",false,"",List.of()),new com.vncode.app.integration.ozon.OzonProductDto("502","other-offer","9002","Fixture sibling","","shared-article","Black","L",false,"",List.of())),"");
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(2,'00000000000024','Old article fixture','fixture')");}
        mappings.replaceArticlesForGtin(2,"00000000000024",List.of("shared-article"));
        assertEquals(GtinWriteTestFixture.GTIN,mappings.findResolvedBySku(2).get("9001"));assertEquals("00000000000024",mappings.findResolvedBySku(2).get("9002"));
        assertEquals("00000000000024",mappings.findAllArticles(2).get("shared-article"));
        api.accepted=false;workflow.register(shop,key,draft,true,true,null);
        assertEquals(GtinWriteTestFixture.GTIN,mappings.findResolvedBySku(2).get("9001"));assertEquals("00000000000024",mappings.findResolvedBySku(2).get("9002"));
        var scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(2,"Fixture")).getSettings());
        assertEquals(List.of(GtinWriteTestFixture.GTIN),NationalCatalogGtinSource.ownedPublishedGtins(shop,scope),"The previous published active card is re-read while its replacement is pending");
    }
    final class FixtureApi extends ZnackApiClient {
        AtomicInteger allocations=new AtomicInteger(),submissions=new AtomicInteger(),signatures=new AtomicInteger(),documents=new AtomicInteger();
        ProductKey activeKey=key;boolean knownQuota;
        boolean multipleCategories,allocationTimeout,feedTimeout,signTimeout,changeSigningDocument,accepted,rejected,pendingModeration;
        String currentGtin=GtinWriteTestFixture.GTIN;
        public JsonElement generatedGtins(String base,String token){return json(knownQuota?"{\"result\":{\"drafts\":[],\"monthly-limit\":{\"limit\":100,\"usage\":0}}}":"{\"result\":{\"drafts\":[]}}");}
        public JsonElement generateGtins(String base,String token,int quantity)throws IOException{assertEquals(Status.ALLOCATING_GTIN,repository.find(activeKey).status());int count=allocations.incrementAndGet();if(allocationTimeout)throw new IOException("fixture allocation timeout");currentGtin=count==1?GtinWriteTestFixture.GTIN:"00000000000024";return json("{\"result\":{\"drafts\":[{\"gtin\":\""+currentGtin+"\"}]}}");}
        public JsonElement nationalCatalogCategories(String base,String token,String tnved){return json(multipleCategories?"{\"result\":[{\"cat_id\":1,\"cat_name\":\"Fixture\",\"category_active\":true},{\"cat_id\":2,\"cat_name\":\"Fixture 2\",\"category_active\":true}]}":"{\"result\":[{\"cat_id\":1,\"cat_name\":\"Fixture\",\"category_active\":true}]}");}
        public JsonElement nationalCatalogAttributes(String base,String token,long category){return json("{\"result\":[{\"attr_id\":1,\"attr_name\":\"Fixture\",\"attr_field_type\":\"text\"},{\"attr_id\":23557,\"attr_name\":\"Declaration\",\"attr_field_type\":\"text\"},{\"attr_id\":23561,\"attr_name\":\"Certificate\",\"attr_field_type\":\"text\"}]}");}
        public JsonElement submitNationalCatalogFeed(String base,String token,JsonElement body)throws IOException{assertEquals(Status.FEED_SUBMITTING,repository.find(activeKey).status());submissions.incrementAndGet();if(feedTimeout)throw new IOException("fixture feed timeout");return json("{\"result\":{\"feed_id\":\"feed-"+submissions.get()+"\"}}");}
        public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){if(rejected)return json("{\"result\":{\"status\":\"Rejected\",\"item\":[{\"gtin\":\""+currentGtin+"\",\"status_code\":400,\"message\":\"required attribute missing; token=fixture-secret\"}]}}");return json("{\"result\":{\"status\":\""+(pendingModeration?"Processing":accepted?"Signed":"Moderated")+"\",\"item\":[{\"gtin\":\""+currentGtin+"\",\"good_id\":91}]}}");}
        public JsonElement nationalCatalogSigningDocument(String base,String token,JsonObject body){int count=documents.incrementAndGet();return json("{\"result\":{\"xmls\":[{\"goodId\":91,\"xml\":\"<good>"+(changeSigningDocument&&count>1?"changed":"reviewed")+"</good>\"}]}}");}
        public JsonElement signNationalCatalogProduct(String base,String token,JsonArray body)throws IOException{assertEquals(Status.SIGNING,repository.find(activeKey).status());assertEquals("SENDING",repository.find(activeKey).signatureState());assertEquals("<good>reviewed</good>",new String(Base64.getDecoder().decode(body.get(0).getAsJsonObject().get("base64Xml").getAsString()),java.nio.charset.StandardCharsets.UTF_8));signatures.incrementAndGet();if(signTimeout)throw new IOException("fixture signing timeout");accepted=true;return json("{\"result\":{\"signed\":[91]}}");}
        JsonElement json(String value){return JsonParser.parseString(value);}
    }
}
