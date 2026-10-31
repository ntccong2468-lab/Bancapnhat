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
    RegistrationExistingCards.LookupContext emptyLookup(){return new RegistrationExistingCards.LookupContext((s,c)->List.of(),(c,g,id)->{throw new AssertionError("A complete empty Catalog cannot have an observed candidate");},(s,c)->com.vncode.app.features.gtinsync.RegisteredGtinProof.source(s));}
    OzonCardRegistrationWorkflow workflow(java.util.concurrent.Executor executor){return new OzonCardRegistrationWorkflow(repository,s->{sessions.incrementAndGet();return session();},(s,k)->snapshot,executor,(s,k,sku,gtin)->onMarketplace,this::emptyLookup);}
    boolean sign(){try{return workflow.signApprovedReviewed(shop,workflow.prepareApprovedSigning(shop,List.of(key)),null);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}}
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
    void sourceTnved(String code){
        var attrs=new ArrayList<>(snapshot.attributes().stream().filter(a->!ProductFieldNames.matches("tnved",a.name())).toList());attrs.add(new ProductAttribute("tnved","ТН ВЭД",List.of(code),null));
        snapshot=new ProductSnapshot(snapshot.key(),snapshot.revision(),snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),attrs,snapshot.variants(),snapshot.dimensions(),snapshot.documents(),Instant.now(),snapshot.metadata());
    }
    void sourceCountry(String country){
        var attrs=new ArrayList<>(snapshot.attributes());attrs.add(new ProductAttribute("country","Страна производства",List.of(country),null));
        snapshot=new ProductSnapshot(snapshot.key(),snapshot.revision(),snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),attrs,snapshot.variants(),snapshot.dimensions(),snapshot.documents(),Instant.now(),snapshot.metadata());
    }
    OzonCardRegistrationRepository.Row row(){return repository.find(key);}
    RegistrationIntent reviewedStart(Draft submitted) { try {return reviewedStart(workflow.prepare(shop,key),submitted);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);} }
    RegistrationIntent reviewedStart(OzonCardRegistrationWorkflow.Preparation prepared,Draft submitted) {return reviewedStart(prepared,submitted,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null));}
    RegistrationIntent reviewedStart(OzonCardRegistrationWorkflow.Preparation prepared,Draft submitted,RegistrationSourceConsent.Target target) { return reviewedStart(prepared,submitted,target,null); }
    RegistrationIntent reviewedStart(OzonCardRegistrationWorkflow.Preparation prepared,Draft submitted,RegistrationSourceConsent.Target target,java.util.function.BiConsumer<Status,String> callback) { try {
        var source=prepared.snapshot();var schema=prepared.required();
        RegistrationFieldProvenance.save(shop,List.of(RegistrationFieldProvenance.capture(source,source,submitted,schema,Map.of(),null,false)));
        var plan=new RegistrationDynamicPreflight.Plan(source,submitted,schema,prepared.documents());
        var approval=RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(plan),Map.of(key,target),Map.of(),RegistrationFieldProvenance.readBatch(shop,List.of(plan)),true);
        var queue=new OzonRegistrationBatchRepository();String run=OzonRegistrationAttemptReports.queue(shop,List.of(key));
        queue.create(shop,List.of(key),submitted.autoSign(),session().scope(),run,approval);
        var intent=new RegistrationIntent(run,0);assertTrue(workflow.registerReviewedForBatch(shop,prepared,submitted,callback,target.action()==RegistrationSourceConsent.Action.EXISTING?workflow.lookupOperation():emptyLookup(),intent));
        if(row()!=null&&!row().feedId().isBlank())queue.item(queue.next(queue.latest(shop)),"WAITING_REMOTE","");
        return intent;
    }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}
    }
    RegistrationIntent ready(){var intent=reviewedStart(draft);assertEquals(Status.READY_TO_SIGN,row().status());new OzonRegistrationBatchRepository().state(intent.runId(),"COMPLETED","");return intent;}
    @Test void monitorReadsLegacyReceiptButCannotAutomaticallySignWithoutSourceConsent()throws Exception{
        ready();RegistrationSigningConsent.set(shop,key,row().gtin(),true);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM znack_source_consents");}
        new ZnackRegistrationMonitor(null,workflow).poll(shop,3);
        assertEquals(0,api.signatures.get());assertEquals(Status.READY_TO_SIGN,row().status());
    }
    @Test void newlyReviewedExactSubmissionCanStillBeAutomaticallySigned()throws Exception{
        sourceTnved("6204420000");draft=new Draft("6204420000","6204",1,"Fixture","Brand",Map.of(1L,"Fixture",ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID,"D:::2025-01-01"));
        ready();var batches=new OzonRegistrationBatchRepository();var batch=batches.latest(shop);
        batches.item(new OzonRegistrationBatchRepository.Item(batch.id(),0,key,"PENDING",""),"SUBMITTED","");
        RegistrationSigningConsent.set(shop,key,row().gtin(),true);
        assertTrue(OzonSourceConsentPolicy.permitsAutomatic(shop,key,row().gtin()));
        assertTrue(workflow.signConsented(shop,List.of(key),null));assertNull(workflow.failure(shop));assertEquals(1,api.signatures.get(),row().status()+" / "+row().error());
    }
    RegistrationSourceConsent.Prepared reviewedBatch(OzonCardRegistrationWorkflow worker,List<ProductKey> keys)throws Exception {
        return reviewedBatch(worker,keys,false);
    }
    RegistrationSourceConsent.Prepared reviewedBatch(OzonCardRegistrationWorkflow worker,List<ProductKey> keys,boolean allowRepair)throws Exception {
        var reviewed=worker.prepareBatch(shop,keys,false,n->{});assertEquals(keys.size(),reviewed.ready().size());
        var plans=new ArrayList<RegistrationDynamicPreflight.Plan>();var targets=new HashMap<ProductKey,RegistrationSourceConsent.Target>();
        for(var item:reviewed.ready()){
            var p=item.preparation();var product=p.snapshot();var proposed=item.draft();
            RegistrationFieldProvenance.save(shop,List.of(RegistrationFieldProvenance.capture(product,product,proposed,p.required(),Map.of(),null,false)));
            plans.add(new RegistrationDynamicPreflight.Plan(product,proposed,p.required(),p.documents()));
            targets.put(item.key(),new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null));
        }
        return RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),plans,targets,Map.of(),RegistrationFieldProvenance.readBatch(shop,plans),allowRepair);
    }
    @Test void explicitFeed429RetainsTheSameGtinForRetryInsteadOfAnUncertainSend(){
        api.feedRateLimits=2;var intent=reviewedStart(draft);
        assertEquals(Status.READY_TO_SUBMIT,row().status());String gtin=row().gtin();assertTrue(row().feedId().isBlank());
        assertTrue(workflow.resumeForBatch(shop,key,false,null,intent));assertEquals(Status.READY_TO_SUBMIT,row().status());assertEquals(gtin,row().gtin());
        assertTrue(workflow.resumeForBatch(shop,key,false,null,intent));assertEquals(gtin,row().gtin());assertFalse(row().feedId().isBlank());
        assertEquals(1,api.allocations.get());assertEquals(3,api.submissions.get());
    }
    @Test void explicitAllocation429IsNotAnUnknownAllocation() throws Exception {
        api.allocationRateLimits=1;var intent=reviewedStart(draft);
        assertEquals(Status.PREFLIGHT_ERROR,row().status());assertTrue(row().gtin().isBlank());assertEquals(0,api.submissions.get());
        assertTrue(workflow.registerReviewedForBatch(shop,workflow.prepare(shop,key),draft,null,emptyLookup(),intent));assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(2,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void batchPersistsTheServersLongerWriteRetryDeadlineAndKeepsTheSameIdentity()throws Exception{
        api.feedRateLimits=1;api.writeRetryAfter=Duration.ofHours(1);
        var queue=singleCardBatch("PENDING");var batch=new OzonRegistrationBatchService(queue,repository,workflow);
        try{
            assertTrue(batch.resumePending(shop));long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<until&&!queue.latest(shop).state().equals("WAITING"))Thread.sleep(10);
            assertEquals("WAITING",queue.latest(shop).state());assertEquals(Status.READY_TO_SUBMIT,row().status());
            assertFalse(row().gtin().isBlank());assertTrue(row().feedId().isBlank());
            var reopened=new OzonRegistrationBatchRepository();assertTrue(reopened.latest(shop).nextRetryAt().isAfter(Instant.now().plusSeconds(3590)));
            assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(1,queue.counts(queue.latest(shop).id()).remaining());
            assertTrue(batch.resumePending(shop));assertEquals(1,api.submissions.get());
        }finally{batch.cancel(shop);}
    }
    void published(){ready();assertTrue(sign());assertEquals(Status.PUBLISHED,row().status());}
    @Test void externallySignedCardWithLaggedFeedDoesNotSignAgain()throws Exception{
        ready();api.accepted=true;api.laggedModeration=true;assertTrue(workflow.refreshStatus(shop,key));
        assertEquals(Status.PUBLISHED,row().status());assertEquals(0,api.signatures.get());assertEquals(0,api.documents.get());
    }
    @Test void tariffScopeWarningRequiresReviewInsteadOfInventingACatalogRejection()throws Exception{
        api.permitScope="6101, 6201, 6202, 6211";var prepared=workflow.prepare(shop,key);
        assertNull(prepared.documents().automatic());assertTrue(prepared.documents().requiresReview());
        assertTrue(prepared.documents().issues().stream().anyMatch(RegistrationPermitValidator::advisory));
        assertFalse(workflow.registerReviewed(shop,prepared,draft,false,false,null));
        assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
        reviewedStart(prepared,draft);
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(Status.READY_TO_SIGN,row().status());
    }
    @Test void mandatoryExternalCountryDictionaryIsEnrichedForPlanningAndSubmitValidation()throws Exception{
        api.mandatoryCountry=true;api.knownQuota=true;sourceCountry("Россия");
        var prepared=workflow.prepare(shop,key);var automatic=OzonCardRegistrationWorkflow.suggestedDraft(prepared).orElseThrow();
        assertEquals("RU",automatic.attributes().get(2630L));assertEquals(List.of("RU","CN"),prepared.required().stream().filter(attribute->attribute.id()==2630L).findFirst().orElseThrow().presets());
        reviewedStart(prepared,automatic);assertEquals(Status.READY_TO_SIGN,row().status());
        assertTrue(api.lastPayload.getAsJsonArray("good_attrs").asList().stream().map(JsonElement::getAsJsonObject).anyMatch(attribute->attribute.get("attr_id").getAsLong()==2630L&&attribute.get("attr_value").getAsString().equals("RU")));
        assertEquals(2,api.countryReads.get(),"The no-store dictionary is pinned for planning and freshly verified before submission");
    }
    @Test void sellerReviewedRegistrationUsesExplicitSourceCountryWithoutRejectingEmptyInlinePresets() throws Exception {
        api.mandatoryCountry=true;sourceCountry("Россия");
        var prepared=workflow.prepare(shop,key);var automatic=OzonCardRegistrationWorkflow.suggestedDraft(prepared).orElseThrow();
        reviewedStart(prepared,automatic);assertEquals(Status.READY_TO_SIGN,row().status());
        assertTrue(api.lastPayload.getAsJsonArray("good_attrs").asList().stream().map(JsonElement::getAsJsonObject).anyMatch(attribute->attribute.get("attr_id").getAsLong()==2630L&&attribute.get("attr_value").getAsString().equals("RU")));
        assertEquals(2,api.countryReads.get(),"Seller review and submission must each check the current dictionary");
    }
    @Test void missingSourceCountryCannotCreateOrSubmitACard(){
        api.mandatoryCountry=true;
        assertTrue(workflow.register(shop,key,draft,true,false,null));
        assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
        assertNotNull(workflow.failure(shop));assertTrue(workflow.failure(shop).getMessage().contains("2630"),()->workflow.failure(shop).toString());
    }
    @Test void unannouncedOptionalAttributeStopsBeforeAllocatingOrPosting(){
        var values=new LinkedHashMap<Long,String>(draft.attributes());values.put(999L,"Unannounced");var invalid=new Draft(draft.tnved(),draft.feedTnved(),draft.categoryId(),draft.goodName(),draft.brand(),values,draft.attributeTypes());
        assertTrue(workflow.register(shop,key,invalid,true,false,null));assertNull(row());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertNotNull(workflow.failure(shop));assertTrue(workflow.failure(shop).getMessage().contains("ZNACK_INVALID_ATTRIBUTE_VALUE"));
    }
    @Test void legacyUnsentEditCannotSubmitWithoutTheNewDurableReview()throws Exception{
        ready();String gtin=row().gtin();
        try(var c=Database.getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE ozon_card_registrations SET status='READY_TO_SUBMIT',feed_id='',good_id=91");}
        assertFalse(JsonParser.parseString(row().payload()).getAsJsonObject().has("good_id"));
        assertTrue(workflow.resume(shop,key,false,null));assertEquals(Status.READY_TO_SUBMIT,row().status());assertEquals(gtin,row().gtin());
        assertFalse(JsonParser.parseString(row().payload()).getAsJsonObject().has("good_id"));assertEquals(91L,row().goodId());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void definiteRejectionAfterSigningTimeoutIsNotHiddenBehindSigning()throws Exception {
        ready();api.signTimeout=true;sign();assertEquals(Status.SIGNING,row().status());
        api.rejected=true;workflow.refreshStatus(shop,key);assertEquals(Status.ERROR,row().status());
        assertTrue(row().error().contains("required attribute missing"));assertFalse(row().error().contains("fixture-secret"));
        assertEquals(1,api.signatures.get());assertEquals(1,api.submissions.get());assertEquals(1,api.allocations.get());
    }
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
    @Test void changedTnvedGroupCannotReplaceTheOldCardWithoutDurableApproval()throws Exception {
        ready();String old=row().gtin();sourceTnved("6104");var changed=new Draft("6104",1,"Fixture","Brand",draft.attributes());
        assertTrue(workflow.register(shop,key,changed,true,false,null));assertEquals(old,row().gtin());assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertTrue(row().error().contains("TN VED")||row().error().contains("TNVED"));assertFalse(repository.claim(shop,key,"9001","",session().scope(),false,"6104"));
        assertFalse(workflow.register(shop,key,changed,true,true,null));assertEquals(old,row().gtin());assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertTrue(repository.claimedGtins().contains(old));
        try(var c=Database.getConnection();var st=c.createStatement();var r=st.executeQuery("SELECT COUNT(*) FROM ozon_card_registration_history")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }
    @Test void rejectionReasonSurvivesMonitoringAndSecretsAreNotCopiedIntoTheReport()throws Exception {
        ready();api.rejected=true;new ZnackRegistrationMonitor(null,workflow).poll(shop,3);assertEquals(Status.ERROR,row().status());assertTrue(row().error().contains("required attribute missing"));assertFalse(row().error().contains("fixture-secret"));assertEquals(1,api.submissions.get());
    }
    @Test void genericRejectedFeedCannotBeRetriedWithoutAnEvidenceBasedCorrection()throws Exception{
        ready();String gtin=row().gtin(),oldFeed=row().feedId();api.rejectedFeed=oldFeed;new ZnackRegistrationMonitor(null,workflow).poll(shop,3);assertEquals(Status.ERROR,row().status());
        assertTrue(workflow.resume(shop,key,false,null));assertEquals(Status.ERROR,row().status());assertEquals(gtin,row().gtin());assertEquals(oldFeed,row().feedId());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void confirmedRejectionCannotAllocateWithoutSellerReviewedIntent()throws Exception{
        ready();String old=row().gtin(),feed=row().feedId();api.rejectedFeed=feed;new ZnackRegistrationMonitor(null,workflow).poll(shop,3);
        assertFalse(workflow.register(shop,key,draft,true,true,null));assertEquals(old,row().gtin());assertEquals(feed,row().feedId());assertEquals(Status.ERROR,row().status());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertTrue(repository.claimedGtins().contains(old));
    }
    @Test void pendingOzonFeedCannotAllocateANewGtinThroughTheExplicitRecoveryAction(){
        ready();repository.progress(key,Status.ERROR,91L,"fixture previous read failed");api.pendingModeration=true;
        String old=row().gtin();assertFalse(workflow.register(shop,key,draft,true,true,null));assertEquals(old,row().gtin());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void anErrorItemInAProcessingFeedNeverAllowsExplicitRetryToResubmit(){
        ready();api.rejectedFeed=row().feedId();api.rejectionStatus="Processing";repository.progress(key,Status.ERROR,91L,"temporary error");
        assertTrue(workflow.resume(shop,key,false,null));assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(Status.ERROR,row().status());
    }
    @Test void explicitRejectedKitRepairKeepsTheGtinAndArchivesTheOriginalPayload()throws Exception{
        var intent=ready();String gtin=row().gtin();api.rejectionEvidence=true;api.rejectedFeed=row().feedId();api.rejectionMessage="Карточка должна быть создана с типом Комплект";new ZnackRegistrationMonitor(null,workflow).poll(shop,3);
        assertTrue(workflow.advanceSubmissionForBatch(shop,key,intent));assertEquals(gtin,row().gtin());assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(1,api.allocations.get());assertEquals(2,api.submissions.get());assertTrue(JsonParser.parseString(row().payload()).getAsJsonObject().get("is_kit").getAsBoolean());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT payload_json FROM ozon_card_registration_history")){assertTrue(r.next());assertFalse(JsonParser.parseString(r.getString(1)).getAsJsonObject().has("is_kit"));}
    }
    @Test void savedBatchCorrectsAProvenKitRejectionWithoutAnotherCreateClick()throws Exception{
        var intent=ready();String gtin=row().gtin(),feed=row().feedId();api.rejectionEvidence=true;api.rejectedFeed=feed;
        api.rejectionMessage="Карточка должна быть создана с типом Комплект";
        assertTrue(workflow.advanceSubmissionForBatch(shop,key,intent));
        assertEquals(gtin,row().gtin());assertEquals(Status.READY_TO_SIGN,row().status());
        assertEquals(1,api.allocations.get());assertEquals(2,api.submissions.get());
        assertTrue(api.lastPayload.get("is_kit").getAsBoolean());
        assertFalse(row().feedId().equals(feed));assertEquals(0,api.signatures.get());
    }
    @Test void unavailableImageIsNotRemovedWithoutEvidenceThatItIsOptional()throws Exception{
        ready();String gtin=row().gtin(),feed=row().feedId();
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_card_registrations SET payload_json=? WHERE shop_id=2")){
            var payload=JsonParser.parseString(row().payload()).getAsJsonObject();var images=new JsonArray();images.add("https://fixture.invalid/image.jpg");payload.add("good_images",images);p.setString(1,payload.toString());p.executeUpdate();
        }
        api.rejectionEvidence=true;api.rejectedFeed=feed;api.rejectionMessage="Изображение не доступно по URL https://fixture.invalid/image.jpg";
        assertTrue(workflow.advanceSubmissionForBatch(shop,key));
        assertEquals(1,api.submissions.get());assertEquals(1,api.allocations.get());assertEquals(gtin,row().gtin());
        assertEquals(feed,row().feedId());assertEquals(Status.ERROR,row().status());
        assertTrue(JsonParser.parseString(row().payload()).getAsJsonObject().has("good_images"));
    }
    @Test void automaticRepairNeverRetriesAnUncertainFeedOrHidesRequiredFieldErrors()throws Exception{
        ready();api.rejectedFeed=row().feedId();api.rejectionStatus="Processing";
        api.rejectionMessage="Карточка должна быть создана с типом Комплект";
        assertTrue(workflow.advanceSubmissionForBatch(shop,key));assertEquals(1,api.submissions.get());
        api.rejectionStatus="Rejected";api.rejectionMessage="required attribute missing";
        assertTrue(workflow.advanceSubmissionForBatch(shop,key));assertEquals(1,api.submissions.get());assertEquals(Status.ERROR,row().status());
        assertEquals(1,api.allocations.get());
    }
    @Test void healthyRemoteWaitDoesNotSpendOzonsFailureRetryBudget(){
        var queue=new OzonRegistrationBatchRepository();String run=queue.create(shop,List.of(key),false,session().scope());
        for(int n=0;n<10;n++)assertTrue(queue.defer(run,"CATALOG_REQUEST_ACCEPTED_CARD_PENDING",15,false));
        assertEquals("WAITING_REMOTE",queue.latest(shop).state());assertEquals(0,queue.latest(shop).retryCount());assertNotNull(queue.latest(shop).nextRetryAt());
    }
    @Test void firstCreateClickCorrectsAndSubmitsAnOzonBatchWithoutASecondAction()throws Exception{
        api.rejectionEvidence=true;api.rejectedFeed="feed-1";api.rejectionMessage="Карточка должна быть создана с типом Комплект";
        var queue=singleCardBatch("PENDING",true);var batch=new OzonRegistrationBatchService(queue,repository,workflow);
        assertTrue(batch.resumePending(shop));awaitSubmitted(queue);
        assertEquals(1,queue.counts(queue.latest(shop).id()).submitted());assertEquals(0,queue.counts(queue.latest(shop).id()).failed());
        assertEquals(1,api.allocations.get());assertEquals(2,api.submissions.get());assertEquals(Status.READY_TO_SIGN,row().status());assertTrue(api.lastPayload.get("is_kit").getAsBoolean());
        assertFalse(batch.resumePending(shop));
    }
    @Test void firstCreateClickDoesNotAutomaticallyCorrectWithoutSellerPermission()throws Exception{
        api.rejectionEvidence=true;api.rejectedFeed="feed-1";api.rejectionMessage="Карточка должна быть создана с типом Комплект";
        var queue=singleCardBatch("PENDING");var batch=new OzonRegistrationBatchService(queue,repository,workflow);
        assertTrue(batch.resumePending(shop));awaitSubmitted(queue);
        assertEquals(0,queue.counts(queue.latest(shop).id()).submitted());assertEquals(1,queue.counts(queue.latest(shop).id()).failed());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(Status.ERROR,row().status());
    }
    @Test void terminalOzonBatchCanCorrectTwoDistinctProvenRejectionsButNotAThird()throws Exception{
        var intent=ready();String gtin=row().gtin();api.rejectionEvidence=true;api.rejectedFeed=row().feedId();api.rejectionMessage="Карточка должна быть создана с типом Комплект";
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(2,?,?,?,'Fixture',?)")){p.setString(1,key.cardId());p.setString(2,key.variantId());p.setString(3,"9001");p.setString(4,Instant.now().toString());p.executeUpdate();}
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);
        workflow.refreshStatus(shop,key);var queue=new OzonRegistrationBatchRepository();String run=intent.runId();
        queue.item(new OzonRegistrationBatchRepository.Item(run,0,key,"WAITING_REMOTE",""),"FAILED",row().error());queue.state(run,"COMPLETED","");
        var batch=new OzonRegistrationBatchService(queue,repository,workflow);assertTrue(batch.resumePending(shop));awaitSubmitted(queue);
        assertEquals(gtin,row().gtin());assertEquals(1,queue.counts(run).submitted());assertEquals(2,api.submissions.get());assertEquals(1,api.allocations.get());
        api.rejectedFeed=row().feedId();api.rejectionMessage="is_kit не допускается. Исключите параметр";workflow.refreshStatus(shop,key);
        queue.item(new OzonRegistrationBatchRepository.Item(run,0,key,"SUBMITTED",""),"FAILED",row().error());queue.state(run,"COMPLETED","");
        assertTrue(batch.resumePending(shop));awaitSubmitted(queue);assertEquals(3,api.submissions.get());assertEquals(1,api.allocations.get());assertEquals(gtin,row().gtin());
        api.rejectedFeed=row().feedId();api.rejectionMessage="Карточка должна быть создана с типом Комплект";workflow.refreshStatus(shop,key);
        queue.item(new OzonRegistrationBatchRepository.Item(run,0,key,"SUBMITTED",""),"FAILED",row().error());queue.state(run,"COMPLETED","");
        assertTrue(batch.resumePending(shop));long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline&&!Set.of("FAILED","COMPLETED").contains(queue.latest(shop).state()))Thread.sleep(10);
        assertEquals(3,api.submissions.get());assertEquals(1,api.allocations.get());assertEquals(gtin,row().gtin());assertEquals(Status.ERROR,row().status());
    }
    @Test void unavailableSourcesDuringReadbackDeferTheBatchWithoutResubmitting()throws Exception{
        var intent=ready();String feed=row().feedId(),gtin=row().gtin();api.rejectionEvidence=true;api.rejectedFeed=feed;api.rejectionMessage="Карточка должна быть создана с типом Комплект";
        workflow.refreshStatus(shop,key);var queue=new OzonRegistrationBatchRepository();String run=intent.runId();
        queue.item(new OzonRegistrationBatchRepository.Item(run,0,key,"WAITING_REMOTE",""),"FAILED",row().error());queue.state(run,"COMPLETED","");
        var batch=new OzonRegistrationBatchService(queue,repository,workflow);
        try{assertTrue(batch.resumePending(shop));long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<until&&!queue.latest(shop).state().equals("WAITING"))Thread.sleep(10);
            assertEquals("WAITING",queue.latest(shop).state());assertTrue(queue.latest(shop).detail().contains("SOURCE_SYNC_REQUIRED"));assertNotNull(queue.latest(shop).nextRetryAt());
            assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(gtin,row().gtin());assertEquals(feed,row().feedId());
        }finally{batch.cancel(shop);}
    }
    @Test void aSlowOzonImportIsPendingRatherThanRejectedByTheLocalClock()throws Exception{
        ready();String feed=row().feedId();api.cardMissing=true;
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE ozon_card_registrations SET created_at='2020-01-01T00:00:00Z'");}
        assertFalse(workflow.refreshStatus(shop,key));assertEquals(Status.FEED_SUBMITTED,row().status());assertEquals("CATALOG_REQUEST_ACCEPTED_CARD_PENDING",row().error());
        api.cardMissing=false;assertFalse(workflow.refreshStatus(shop,key));assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(feed,row().feedId());assertEquals(1,api.submissions.get());assertEquals(1,api.allocations.get());
    }
    private void awaitSubmitted(OzonRegistrationBatchRepository queue)throws Exception{
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<until&&(!queue.latest(shop).state().equals("COMPLETED")||OzonRegistrationBatchService.hasPendingJobs()))Thread.sleep(10);
        assertEquals("COMPLETED",queue.latest(shop).state());assertFalse(OzonRegistrationBatchService.hasPendingJobs());
    }
    @Test void automaticSigningConsentSurvivesReopeningAndDoesNotRepeatTheSignature()throws Exception {
        draft=new Draft("6204430000","6204",1,"Fixture","Brand",Map.of(1L,"Fixture",13933L,"6204430000",ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID,"D:::2025-01-01"),Map.of());
        sourceTnved("6204430000");api.pendingModeration=true;var intent=reviewedStart(draft.withAutoSign(true));new OzonRegistrationBatchRepository().state(intent.runId(),"COMPLETED","");assertEquals(Status.PROCESSING,row().status());assertEquals(0,api.signatures.get());
        api.pendingModeration=false;var reopened=workflow(Runnable::run);var monitor=new ZnackRegistrationMonitor(null,reopened);assertEquals(1,monitor.poll(shop,3).published());assertEquals(Status.PUBLISHED,row().status());assertEquals(1,api.signatures.get());monitor.poll(shop,3);assertEquals(1,api.signatures.get());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void automaticSigningTimeoutNeverReplaysTheSignatureInTheBackground()throws Exception {
        draft=new Draft("6204430000","6204",1,"Fixture","Brand",Map.of(1L,"Fixture",13933L,"6204430000",ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID,"D:::2025-01-01"),Map.of());
        sourceTnved("6204430000");api.pendingModeration=true;var intent=reviewedStart(draft.withAutoSign(true));new OzonRegistrationBatchRepository().state(intent.runId(),"COMPLETED","");api.pendingModeration=false;api.signTimeout=true;var monitor=new ZnackRegistrationMonitor(null,workflow(Runnable::run));monitor.poll(shop,3);assertEquals(Status.SIGNING,row().status());assertEquals(1,api.signatures.get());monitor.poll(shop,3);assertEquals(1,api.signatures.get());api.accepted=true;assertEquals(1,monitor.poll(shop,3).published());assertEquals(1,api.signatures.get());
    }
    @Test void backgroundMonitorReadsTheNativeOzonFeedWithoutAnyNewWrite()throws Exception {
        ready();var monitor=new ZnackRegistrationMonitor(null,workflow);var waiting=monitor.poll(shop,3);
        assertEquals(1,waiting.checked());assertFalse(waiting.pending());assertEquals(0,api.signatures.get());
        api.accepted=true;var done=monitor.poll(shop,3);assertEquals(1,done.published());assertEquals(Status.PUBLISHED,row().status());assertEquals("",row().activeGtin());
        assertEquals(0,api.signatures.get());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void immutableManualSigningReviewDoesNotGrantOrSignUntilTheExactDocumentIsApproved()throws Exception{
        ready();var review=workflow.prepareApprovedSigning(shop,List.of(key));assertEquals(1,review.cards().size());assertEquals(91L,review.cards().getFirst().goodId());assertEquals(0,api.signatures.get());assertFalse(RegistrationSigningConsent.allowed(shop,key,row().gtin()));
        api.changeSigningDocument=true;assertTrue(workflow.signApprovedReviewed(shop,review,null));assertEquals(0,api.signatures.get());assertFalse(RegistrationSigningConsent.allowed(shop,key,row().gtin()));assertEquals(Status.READY_TO_SIGN,row().status());
    }
    @Test void existingPublishedCatalogCardIsLinkedWithoutAllocatingOrSendingAFakeFeed()throws Exception{
        installExistingCard("published");assertTrue(workflow.register(shop,key,draft,true,false,null));assertEquals(Status.PUBLISHED,row().status());assertEquals(91L,row().goodId());assertEquals("",row().feedId());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals(0,api.signatures.get());assertFalse(RegistrationSigningConsent.allowed(shop,key,row().gtin()));
        assertTrue(workflow.register(shop,key,draft,true,false,null));assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    @Test void legacyConflictConfirmationCannotCreateOrArchiveWithoutDurableConsent()throws Exception {
        installExistingCard("published");api.allocations.set(1);var old=workflow;
        var checks=new AtomicInteger();workflow=new OzonCardRegistrationWorkflow(repository,s->session(),(s,k)->snapshot,Runnable::run,(s,k,sku,gtin)->false,old::lookupOperation,(s,k,gtin)->{checks.incrementAndGet();assertEquals(GtinWriteTestFixture.GTIN,gtin);});
        var prepared=workflow.prepare(shop,key);
        assertFalse(workflow.registerReviewedAfterConflict(shop,prepared,draft,false,GtinWriteTestFixture.GTIN,null));
        assertNull(row());assertEquals(1,api.allocations.get());
        assertFalse(workflow.registerReviewedAfterConflict(shop,prepared,draft,true,GtinWriteTestFixture.GTIN,null));
        assertEquals(0,checks.get());assertNull(row());assertEquals(1,api.allocations.get());assertEquals(0,api.submissions.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT COUNT(*) FROM ozon_card_registration_history WHERE shop_id=2 AND product_id='501'" );var r=p.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }
    @Test void unknownConflictOrUnpublishedCatalogCardCannotAllocateNewGtin()throws Exception {
        installExistingCard("published");var old=workflow;
        workflow=new OzonCardRegistrationWorkflow(repository,s->session(),(s,k)->snapshot,Runnable::run,(s,k,sku,gtin)->false,old::lookupOperation,(s,k,gtin)->{throw new IOException("NETWORK_TIMEOUT");});
        var prepared=workflow.prepare(shop,key);assertFalse(workflow.registerReviewedAfterConflict(shop,prepared,draft,true,GtinWriteTestFixture.GTIN,null));
        assertNull(row());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
        installExistingCard("draft");old=workflow;
        workflow=new OzonCardRegistrationWorkflow(repository,s->session(),(s,k)->snapshot,Runnable::run,(s,k,sku,gtin)->false,old::lookupOperation,(s,k,gtin)->{});
        prepared=workflow.prepare(shop,key);assertFalse(workflow.registerReviewedAfterConflict(shop,prepared,draft,true,GtinWriteTestFixture.GTIN,null));
        assertNull(row());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    @Test void legacyConflictRetryCannotAllocateOrArchiveWithoutConsent()throws Exception {
        installExistingCard("published");api.allocations.set(1);api.allocationRateLimits=1;var old=workflow;
        workflow=new OzonCardRegistrationWorkflow(repository,s->session(),(s,k)->snapshot,Runnable::run,(s,k,sku,gtin)->false,old::lookupOperation,(s,k,gtin)->{});
        var prepared=workflow.prepare(shop,key);
        assertFalse(workflow.registerReviewedAfterConflict(shop,prepared,draft,true,GtinWriteTestFixture.GTIN,null));
        assertFalse(workflow.registerReviewedAfterConflict(shop,prepared,draft,true,GtinWriteTestFixture.GTIN,null));
        assertNull(row());assertEquals(1,api.allocations.get());assertEquals(0,api.submissions.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT COUNT(*) FROM ozon_card_registration_history WHERE shop_id=2 AND product_id='501' AND gtin=?")){
            p.setString(1,GtinWriteTestFixture.GTIN);try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}
        }
    }
    @Test void legacyConflictTimeoutCannotAllocateWithoutConsent()throws Exception {
        installExistingCard("published");api.allocations.set(1);api.allocationTimeout=true;var old=workflow;
        workflow=new OzonCardRegistrationWorkflow(repository,s->session(),(s,k)->snapshot,Runnable::run,(s,k,sku,gtin)->false,old::lookupOperation,(s,k,gtin)->{});
        var prepared=workflow.prepare(shop,key);
        assertFalse(workflow.registerReviewedAfterConflict(shop,prepared,draft,true,GtinWriteTestFixture.GTIN,null));
        assertNull(row());assertEquals(1,api.allocations.get());assertEquals(0,api.submissions.get());
        api.allocationTimeout=false;assertFalse(workflow.registerReviewedAfterConflict(shop,prepared,draft,true,GtinWriteTestFixture.GTIN,null));
        assertNull(row());assertEquals(1,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    @Test void linkedCatalogCardCanBeReadAndManuallySignedWithoutInventingAFeedReceipt()throws Exception{
        installExistingCard("draft");assertTrue(workflow.register(shop,key,draft.withAutoSign(true),true,false,null));assertEquals(Status.READY_TO_SIGN,row().status());assertEquals("",row().feedId());assertFalse(RegistrationSigningConsent.allowed(shop,key,row().gtin()));
        assertFalse(workflow.refreshStatusWithoutSigning(shop,key));assertEquals(0,api.signatures.get());assertTrue(sign());assertEquals(Status.PUBLISHED,row().status());assertEquals("",row().feedId());assertEquals(1,api.signatures.get());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    @Test void aSellerReviewedExistingDraftSendsItsSameIdentityToModerationWithoutAllocatingOrSigning()throws Exception{
        installUnsubmittedDraft();api.pendingModeration=true;
        assertFalse(workflow.register(shop,key,draft,false,false,null));assertNull(row());assertEquals(0,api.submissions.get());
        reviewedStart(workflow.prepare(shop,key),draft.withAutoSign(true),new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.EXISTING,GtinWriteTestFixture.GTIN,91L));
        assertEquals(Status.PROCESSING,row().status());assertEquals(91L,row().goodId());assertEquals(GtinWriteTestFixture.GTIN,row().gtin());assertEquals("feed-1",row().feedId());
        assertEquals(0,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());
        assertEquals(91L,api.lastPayload.get("good_id").getAsLong());assertEquals(1,api.lastPayload.get("moderation").getAsInt());assertEquals(GtinWriteTestFixture.GTIN,api.lastPayload.get("gtin").getAsString());
        assertFalse(api.lastPayload.has("_vncode_reviewed_draft"));assertTrue(ZnackNationalCatalogService.hasReviewedDraft(JsonParser.parseString(row().payload()).getAsJsonObject()));
        assertTrue(repository.reviewedRevisions(key).stream().allMatch(hash->hash.matches("[0-9a-f]{64}")));
        assertFalse(RegistrationSigningConsent.allowed(shop,key,row().gtin()));assertFalse(RegistrationMarketplaceConsent.allowed(shop,key,row().gtin()));
        assertTrue(workflow.register(shop,key,draft.withAutoSign(true),true,false,null));assertEquals(1,api.submissions.get());assertEquals(0,api.allocations.get());assertEquals(0,api.signatures.get());
    }
    @Test void anAlreadyLinkedDraftRequiresFreshSellerReviewAndNoAllocationQuotaToSendItsFirstFeed()throws Exception{
        installUnsubmittedDraft();var observed=session().catalog().observedCardStatus("fixture-token",GtinWriteTestFixture.GTIN,91L);
        assertTrue(repository.linkObserved(shop,key,"9001","",session().scope(),draft,observed));assertTrue(row().reviewableDraft());
        assertTrue(workflow.resume(shop,key,null));assertEquals(0,api.submissions.get());assertTrue(row().reviewableDraft());
        api.forbidQuota=true;var prepared=workflow.prepare(shop,key);
        reviewedStart(prepared,draft,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.EXISTING,GtinWriteTestFixture.GTIN,91L));
        assertEquals(Status.READY_TO_SIGN,row().status(),()->row()+" / "+row().error()+" / feed="+row().feedId()+" / submissions="+api.submissions+" / "+workflow.failure(shop));assertEquals("feed-1",row().feedId());assertEquals(91L,row().goodId());assertEquals(GtinWriteTestFixture.GTIN,row().gtin());
        assertEquals(1,api.submissions.get());assertEquals(0,api.allocations.get());assertEquals(0,api.signatures.get());
    }
    @Test void anUncertainExistingDraftUpdateIsNeverAutomaticallySentTwice()throws Exception{
        installUnsubmittedDraft();api.feedTimeout=true;api.pendingModeration=true;
        reviewedStart(workflow.prepare(shop,key),draft.withAutoSign(true),new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.EXISTING,GtinWriteTestFixture.GTIN,91L));
        assertEquals(Status.FEED_REVIEW_REQUIRED,row().status());assertEquals(91L,row().goodId());assertEquals(GtinWriteTestFixture.GTIN,row().gtin());assertEquals("",row().feedId());
        var draftState=new JsonArray();draftState.add("draft");api.preExisting.add("good_detailed_status",draftState);
        assertTrue(workflow.verifySubmissionForBatch(shop,key));assertEquals(Status.FEED_REVIEW_REQUIRED,row().status());assertFalse(row().reviewableDraft());
        assertFalse(workflow.resume(shop,key,null));assertTrue(workflow.register(shop,key,draft,true,false,null));
        assertEquals(Status.FEED_REVIEW_REQUIRED,row().status());assertEquals(1,api.submissions.get());assertEquals(0,api.allocations.get());assertEquals(0,api.signatures.get());
        var moderationState=new JsonArray();moderationState.add("moderation");api.preExisting.add("good_detailed_status",moderationState);
        assertTrue(workflow.verifySubmissionForBatch(shop,key));assertEquals(Status.PROCESSING,row().status());assertFalse(row().reviewableDraft());assertEquals(1,api.submissions.get());
    }
    @Test void anAdoptedDraftWhoseReviewSourceChangedCannotBeSent()throws Exception{
        installUnsubmittedDraft();var observed=session().catalog().observedCardStatus("fixture-token",GtinWriteTestFixture.GTIN,91L);
        assertTrue(repository.linkObserved(shop,key,"9001","",session().scope(),draft,observed));var prepared=workflow.prepare(shop,key);
        var before=snapshot;snapshot=new ProductSnapshot(before.key(),"live:changed",before.title(),before.description(),before.brand(),before.vendorArticle(),before.categoryId(),before.media(),before.attributes(),before.variants(),before.dimensions(),before.documents(),before.checkedAt(),before.metadata());
        assertTrue(workflow.registerReviewed(shop,prepared,draft,true,false,null));
        assertEquals(0,api.submissions.get());assertEquals(0,api.allocations.get());assertEquals(91L,row().goodId());assertEquals(GtinWriteTestFixture.GTIN,row().gtin());assertFalse(row().payload().contains("_vncode_reviewed_draft"));
    }
    @Test void aKnownUnsentReviewedDraftResumesTheFrozenIntentWithoutASecondReviewOrAllocation()throws Exception{
        installUnsubmittedDraft();var prior=LicenseRuntime.service();RegistrationIntent intent;try(var denied=LicenseDomainFixture.denied(root.resolve("draft-denied-license"))){
            try{intent=reviewedStart(workflow.prepare(shop,key),draft,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.EXISTING,GtinWriteTestFixture.GTIN,91L),(state,detail)->{if(state==Status.FEED_SUBMITTING)LicenseRuntime.install(denied);});}
            finally{LicenseRuntime.install(prior);}
            assertEquals(Status.READY_TO_SUBMIT,row().status());assertEquals(0,api.submissions.get());String frozen=row().payload();assertTrue(ZnackNationalCatalogService.hasReviewedDraft(JsonParser.parseString(frozen).getAsJsonObject()));
            api.forbidQuota=true;assertTrue(workflow.resumeForBatch(shop,key,false,null,intent));assertEquals(Status.READY_TO_SIGN,row().status(),row().error());assertEquals(frozen,row().payload());assertEquals(1,api.submissions.get());assertEquals(0,api.allocations.get());assertEquals(0,api.signatures.get());
        }
    }
    @Test void unknownExistingCardReadNeverBecomesPermissionToAllocateOrCreate()throws Exception{
        installExistingCard("draft");api.readbackStatus=403;var original=snapshot;assertTrue(workflow.register(shop,key,draft,true,false,null));assertNull(row());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals(original,snapshot);assertNotNull(workflow.failure(shop));
    }
    private void installExistingCard(String status){
        var base=snapshot;var fields=new LinkedHashMap<String,List<String>>();fields.put("article",List.of("fixture-offer"));fields.put("color",List.of("Blue"));fields.put("size",List.of("52"));fields.put("tnved",List.of("6204430000"));fields.put("title",List.of("Fixture"));fields.put("brand",List.of("Brand"));fields.put("@catalog_id",List.of("91"));fields.put("@catalog_status",List.of(status));
        var details=new com.vncode.app.features.catalogsync.CatalogProductData(fields,List.of(),List.of());var source=new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(GtinWriteTestFixture.GTIN,"fixture-offer","Blue","52",true,status.equals("published"),com.vncode.app.features.gtinsync.RegisteredGtinProof.source(shop),Instant.now(),details);
        snapshot=new ProductSnapshot(key,"live:existing","Fixture","","Brand","fixture-offer","1:2",List.of(),List.of(new ProductAttribute("fixture","Fixture",List.of("Fixture"),null),new ProductAttribute("tnved","ТН ВЭД",List.of("6204430000"),null)),List.of(new ProductVariant(key.variantId(),key.variantId(),"Blue","52","",false,"",List.of(GtinWriteTestFixture.GTIN),ProductVariant.GtinEvidence.UNVERIFIED)),null,base.documents(),Instant.now(),base.metadata());
        draft=new Draft("6204430000","6204",1,"Fixture","Brand",Map.of(1L,"Fixture",13914L,"fixture-offer",36L,"Blue",35L,"52",13933L,"6204430000",ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID,"D:::2025-01-01"),Map.of());
        api.preExisting=ZnackNationalCatalogService.buildPayload(GtinWriteTestFixture.GTIN,draft,"");api.preExisting.addProperty("good_id",91);api.preExisting.addProperty("good_status",status);api.preExisting.addProperty("owner_inn",session().scope().inn());var states=new JsonArray();states.add(status.equals("published")?"published":"notsigned");api.preExisting.add("good_detailed_status",states);
        workflow=new OzonCardRegistrationWorkflow(repository,s->session(),(s,k)->snapshot,Runnable::run,(s,k,sku,gtin)->onMarketplace,()->new RegistrationExistingCards.LookupContext((s,c)->List.of(source),(c,g,id)->c.catalog().observedCardStatus(c.token(),g,id),(s,c)->com.vncode.app.features.gtinsync.RegisteredGtinProof.source(s)));
    }
    private void installUnsubmittedDraft(){installExistingCard("draft");var states=new JsonArray();states.add("draft");api.preExisting.add("good_detailed_status",states);}
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
        var batchWorkflow=new OzonCardRegistrationWorkflow(repository,s->{sessions.incrementAndGet();return session();},(s,k)->{api.activeKey=k;return snapshots.get(k);},Runnable::run,(s,k,sku,gtin)->false,this::emptyLookup);
        var review=batchWorkflow.prepareBatch(shop,List.of(key,second),false,n->{});
        assertEquals(2,review.ready().size());assertEquals(1,sessions.get());assertEquals(0,api.allocations.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(2,?,?,?,'Fixture',?)")){
            for(var k:List.of(key,second)){p.setString(1,k.cardId());p.setString(2,k.variantId());p.setString(3,k.equals(key)?"9001":"9002");p.setString(4,Instant.now().toString());p.addBatch();}p.executeBatch();
        }
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);var keys=List.of(key,second);
        var approval=reviewedBatch(batchWorkflow,keys);var queue=new OzonRegistrationBatchRepository();var batch=new OzonRegistrationBatchService(queue,repository,batchWorkflow);
        batch.start(shop,keys,false,approval);awaitSubmitted(queue);
        assertEquals(2,api.allocations.get());assertEquals(2,api.submissions.get());assertNotNull(repository.find(second));assertNotNull(repository.find(key));
    }
    @Test void durableBatchResumesFrozenSelectionAndCountsOnlyActualCatalogReceipts()throws Exception {
        api.knownQuota=true;api.pendingModeration=true;
        var second=new ProductKey(2,Marketplace.OZON,"502","fixture-offer-2");var base=snapshot;var snapshots=new HashMap<ProductKey,ProductSnapshot>();snapshots.put(key,base);
        snapshots.put(second,new ProductSnapshot(second,"live:second","Second","",base.brand(),second.variantId(),base.categoryId(),base.media(),base.attributes(),List.of(new ProductVariant(second.variantId(),second.variantId(),"","","",false)),base.dimensions(),base.documents(),Instant.now(),Map.of("sku","9002","owner",com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop))));
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(2,?,?,?,'Fixture',?)")){
            for(var k:List.of(key,second)){p.setString(1,k.cardId());p.setString(2,k.variantId());p.setString(3,k.equals(key)?"9001":"9002");p.setString(4,Instant.now().toString());p.addBatch();}p.executeBatch();
        }
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);
        var keys=List.of(key,second);var queue=new OzonRegistrationBatchRepository();String report=OzonRegistrationAttemptReports.queue(shop,keys);
        var batchWorkflow=new OzonCardRegistrationWorkflow(repository,s->session(),(s,k)->{api.activeKey=k;return snapshots.get(k);},Runnable::run,(s,k,sku,gtin)->false,this::emptyLookup);
        queue.create(shop,keys,false,session().scope(),report,reviewedBatch(batchWorkflow,keys));
        assertEquals(0,queue.counts(report).submitted());assertEquals(2,queue.counts(report).remaining());
        var restarted=new OzonRegistrationBatchService(new OzonRegistrationBatchRepository(),repository,batchWorkflow);
        assertTrue(restarted.resumePending(shop));
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<until&&queue.counts(report).remaining()>0)Thread.sleep(10);
        while(System.nanoTime()<until&&OzonRegistrationBatchService.hasPendingJobs())Thread.sleep(10);
        var progress=restarted.latest(shop);assertEquals(2,progress.submitted());assertEquals(0,progress.failed());assertEquals(0,progress.remaining());assertEquals(OzonRegistrationBatchService.State.COMPLETED,progress.state());assertEquals(second.variantId(),progress.lastSubmittedArticle());
        assertEquals(2,api.submissions.get());assertEquals(2,api.allocations.get());assertEquals(2,api.polls.get());assertEquals(Status.PROCESSING,repository.find(key).status());assertEquals(Status.PROCESSING,repository.find(second).status());
        var reports=new com.vncode.app.features.sync.SyncRunRepository();assertEquals(2,reports.find(report).orElseThrow().total());assertEquals(2L,reports.counts(report).get("WAITING_REMOTE"));
        assertFalse(new OzonRegistrationBatchService(queue,repository,batchWorkflow).resumePending(shop));assertEquals(2,api.submissions.get());
    }
    @Test void delayedFirstImportDoesNotBlockOtherSelectedCardsAndRetriesOnlyReadback()throws Exception {
        api.knownQuota=true;api.pendingModeration=true;api.missingGtins.add(GtinWriteTestFixture.GTIN);
        var second=new ProductKey(2,Marketplace.OZON,"502","fixture-offer-2");var base=snapshot;
        var snapshots=Map.of(key,base,second,new ProductSnapshot(second,"live:second","Second","",base.brand(),second.variantId(),base.categoryId(),base.media(),base.attributes(),List.of(new ProductVariant(second.variantId(),second.variantId(),"","","",false)),base.dimensions(),base.documents(),Instant.now(),Map.of("sku","9002","owner",com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop))));
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(2,?,?,?,'Fixture',?)")){
            for(var k:List.of(key,second)){p.setString(1,k.cardId());p.setString(2,k.variantId());p.setString(3,k.equals(key)?"9001":"9002");p.setString(4,Instant.now().toString());p.addBatch();}p.executeBatch();
        }
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);
        var worker=new OzonCardRegistrationWorkflow(repository,s->session(),(s,k)->{api.activeKey=k;return snapshots.get(k);},Runnable::run,(s,k,sku,gtin)->false,this::emptyLookup);
        var queue=new OzonRegistrationBatchRepository();var keys=List.of(key,second);String run=OzonRegistrationAttemptReports.queue(shop,keys);queue.create(shop,keys,false,session().scope(),run,reviewedBatch(worker,keys));
        var batch=new OzonRegistrationBatchService(queue,repository,worker);
        try{
            assertTrue(batch.resumePending(shop));long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<until&&!queue.latest(shop).state().equals("WAITING_REMOTE"))Thread.sleep(10);
            assertEquals("WAITING_REMOTE",queue.latest(shop).state());assertEquals(1,queue.counts(run).submitted());assertEquals(1,queue.counts(run).remaining());assertEquals(2,queue.counts(run).acceptedFeeds());assertEquals(1,queue.counts(run).awaitingReadback());
            assertEquals(2,api.submissions.get(),"A missing card must not block the later selected product");assertEquals(2,api.allocations.get());
            assertEquals(Status.FEED_SUBMITTED,repository.find(key).status());assertTrue(OzonCardRegistrationWorkflow.verifiedSubmission(repository.find(second)));
            String firstFeed=repository.find(key).feedId();api.missingGtins.clear();
            until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<until&&!queue.latest(shop).state().equals("COMPLETED"))Thread.sleep(20);
            assertEquals("COMPLETED",queue.latest(shop).state());assertEquals(2,queue.counts(run).submitted());assertEquals(0,queue.counts(run).remaining());
            assertEquals(firstFeed,repository.find(key).feedId());assertEquals(2,api.submissions.get());assertEquals(2,api.allocations.get());assertEquals(0,api.signatures.get());
        }finally{queue.state(run,"FAILED","fixture complete");batch.resumePending(shop);}
    }
    @Test void newlyAcceptedFeedWithUnconfirmedIdentityFailsTheBatchWithoutResending()throws Exception {
        api.invalidFeedIdentity="missing";
        var queue=singleCardBatch("PENDING");var batch=new OzonRegistrationBatchService(queue,repository,workflow);
        assertTrue(batch.resumePending(shop));awaitFinished(batch,queue,"CATALOG_FEED_IDENTITY_NOT_CONFIRMED");
        assertFalse(row().feedId().isBlank());assertFalse(row().gtin().isBlank());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());
    }
    @Test void savedReadyCardWithPermanentFeedIdentityFailureCannotCountAsSubmitted()throws Exception {
        ready();String feed=row().feedId(),gtin=row().gtin();assertTrue(OzonCardRegistrationWorkflow.verifiedSubmission(row()));
        api.invalidFeedIdentity="missing";
        var queue=singleCardBatch("PENDING");var batch=new OzonRegistrationBatchService(queue,repository,workflow);
        assertTrue(batch.resumePending(shop));awaitFinished(batch,queue,"CATALOG_FEED_IDENTITY_NOT_CONFIRMED");
        assertEquals(feed,row().feedId());assertEquals(gtin,row().gtin());assertEquals(Status.READY_TO_SIGN,row().status());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());
    }
    @Test void waitingPublishedCardWithChangedFeedIdentityFailsWithoutAnotherReadbackLoop()throws Exception {
        published();String feed=row().feedId(),gtin=row().gtin();api.invalidFeedIdentity="changed";
        var queue=singleCardBatch("WAITING_REMOTE");var batch=new OzonRegistrationBatchService(queue,repository,workflow);
        assertTrue(batch.resumePending(shop));awaitFinished(batch,queue,"CATALOG_IDENTITY_CHANGED");
        assertEquals(feed,row().feedId());assertEquals(gtin,row().gtin());assertEquals(Status.PUBLISHED,row().status());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(1,api.signatures.get());
    }
    @Test void legacyFeedWithoutSourceConsentReadsRejectionButCannotRepairSignOrWriteMarketplace()throws Exception {
        ready();String feed=row().feedId(),gtin=row().gtin();int reads=api.polls.get();
        api.rejectionEvidence=true;api.rejectedFeed=feed;api.rejectionMessage="Карточка должна быть создана с типом Комплект";
        var queue=singleCardBatch("PENDING");var batch=new OzonRegistrationBatchService(queue,repository,workflow);
        assertTrue(batch.resumePending(shop));awaitFinished(batch,queue,"CATALOG_REJECTED");
        assertTrue(api.polls.get()>reads,"Read the persisted feed instead of failing before the GET");
        assertEquals(Status.ERROR,row().status());assertEquals(feed,row().feedId());assertEquals(gtin,row().gtin());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());
        assertTrue(row().activeGtin().isBlank(),"Readback cannot activate an Ozon GTIN");
    }
    @Test void legacyFeed404RemainsPendingAndDoesNotCauseAnotherWrite()throws Exception {
        ready();String feed=row().feedId(),gtin=row().gtin();api.readbackStatus=404;
        var queue=singleCardBatch("PENDING");var batch=new OzonRegistrationBatchService(queue,repository,workflow);String run=queue.latest(shop).id();
        try{
            assertTrue(batch.resumePending(shop));long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<until&&!queue.latest(shop).state().equals("WAITING_REMOTE"))Thread.sleep(10);
            assertEquals("WAITING_REMOTE",queue.latest(shop).state());assertEquals(1,queue.counts(run).remaining());assertEquals(0,queue.counts(run).failed());
            assertEquals(Status.FEED_SUBMITTED,row().status());assertEquals("CATALOG_READBACK_RETRY_PENDING",row().error());
            assertEquals(feed,row().feedId());assertEquals(gtin,row().gtin());assertTrue(row().activeGtin().isBlank());
            assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());
        }finally{batch.cancel(shop);}
    }
    @Test void consentForAnotherVariantCannotBeTreatedAsAnUnreviewedLegacyJob()throws Exception {
        ready();var queue=singleCardBatch("PENDING");String run=queue.latest(shop).id();
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_source_consents SET run_id=?,product_id='999'")){p.setString(1,run);assertEquals(1,p.executeUpdate());}
        try(var c=Database.getConnection()){
            assertThrows(RegistrationValidationException.class,()->RegistrationSourceConsentGuard.hasPositionOrLegacy(c,new RegistrationIntent(run,0),key));
        }
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());assertTrue(row().activeGtin().isBlank());
    }
    private OzonRegistrationBatchRepository singleCardBatch(String state)throws Exception {
        return singleCardBatch(state,false);
    }
    private OzonRegistrationBatchRepository singleCardBatch(String state,boolean allowRepair)throws Exception {
        api.knownQuota=true;
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(2,?,?,?,'Fixture',?)")){
            p.setString(1,key.cardId());p.setString(2,key.variantId());p.setString(3,"9001");p.setString(4,Instant.now().toString());p.executeUpdate();
        }
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);
        var queue=new OzonRegistrationBatchRepository();String run=OzonRegistrationAttemptReports.queue(shop,List.of(key));
        var approval=row()==null?reviewedBatch(workflow,List.of(key),allowRepair):null;
        queue.create(shop,List.of(key),false,session().scope(),run,approval);
        if(!state.equals("PENDING"))queue.item(queue.next(queue.latest(shop)),state,"");return queue;
    }
    private void awaitFinished(OzonRegistrationBatchService batch,OzonRegistrationBatchRepository queue,String reason)throws Exception {
        String run=queue.latest(shop).id();long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<until&&(!queue.latest(shop).state().equals("COMPLETED")||OzonRegistrationBatchService.hasPendingJobs()))Thread.sleep(10);
        assertEquals("COMPLETED",queue.latest(shop).state());assertEquals(0,queue.counts(run).submitted());assertEquals(1,queue.counts(run).failed());assertEquals(0,queue.counts(run).remaining());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT state,detail FROM ozon_registration_batch_items WHERE run_id=?")){
            p.setString(1,run);try(var rows=p.executeQuery()){assertTrue(rows.next());assertEquals("FAILED",rows.getString("state"));assertTrue(rows.getString("detail").contains(reason),rows.getString("detail"));}
        }
        assertFalse(batch.resumePending(shop));assertFalse(OzonRegistrationBatchService.hasPendingJobs());
    }
    @Test void monitorLeavesKnownUnsentSubmissionToItsDurableBatchEvenBeforeBatchWorkerStarts()throws Exception {
        var scope=session().scope();assertTrue(repository.claim(shop,key,"9001","",scope,false));assertTrue(repository.transition(key,Status.CHECKING,Status.ALLOCATING_GTIN));
        repository.generated(key,GtinWriteTestFixture.GTIN,ZnackNationalCatalogService.buildPayload(GtinWriteTestFixture.GTIN,draft,"").toString());
        repository.rememberReview(key,RegistrationReviewedValues.productRevision(snapshot),RegistrationReviewedValues.schemaRevision(session().catalog().registrationAttributes(1,"fixture-token")),row().payload());
        var queue=new OzonRegistrationBatchRepository();String run=OzonRegistrationAttemptReports.queue(shop,List.of(key));queue.create(shop,List.of(key),false,scope,run);
        var batch=new OzonRegistrationBatchService(queue,repository,workflow);assertTrue(batch.ownsPending(shop,key));
        // This models startup after dispatch is accepted but before its worker claims the card.
        var monitor=new ZnackRegistrationMonitor(null,workflow,ignored->true,batch::ownsPending);
        var guarded=monitor.poll(shop,3);assertTrue(guarded.pending());assertEquals(Status.READY_TO_SUBMIT,row().status());assertEquals(0,api.submissions.get());assertEquals(0,api.polls.get());
        assertFalse(batch.ownsPending(shop,new ProductKey(shop.getId(),Marketplace.OZON,key.cardId(),"different-offer")));
        queue.item(queue.next(queue.latest(shop)),"FAILED","fixture release");assertFalse(batch.ownsPending(shop,key));
        // Releasing a legacy job from the batch does not grant seller consent to send it.
        monitor.poll(shop,3);assertEquals(0,api.submissions.get());assertEquals(0,api.allocations.get());assertEquals(Status.PRE_SUBMIT_ERROR,row().status());assertTrue(row().error().contains("ZNACK_SOURCE_REVIEW_REQUIRED"));
    }
    @Test void pausedAndCancelledQueuesCannotLeakKnownUnsentPostsThroughTheMonitor()throws Exception{
        var scope=session().scope();assertTrue(repository.claim(shop,key,"9001","",scope,false));assertTrue(repository.transition(key,Status.CHECKING,Status.ALLOCATING_GTIN));repository.generated(key,GtinWriteTestFixture.GTIN,ZnackNationalCatalogService.buildPayload(GtinWriteTestFixture.GTIN,draft,"").toString());
        var queue=new OzonRegistrationBatchRepository();String run=OzonRegistrationAttemptReports.queue(shop,List.of(key));queue.create(shop,List.of(key),true,scope,run);var batch=new OzonRegistrationBatchService(queue,repository,workflow);var monitor=new ZnackRegistrationMonitor(null,workflow,batch::resumePending,batch::ownsPending);
        assertTrue(batch.pause(shop));assertFalse(batch.resumePending(shop));monitor.poll(shop,3);assertEquals(0,api.submissions.get());assertEquals(Status.READY_TO_SUBMIT,row().status());assertTrue(queue.latest(shop).autoSign());
        assertTrue(batch.cancel(shop));monitor.poll(shop,3);assertEquals(0,api.submissions.get());assertEquals(1,queue.counts(run).cancelled());assertEquals("CANCELLED",queue.latest(shop).state());assertFalse(queue.begin(new OzonRegistrationBatchRepository.Item(run,0,key,"PENDING","")));assertTrue(batch.ownsPending(shop,key));
    }
    @Test void pausingKeepsAcceptedCardsReadableButStopsAutomaticSigning()throws Exception{
        ready();RegistrationSigningConsent.set(shop,key,row().gtin(),true);var queue=new OzonRegistrationBatchRepository();String run=OzonRegistrationAttemptReports.queue(shop,List.of(key));queue.create(shop,List.of(key),true,session().scope(),run);queue.item(queue.next(queue.latest(shop)),"SUBMITTED","");var batch=new OzonRegistrationBatchService(queue,repository,workflow);assertTrue(batch.pause(shop));
        var monitor=new ZnackRegistrationMonitor(null,workflow,batch::resumePending,batch::ownsPending);monitor.poll(shop,3);assertEquals(0,api.signatures.get());api.accepted=true;monitor.poll(shop,3);assertEquals(Status.PUBLISHED,row().status());assertEquals(0,api.signatures.get());assertEquals("PAUSED",queue.latest(shop).state());
    }
    @Test void anOldCancelledBatchDoesNotBlockSigningForANewApprovedSelection(){
        var queue=new OzonRegistrationBatchRepository();var scope=session().scope();String cancelled=queue.create(shop,List.of(key),true,scope);assertTrue(queue.control(shop,"CANCELLED"));assertTrue(queue.blocksSigning(shop,key));
        String approved=queue.create(shop,List.of(key),true,scope);assertNotEquals(cancelled,approved);assertFalse(queue.blocksSigning(shop,key));assertTrue(queue.ownsPending(shop,key));assertEquals("QUEUED",queue.latest(shop).state());
    }
    @Test void repeatedFailuresKeepTheirRetryDeadlineAndFrozenSelectionAfterReopening(){
        var queue=new OzonRegistrationBatchRepository();String run=OzonRegistrationAttemptReports.queue(shop,List.of(key));queue.create(shop,List.of(key),true,session().scope(),run);
        for(int attempt=1;attempt<=12;attempt++)assertTrue(queue.defer(run,"NETWORK_TIMEOUT",3600,true));
        var reopened=new OzonRegistrationBatchRepository();assertEquals("WAITING",reopened.latest(shop).state());assertEquals(12,reopened.latest(shop).retryCount());assertNotNull(reopened.latest(shop).nextRetryAt());assertTrue(reopened.latest(shop).nextRetryAt().isAfter(java.time.Instant.now().plusSeconds(3599)));assertEquals(1,reopened.counts(run).remaining());assertTrue(reopened.latest(shop).autoSign());
        var blocked=assertThrows(IllegalStateException.class,()->reopened.create(shop,List.of(new ProductKey(2,Marketplace.OZON,"502","other")),false,session().scope()));assertEquals("ZNACK_REGISTRATION_BATCH_ALREADY_RUNNING",blocked.getMessage());assertEquals(run,reopened.latest(shop).id());
        assertTrue(reopened.control(shop,"PAUSED"));assertFalse(reopened.recoverRetryPause(shop));
        assertTrue(reopened.control(shop,"QUEUED"));assertEquals(0,reopened.latest(shop).retryCount());assertEquals(key,reopened.next(reopened.latest(shop)).key());assertTrue(reopened.latest(shop).autoSign());
    }
    @Test void futureRetryDeadlineIsHonoredAndCancellationReleasesTheScheduledGuard(){
        var queue=new OzonRegistrationBatchRepository();String run=OzonRegistrationAttemptReports.queue(shop,List.of(key));queue.create(shop,List.of(key),false,session().scope(),run);assertTrue(queue.defer(run,"ZNACK_RATE_LIMIT",3600,true));var batch=new OzonRegistrationBatchService(queue,repository,workflow);
        assertTrue(batch.resumePending(shop));assertTrue(OzonRegistrationBatchService.hasPendingJobs());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertTrue(batch.cancel(shop));assertFalse(OzonRegistrationBatchService.hasPendingJobs());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    @Test void anOldAutomaticRetryPauseIsRecoveredWithoutChangingTheAcceptedCard()throws Exception{
        ready();String gtin=row().gtin(),feed=row().feedId();int sends=api.submissions.get(),allocations=api.allocations.get();
        var queue=singleCardBatch("WAITING_REMOTE");String run=queue.latest(shop).id();
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_registration_batches SET state='PAUSED',detail='REGISTRATION_BATCH_RETRY_LIMIT: CATALOG_READBACK_RETRY_PENDING',retry_count=5,next_retry_at=NULL WHERE run_id=?")){
            p.setString(1,run);p.executeUpdate();
        }
        var batch=new OzonRegistrationBatchService(queue,repository,workflow);
        assertTrue(batch.resumePending(shop));assertEquals("WAITING_REMOTE",queue.latest(shop).state());assertNotNull(queue.latest(shop).nextRetryAt());
        assertEquals(1,queue.counts(run).remaining());assertEquals(gtin,row().gtin());assertEquals(feed,row().feedId());assertEquals(sends,api.submissions.get());assertEquals(allocations,api.allocations.get());
        assertTrue(batch.pause(shop));assertFalse(queue.recoverRetryPause(shop));assertFalse(batch.resumePending(shop));assertTrue(batch.cancel(shop));
    }
    @Test void aChangedApprovedSourceCannotResumeTheOldKnownUnsentPayload()throws Exception{
        api.feedTimeout=false;var prior=LicenseRuntime.service();RegistrationIntent intent;try(var denied=LicenseDomainFixture.denied(root.resolve("source-review-license"))){
            try{intent=reviewedStart(workflow.prepare(shop,key),draft,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null),(status,detail)->{if(status==Status.GTIN_GENERATED)LicenseRuntime.install(denied);});}finally{LicenseRuntime.install(prior);}
        }
        assertEquals(Status.READY_TO_SUBMIT,row().status());String payload=row().payload(),gtin=row().gtin();var base=snapshot;snapshot=new ProductSnapshot(base.key(),"live:changed","Changed title",base.description(),base.brand(),base.vendorArticle(),base.categoryId(),base.media(),base.attributes(),base.variants(),base.dimensions(),base.documents(),base.checkedAt(),base.metadata());
        assertTrue(workflow.resumeForBatch(shop,key,false,null,intent));assertEquals(0,api.submissions.get());assertEquals(1,api.allocations.get());assertEquals(gtin,row().gtin());assertEquals(payload,row().payload());assertEquals(Status.PRE_SUBMIT_ERROR,row().status());
    }
    @Test void unchangedConfiguredShopBrandCanResumeTheSameReviewedPayload()throws Exception{
        new com.vncode.app.integration.ozon.OzonShopBrand().save(shop,"Configured Brand");var base=snapshot;snapshot=new ProductSnapshot(base.key(),base.revision(),base.title(),base.description(),"",base.vendorArticle(),base.categoryId(),base.media(),base.attributes(),base.variants(),base.dimensions(),base.documents(),base.checkedAt(),base.metadata());
        var approved=new Draft(draft.tnved(),draft.feedTnved(),draft.categoryId(),draft.goodName(),"Configured Brand",draft.attributes(),draft.attributeTypes());var prior=LicenseRuntime.service();
        RegistrationIntent intent;try(var denied=LicenseDomainFixture.denied(root.resolve("configured-brand-license"))){try{intent=reviewedStart(workflow.prepare(shop,key),approved,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null),(status,detail)->{if(status==Status.GTIN_GENERATED)LicenseRuntime.install(denied);});}finally{LicenseRuntime.install(prior);}}
        assertEquals(Status.READY_TO_SUBMIT,row().status());String payload=row().payload(),gtin=row().gtin();assertTrue(workflow.resumeForBatch(shop,key,false,null,intent));assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(gtin,row().gtin());assertEquals(payload,row().payload());assertEquals("Configured Brand",api.lastPayload.get("brand").getAsString());
    }
    @Test void batchSubmissionVerifiesOneCardWithoutWaitingForModerationOrSigning()throws Exception {
        api.pendingModeration=true;var prepared=workflow.prepare(shop,key);
        var intent=reviewedStart(prepared,draft.withAutoSign(true));
        assertEquals(Status.PROCESSING,row().status());assertFalse(row().feedId().isBlank());assertEquals(1,api.submissions.get());assertEquals(1,api.polls.get());assertEquals(0,api.signatures.get());
        assertEquals("CATALOG_CARD_IN_MODERATION",row().error());assertTrue(OzonCardRegistrationWorkflow.verifiedSubmission(row()));
        assertTrue(workflow.resumeForBatch(shop,key,true,null,intent));assertEquals(1,api.submissions.get());assertEquals(2,api.polls.get());assertEquals(0,api.signatures.get());
    }
    @Test void feedReceiptWithoutCardStaysPendingThenLateCardIsReadWithoutAnotherPost()throws Exception {
        api.pendingModeration=true;api.cardMissing=true;
        var prepared=workflow.prepare(shop,key);
        reviewedStart(prepared,draft.withAutoSign(true));
        assertEquals(Status.FEED_SUBMITTED,row().status());assertNull(row().goodId());
        assertEquals("CATALOG_REQUEST_ACCEPTED_CARD_PENDING",row().error());
        assertFalse(OzonCardRegistrationWorkflow.verifiedSubmission(row()));
        String feed=row().feedId(),gtin=row().gtin();
        api.cardMissing=false;assertTrue(workflow.verifySubmissionForBatch(shop,key));
        assertTrue(OzonCardRegistrationWorkflow.verifiedSubmission(row()));assertEquals(Status.PROCESSING,row().status());
        assertEquals(feed,row().feedId());assertEquals(gtin,row().gtin());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());
    }
    @Test void unavailableReadbackKeepsReceiptAndIdentityWithoutResending()throws Exception {
        api.pendingModeration=true;api.readbackFailure=true;
        var prepared=workflow.prepare(shop,key);reviewedStart(prepared,draft);
        assertEquals(Status.FEED_SUBMITTED,row().status());assertEquals("CATALOG_READBACK_RETRY_PENDING",row().error());
        assertFalse(OzonCardRegistrationWorkflow.verifiedSubmission(row()));String feed=row().feedId(),gtin=row().gtin();
        assertTrue(workflow.verifySubmissionForBatch(shop,key));assertEquals(1,api.submissions.get());
        api.readbackFailure=false;assertTrue(workflow.verifySubmissionForBatch(shop,key));
        assertEquals(feed,row().feedId());assertEquals(gtin,row().gtin());assertEquals(91L,row().goodId());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());
    }
    @Test void rateLimitedReadbackRetainsSavedReceiptAndDoesNotRepeatPaidAllocationOrFeed()throws Exception {
        api.pendingModeration=true;api.readbackStatus=429;
        var prepared=workflow.prepare(shop,key);reviewedStart(prepared,draft);
        assertEquals(Status.FEED_SUBMITTED,row().status());assertEquals("CATALOG_READBACK_RETRY_PENDING",row().error());
        String feed=row().feedId(),gtin=row().gtin();api.readbackStatus=0;
        assertTrue(workflow.verifySubmissionForBatch(shop,key));assertTrue(OzonCardRegistrationWorkflow.verifiedSubmission(row()));
        assertEquals(feed,row().feedId());assertEquals(gtin,row().gtin());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void failedAndErrorFeedWithoutDetailsAreReportedInsteadOfWaitingForModeration()throws Exception {
        api.emptyDetailFailure="Failed";
        var prepared=workflow.prepare(shop,key);reviewedStart(prepared,draft);
        assertEquals(Status.ERROR,row().status());assertFalse(OzonCardRegistrationWorkflow.verifiedSubmission(row()));
        assertTrue(row().error().contains("CATALOG_REJECTED"));
        api.emptyDetailFailure="Error";assertTrue(workflow.verifySubmissionForBatch(shop,key));
        assertEquals(Status.ERROR,row().status());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
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
        assertFalse(workflow.signApproved(shop,List.of(key),true,null));assertTrue(sign());assertEquals(Status.PUBLISHED,row().status());assertEquals(1,api.signatures.get());
        assertTrue(new GtinSyncRepository().catalog(2,Marketplace.OZON).stream().anyMatch(g->g.gtin().equals(row().gtin())&&g.source().matches("National Catalog@[a-f0-9]{64}")));
    }
    @Test void uncertainAllocationAndFeedArePersistedAndNotRepeated() {
        api.allocationTimeout=true;reviewedStart(draft);assertEquals(Status.GTIN_REVIEW_REQUIRED,row().status());
        assertFalse(workflow.resume(shop,key,null));assertTrue(workflow.register(shop,key,draft,true,false,null));assertEquals(1,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    @Test void lostFeedResultKeepsGtinAndRequiresReadbackReview() {
        api.feedTimeout=true;reviewedStart(draft);assertEquals(Status.FEED_REVIEW_REQUIRED,row().status());assertEquals(GtinWriteTestFixture.GTIN,row().gtin());
        assertFalse(workflow.resume(shop,key,null));assertTrue(workflow.register(shop,key,draft,true,false,null));assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void ambiguousSignatureOnlyReadsBackAndNeverSignsTwice() {
        ready();api.signTimeout=true;assertTrue(sign());assertEquals(Status.SIGNING,row().status());assertEquals("RECONCILE_REQUIRED",row().signatureState());
        assertFalse(sign());assertEquals(1,api.signatures.get());assertEquals(Status.SIGNING,row().status());
        api.accepted=true;assertTrue(workflow.resume(shop,key,null));assertEquals(Status.PUBLISHED,row().status());assertEquals(1,api.signatures.get());assertEquals("CONFIRMED",row().signatureState());
    }
    @Test void changedSigningXmlIsKnownUnsentAndLeavesReviewedRetry() {
        ready();api.changeSigningDocument=true;assertTrue(sign());
        assertEquals(0,api.signatures.get());assertEquals(Status.READY_TO_SIGN,row().status());assertEquals("",row().signatureState(),"Changed XML must stop before any new reviewed document is claimed");
    }
    @Test void firstPublishedCardActivatesOnlyAfterMarketplaceReadback() {
        published();assertTrue(workflow.complete(shop,key,true,null));assertEquals("",row().activeGtin());
        var mappings=new OzonProductGtinMappingRepository();assertNull(mappings.findGtin(2,"9001"));
        onMarketplace=true;assertTrue(workflow.complete(shop,key,true,null));assertEquals(row().gtin(),row().activeGtin());assertEquals(row().gtin(),mappings.findGtin(2,"9001"));
    }
    @Test void legacyReplacementCannotSwitchPublishedOzonMappingWithoutReview() {
        published();assertTrue(workflow.complete(shop,key,true,null));assertEquals("",row().activeGtin());
        assertNull(new OzonProductGtinMappingRepository().findGtin(2,"9001"));onMarketplace=true;
        assertTrue(workflow.complete(shop,key,true,null));String old=row().gtin();assertEquals(old,row().activeGtin());assertEquals(old,new OzonProductGtinMappingRepository().findGtin(2,"9001"));
        api.accepted=false;assertFalse(workflow.register(shop,key,draft,true,true,null));assertEquals(old,row().gtin());assertEquals(old,row().activeGtin());assertEquals(old,new OzonProductGtinMappingRepository().findGtin(2,"9001"));
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(1,api.signatures.get());
    }
    @Test void deniedLicenseWhileQueuedHasNoClaimOrApiWrite()throws Exception {
        var queued=new ArrayList<Runnable>();workflow=workflow(queued::add);assertTrue(workflow.register(shop,key,draft,true,false,null));assertTrue(OzonCardRegistrationWorkflow.hasActiveJobs());
        var prior=LicenseRuntime.service();try(var denied=LicenseDomainFixture.denied(root.resolve("denied-license"))){try{LicenseRuntime.install(denied);queued.getFirst().run();assertNull(row());assertEquals(0,api.allocations.get());}finally{LicenseRuntime.install(prior);}}
        assertFalse(OzonCardRegistrationWorkflow.hasActiveJobs());
    }
    @Test void queuedWorkCapturesShopCredentialsInsteadOfFollowingMutableUiObject()throws Exception {
        var queued=new ArrayList<Runnable>();
        workflow=new OzonCardRegistrationWorkflow(repository,s->{sessions.incrementAndGet();return session();},(s,k)->{com.vncode.app.integration.listing.ListingIdentity.require(s,k);return snapshot;},queued::add,(s,k,sku,gtin)->false,this::emptyLookup);
        assertTrue(workflow.register(shop,key,draft,true,false,null));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated-fixture' WHERE id=2");}
        shop.setApiKey("rotated-fixture");queued.getFirst().run();assertNull(row());assertEquals(0,sessions.get());assertEquals(0,api.allocations.get());
    }
    @Test void licenseDenialAfterFeedClaimIsKnownUnsentAndCanResumeExactlyOnce()throws Exception {
        var prior=LicenseRuntime.service();RegistrationIntent intent;try(var denied=LicenseDomainFixture.denied(root.resolve("denied-license"))){
            try{intent=reviewedStart(workflow.prepare(shop,key),draft,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null),(state,detail)->{if(state==Status.FEED_SUBMITTING)LicenseRuntime.install(denied);});assertEquals(Status.READY_TO_SUBMIT,row().status());assertEquals(0,api.submissions.get());}
            finally{LicenseRuntime.install(prior);}
        }
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT phase FROM znack_source_consents WHERE run_id=?")){p.setString(1,intent.runId());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("ALLOCATED",r.getString(1));}}
        assertTrue(row().feedId().isBlank());assertTrue(workflow.resumeForBatch(shop,key,false,null,intent));assertEquals(Status.READY_TO_SIGN,row().status(),row().error());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
        assertFalse(row().feedId().isBlank());try(var c=Database.getConnection();var p=c.prepareStatement("SELECT phase FROM znack_source_consents WHERE run_id=?")){p.setString(1,intent.runId());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("SUBMITTED",r.getString(1));}}
    }
    @Test void preTransportRollbackFailureNeverReleasesOnlyTheNativeCheckpoint()throws Exception {
        var prior=LicenseRuntime.service();RegistrationIntent intent;
        try(var denied=LicenseDomainFixture.denied(root.resolve("rollback-denied-license"))){
            try{intent=reviewedStart(workflow.prepare(shop,key),draft,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null),(state,detail)->{
                if(state==Status.FEED_SUBMITTING){try(var c=Database.getConnection();var s=c.createStatement()){
                    s.execute("CREATE TRIGGER reject_consent_release BEFORE UPDATE ON znack_source_consents WHEN OLD.phase='SUBMITTING' AND NEW.phase<>'SUBMITTING' BEGIN SELECT RAISE(ABORT,'fixture rollback'); END");
                }catch(Exception e){throw new IllegalStateException(e);}LicenseRuntime.install(denied);}
            });}finally{LicenseRuntime.install(prior);}
        }
        assertEquals(0,api.submissions.get());assertTrue(row().feedId().isBlank());assertEquals(Status.FEED_REVIEW_REQUIRED,row().status());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT phase FROM znack_source_consents WHERE run_id=?")){p.setString(1,intent.runId());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("SUBMITTING",r.getString(1));}}
        assertFalse(workflow.resumeForBatch(shop,key,false,null,intent));assertEquals(0,api.submissions.get());
    }
    @Test void settingsChangedAfterGenerationRetainsOriginalScopeAndStopsFeed() throws Exception {
        var intent=reviewedStart(workflow.prepare(shop,key),draft,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null),(state,detail)->{if(state==Status.GTIN_GENERATED)GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);});
        assertEquals(1,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals(Status.READY_TO_SUBMIT,row().status());assertTrue(row().scopeJson().contains("AAAAAAAA"));
        assertTrue(workflow.resumeForBatch(shop,key,false,null,intent));assertEquals(0,api.submissions.get());
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
    @Test void publicationKeepsTheActualCatalogIdentityAndTradeUnitProofForCompletion()throws Exception{
        published();var proof=new GtinSyncRepository().catalogItem(shop.getId(),Marketplace.OZON,row().gtin());assertEquals("91",proof.details().single("@catalog_id"));assertEquals("published",proof.details().single("@catalog_status"));assertTrue(proof.tradeUnit());com.vncode.app.features.gtinsync.RegisteredGtinProof.requireCurrent(shop,row().gtin());
        onMarketplace=true;assertTrue(workflow.complete(shop,key,true,null));assertEquals(row().gtin(),row().activeGtin());
    }
    @Test void publicationWithoutActualTradeUnitEvidenceCannotActivateTheGtin()throws Exception{
        ready();api.unitEvidence=false;assertTrue(sign());assertEquals(Status.PUBLISHED,row().status());var proof=new GtinSyncRepository().catalogItem(shop.getId(),Marketplace.OZON,row().gtin());assertFalse(proof.tradeUnit());assertEquals("91",proof.details().single("@catalog_id"));assertThrows(IllegalArgumentException.class,()->com.vncode.app.features.gtinsync.RegisteredGtinProof.requireCurrent(shop,row().gtin()));onMarketplace=true;assertTrue(workflow.complete(shop,key,true,null));assertEquals("",row().activeGtin());
    }
    final class FixtureApi extends ZnackApiClient {
        String permitScope="6204, 6104, 6112";
        @Override public JsonElement registrationPermitInfo(String base,String token,JsonArray requested){
            var documents=requested.deepCopy();for(var entry:documents){var d=entry.getAsJsonObject();d.addProperty("status_group",1);d.addProperty("product_tnved",permitScope);}
            var result=new JsonObject();result.add("documents",documents);var root=new JsonObject();root.add("result",result);return root;
        }
        AtomicInteger allocations=new AtomicInteger(),submissions=new AtomicInteger(),signatures=new AtomicInteger(),documents=new AtomicInteger(),polls=new AtomicInteger(),countryReads=new AtomicInteger();JsonObject lastPayload,preExisting;
        ProductKey activeKey=key;boolean knownQuota,mandatoryCountry,rejectionEvidence,cleanTerminalFeed,rejectedOldCard;
        String rejectedFeed="";
        String rejectionStatus="Rejected",rejectionMessage="required attribute missing; token=fixture-secret";
        boolean multipleCategories,allocationTimeout,feedTimeout,signTimeout,changeSigningDocument,accepted,rejected,pendingModeration,laggedModeration,cardMissing,readbackFailure;
        final Set<String> missingGtins=java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Map<String,String> feedGtins=new java.util.concurrent.ConcurrentHashMap<>();
        int readbackStatus,feedRateLimits,allocationRateLimits;Duration writeRetryAfter;
        String emptyDetailFailure="";
        volatile String invalidFeedIdentity="";
        boolean unitEvidence=true,forbidQuota;
        String currentGtin=GtinWriteTestFixture.GTIN;
        public JsonElement generatedGtins(String base,String token){if(forbidQuota)throw new AssertionError("An existing draft update does not allocate or require allocation quota");return json(knownQuota?"{\"result\":{\"drafts\":[],\"monthly-limit\":{\"limit\":100,\"usage\":0}}}":"{\"result\":{\"drafts\":[]}}");}
        public JsonElement generateGtins(String base,String token,int quantity)throws IOException{assertEquals(Status.ALLOCATING_GTIN,repository.find(activeKey).status());int count=allocations.incrementAndGet();if(allocationRateLimits>0){allocationRateLimits--;throw new ZnackApiClient.ZnackApiException("fixture allocation rate limit",429,"{}");}if(allocationTimeout)throw new IOException("fixture allocation timeout");currentGtin=count==1?GtinWriteTestFixture.GTIN:"00000000000024";return json("{\"result\":{\"drafts\":[{\"gtin\":\""+currentGtin+"\"}]}}");}
        public JsonElement nationalCatalogCategories(String base,String token,String tnved){if(tnved.length()==10)return json("{\"result\":[]}");return json(multipleCategories?"{\"result\":[{\"cat_id\":1,\"cat_name\":\"Fixture\",\"category_active\":true},{\"cat_id\":2,\"cat_name\":\"Fixture 2\",\"category_active\":true}]}":"{\"result\":[{\"cat_id\":1,\"cat_name\":\"Fixture\",\"category_active\":true}]}");}
        public JsonElement nationalCatalogAttributes(String base,String token,long category){var response=json("{\"result\":[{\"attr_id\":1,\"attr_name\":\"Fixture\",\"attr_field_type\":\"text\"},{\"attr_id\":23557,\"attr_name\":\"Declaration\",\"attr_field_type\":\"text\"},{\"attr_id\":23561,\"attr_name\":\"Certificate\",\"attr_field_type\":\"text\"}]}").getAsJsonObject();if(mandatoryCountry)response.getAsJsonArray("result").add(json("{\"attr_id\":2630,\"attr_name\":\"Страна производства\",\"attr_field_type\":\"text\",\"attr_preset_only\":true,\"attr_preset\":[]}"));return response;}
        public JsonElement nationalCatalogAllAttributes(String base,String token,long category){var response=nationalCatalogAttributes(base,token,category).getAsJsonObject();var all=response.getAsJsonArray("result");for(String field:List.of("{\"attr_id\":35,\"attr_name\":\"Размер\",\"attr_field_type\":\"text\"}","{\"attr_id\":36,\"attr_name\":\"Цвет\",\"attr_field_type\":\"text\"}","{\"attr_id\":13914,\"attr_name\":\"Артикул\",\"attr_field_type\":\"text\"}"))all.add(json(field));return response;}
        public JsonElement nationalCatalogCountries(String base,String token){countryReads.incrementAndGet();return json("{\"apiversion\":3,\"result\":{\"_etag\":\"fixture-country-etag\",\"_list\":[{\"country_iso\":\"RU\",\"country_name\":\"Россия\"},{\"country_iso\":\"CN\",\"country_name\":\"Китай\"}]}}");}
        public JsonElement submitNationalCatalogFeed(String base,String token,JsonElement body)throws IOException{assertEquals(Status.FEED_SUBMITTING,repository.find(activeKey).status());lastPayload=body.getAsJsonObject().deepCopy();assertFalse(lastPayload.has("_vncode_reviewed_draft"));if(preExisting!=null&&lastPayload.has("moderation")){preExisting=lastPayload.deepCopy();preExisting.addProperty("good_status","draft");var state=new JsonArray();state.add(pendingModeration?"moderation":"notsigned");preExisting.add("good_detailed_status",state);}submissions.incrementAndGet();if(feedRateLimits>0){feedRateLimits--;throw new ZnackApiClient.ZnackApiException("fixture feed rate limit",429,"{}","POST","https://fixture.invalid/v3/feed",writeRetryAfter);}if(feedTimeout)throw new IOException("fixture feed timeout");String feed="feed-"+submissions.get();feedGtins.put(feed,lastPayload.get("gtin").getAsString());return json("{\"result\":{\"feed_id\":\""+feed+"\"}}");}
        public JsonElement productCards(String base,String token,String gtins)throws IOException {
            if(readbackStatus>0)throw new ZnackApiClient.ZnackApiException("fixture readback deferred",readbackStatus,"{}");
            if(readbackFailure)throw new IOException("fixture readback offline");
            if(cardMissing||missingGtins.contains(gtins))return json("{\"result\":[]}");
            if(preExisting!=null){var card=preExisting.deepCopy();if(accepted){card.addProperty("good_status","published");var state=new JsonArray();state.add("published");card.add("good_detailed_status",state);}unit(card,gtins);var cards=new JsonArray();cards.add(card);var response=new JsonObject();response.add("result",cards);return response;}
            boolean published=accepted||!gtins.equals(currentGtin);
            var card=lastPayload==null?new JsonObject():lastPayload.deepCopy();card.addProperty("gtin",gtins);card.addProperty("good_id",91);if(!card.has("tnved"))card.addProperty("tnved","6204");card.addProperty("good_status",published?"published":"draft");var status=new JsonArray();boolean rejectedCard=rejectionEvidence&&!published&&(rejected||("feed-"+submissions.get()).equals(rejectedFeed)||(rejectedOldCard&&gtins.equals(feedGtins.get("old-feed"))));status.add(published?"published":rejectedCard?"errors":pendingModeration?"moderation":"notsigned");if(rejectedCard)card.addProperty("owner_inn",session().scope().inn());card.add("good_detailed_status",status);unit(card,gtins);var cards=new JsonArray();cards.add(card);var response=new JsonObject();response.add("result",cards);return response;
        }
        private void unit(JsonObject card,String gtin){if(!unitEvidence){card.remove("identified_by");return;}var values=new JsonArray();var value=new JsonObject();value.addProperty("type","gtin");value.addProperty("value",gtin);value.addProperty("level","trade-unit");values.add(value);card.add("identified_by",values);}
        public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){polls.incrementAndGet();String feedGtin=feedGtins.getOrDefault(feed,currentGtin);
            if(cleanTerminalFeed)return json("{\"result\":{\"feed_id\":\""+feed+"\",\"status\":\"Processed\",\"item\":[{\"gtin\":\""+feedGtin+"\",\"good_id\":91}]}}");
            if(invalidFeedIdentity.equals("missing"))return json("{\"result\":{\"status\":\"Processing\",\"item\":[{\"gtin\":\"00000000000024\",\"good_id\":91}]}}");
            if(invalidFeedIdentity.equals("changed"))return json("{\"result\":{\"status\":\"Processing\",\"item\":[{\"gtin\":\""+currentGtin+"\",\"good_id\":91},{\"gtin\":\""+currentGtin+"\",\"good_id\":92}]}}");
            if(!emptyDetailFailure.isBlank())return json("{\"result\":{\"status\":\""+emptyDetailFailure+"\",\"item\":[]}}");if(rejected||feed.equals(rejectedFeed))return json("{\"result\":{\"feed_id\":\""+feed+"\",\"status\":\""+rejectionStatus+"\",\"item\":[{\"gtin\":\""+feedGtin+"\","+(rejectionEvidence?"\"good_id\":91,":"")+"\"status_code\":400,\"message\":\""+rejectionMessage+"\"}]}}");return json("{\"result\":{\"status\":\""+(pendingModeration?"Processing":accepted&&!laggedModeration?"Signed":"Moderated")+"\",\"item\":[{\"gtin\":\""+feedGtin+"\",\"good_id\":91}]}}");}
        public JsonElement nationalCatalogSigningDocument(String base,String token,JsonObject body){int count=documents.incrementAndGet();return json("{\"result\":{\"xmls\":[{\"goodId\":91,\"xml\":\"<good>"+(changeSigningDocument&&count>1?"changed":"reviewed")+"</good>\"}]}}");}
        public JsonElement signNationalCatalogProduct(String base,String token,JsonArray body)throws IOException{assertEquals(Status.SIGNING,repository.find(activeKey).status());assertEquals("SENDING",repository.find(activeKey).signatureState());assertEquals("<good>reviewed</good>",new String(Base64.getDecoder().decode(body.get(0).getAsJsonObject().get("base64Xml").getAsString()),java.nio.charset.StandardCharsets.UTF_8));signatures.incrementAndGet();if(signTimeout)throw new IOException("fixture signing timeout");accepted=true;return json("{\"result\":{\"signed\":[91]}}");}
        JsonElement json(String value){return JsonParser.parseString(value);}
    }
}
