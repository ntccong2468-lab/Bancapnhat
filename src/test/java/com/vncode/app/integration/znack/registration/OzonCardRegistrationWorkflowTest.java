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
    OzonCardRegistrationRepository.Row row(){return repository.find(key);}
    void ready(){assertTrue(workflow.register(shop,key,draft,true,false,null));assertEquals(Status.READY_TO_SIGN,row().status());}
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
        assertTrue(workflow.registerReviewed(shop,prepared,draft,true,false,null));
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(Status.READY_TO_SIGN,row().status());
    }
    @Test void mandatoryExternalCountryDictionaryIsEnrichedForPlanningAndSubmitValidation()throws Exception{
        api.mandatoryCountry=true;api.knownQuota=true;
        var prepared=workflow.prepare(shop,key);var automatic=OzonCardRegistrationWorkflow.suggestedDraft(prepared).orElseThrow();
        assertEquals("RU",automatic.attributes().get(2630L));assertEquals(List.of("RU","CN"),prepared.required().stream().filter(attribute->attribute.id()==2630L).findFirst().orElseThrow().presets());
        assertTrue(workflow.registerReviewed(shop,prepared,automatic,true,false,null));assertEquals(Status.READY_TO_SIGN,row().status());
        assertTrue(api.lastPayload.getAsJsonArray("good_attrs").asList().stream().map(JsonElement::getAsJsonObject).anyMatch(attribute->attribute.get("attr_id").getAsLong()==2630L&&attribute.get("attr_value").getAsString().equals("RU")));
        assertEquals(2,api.countryReads.get(),"The no-store dictionary is pinned for planning and freshly verified before submission");
    }
    @Test void directRegistrationWithMandatoryCountryUsesTheSellerDefaultWithoutRejectingEmptyInlinePresets(){
        api.mandatoryCountry=true;
        assertTrue(workflow.register(shop,key,draft,true,false,null));assertEquals(Status.READY_TO_SIGN,row().status());
        assertTrue(api.lastPayload.getAsJsonArray("good_attrs").asList().stream().map(JsonElement::getAsJsonObject).anyMatch(attribute->attribute.get("attr_id").getAsLong()==2630L&&attribute.get("attr_value").getAsString().equals("RU")));
        assertEquals(1,api.countryReads.get());
    }
    @Test void unannouncedOptionalAttributeStopsBeforeAllocatingOrPosting(){
        var values=new LinkedHashMap<Long,String>(draft.attributes());values.put(999L,"Unannounced");var invalid=new Draft(draft.tnved(),draft.feedTnved(),draft.categoryId(),draft.goodName(),draft.brand(),values,draft.attributeTypes());
        assertTrue(workflow.register(shop,key,invalid,true,false,null));assertNull(row());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertNotNull(workflow.failure(shop));assertTrue(workflow.failure(shop).getMessage().contains("ZNACK_INVALID_ATTRIBUTE_VALUE"));
    }
    @Test void aLegacyUnsentEditAddsTheKnownIdentityWithoutAllocatingAnotherGtin()throws Exception{
        ready();String gtin=row().gtin();
        try(var c=Database.getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE ozon_card_registrations SET status='READY_TO_SUBMIT',feed_id='',good_id=91");}
        assertFalse(JsonParser.parseString(row().payload()).getAsJsonObject().has("good_id"));
        assertTrue(workflow.resume(shop,key,false,null));assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(gtin,row().gtin());
        assertEquals(91L,api.lastPayload.get("good_id").getAsLong());assertEquals(91L,JsonParser.parseString(row().payload()).getAsJsonObject().get("good_id").getAsLong());assertEquals(1,api.allocations.get());assertEquals(2,api.submissions.get());
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
    @Test void changedTnvedGroupNeedsExplicitNewCardApprovalAndArchivesTheOldPendingCard()throws Exception {
        ready();String old=row().gtin();var changed=new Draft("6104",1,"Fixture","Brand",draft.attributes());
        assertTrue(workflow.register(shop,key,changed,true,false,null));assertEquals(old,row().gtin());assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertTrue(row().error().contains("TN VED")||row().error().contains("TNVED"));assertFalse(repository.claim(shop,key,"9001","",session().scope(),false,"6104"));
        assertTrue(workflow.register(shop,key,changed,true,true,null));assertEquals("00000000000024",row().gtin());assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(2,api.allocations.get());assertEquals(2,api.submissions.get());assertEquals("",row().activeGtin());assertTrue(repository.claimedGtins().contains(old));
        try(var c=Database.getConnection();var st=c.createStatement();var r=st.executeQuery("SELECT gtin,feed_id,payload_json FROM ozon_card_registration_history")){assertTrue(r.next());assertEquals(old,r.getString(1));assertEquals("feed-1",r.getString(2));assertTrue(r.getString(3).contains("6204"));}
    }
    @Test void rejectionReasonSurvivesMonitoringAndSecretsAreNotCopiedIntoTheReport()throws Exception {
        ready();api.rejected=true;new ZnackRegistrationMonitor(null,workflow).poll(shop,3);assertEquals(Status.ERROR,row().status());assertTrue(row().error().contains("required attribute missing"));assertFalse(row().error().contains("fixture-secret"));assertEquals(1,api.submissions.get());
    }
    @Test void explicitRetryOfRejectedFeedKeepsItsGtinAndArchivesTheOriginalRequest()throws Exception{
        ready();String gtin=row().gtin(),oldFeed=row().feedId();api.rejectedFeed=oldFeed;new ZnackRegistrationMonitor(null,workflow).poll(shop,3);assertEquals(Status.ERROR,row().status());
        assertTrue(workflow.resume(shop,key,false,null));assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(gtin,row().gtin());assertNotEquals(oldFeed,row().feedId());
        assertEquals(1,api.allocations.get());assertEquals(2,api.submissions.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT feed_id,gtin FROM ozon_card_registration_history WHERE shop_id=2" );var r=p.executeQuery()){
            assertTrue(r.next());assertEquals(oldFeed,r.getString(1));assertEquals(gtin,r.getString(2));assertFalse(r.next());
        }
    }
    @Test void sellerCanChooseOneNewGtinAfterConfirmedRejectionAndRetainTheOldRequest()throws Exception{
        ready();String old=row().gtin(),feed=row().feedId();api.rejectedFeed=feed;new ZnackRegistrationMonitor(null,workflow).poll(shop,3);
        assertTrue(workflow.register(shop,key,draft,true,true,null));assertNotEquals(old,row().gtin());assertEquals(Status.READY_TO_SIGN,row().status());
        assertEquals(2,api.allocations.get());assertEquals(2,api.submissions.get());assertTrue(repository.claimedGtins().contains(old));
    }
    @Test void pendingOzonFeedCannotAllocateANewGtinThroughTheExplicitRecoveryAction(){
        ready();repository.progress(key,Status.ERROR,91L,"fixture previous read failed");api.pendingModeration=true;
        String old=row().gtin();assertTrue(workflow.register(shop,key,draft,true,true,null));assertEquals(old,row().gtin());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void anErrorItemInAProcessingFeedNeverAllowsExplicitRetryToResubmit(){
        ready();api.rejectedFeed=row().feedId();api.rejectionStatus="Processing";repository.progress(key,Status.ERROR,91L,"temporary error");
        assertTrue(workflow.resume(shop,key,false,null));assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(Status.ERROR,row().status());
    }
    @Test void explicitRejectedKitRepairKeepsTheGtinAndArchivesTheOriginalPayload()throws Exception{
        ready();String gtin=row().gtin();api.rejectedFeed=row().feedId();api.rejectionMessage="Карточка должна быть создана с типом Комплект";new ZnackRegistrationMonitor(null,workflow).poll(shop,3);
        assertTrue(workflow.resume(shop,key,false,null));assertEquals(gtin,row().gtin());assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(1,api.allocations.get());assertEquals(2,api.submissions.get());assertTrue(JsonParser.parseString(row().payload()).getAsJsonObject().get("is_kit").getAsBoolean());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT payload_json FROM ozon_card_registration_history")){assertTrue(r.next());assertFalse(JsonParser.parseString(r.getString(1)).getAsJsonObject().has("is_kit"));}
    }
    @Test void automaticSigningConsentSurvivesReopeningAndDoesNotRepeatTheSignature()throws Exception {
        draft=new Draft("6204430000","6204",1,"Fixture","Brand",Map.of(1L,"Fixture",13933L,"6204430000",ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID,"D:::2025-01-01"),Map.of());
        api.pendingModeration=true;assertTrue(workflow.register(shop,key,draft.withAutoSign(true),true,false,null));assertEquals(Status.PROCESSING,row().status());assertEquals(0,api.signatures.get());
        api.pendingModeration=false;var reopened=workflow(Runnable::run);var monitor=new ZnackRegistrationMonitor(null,reopened);assertEquals(1,monitor.poll(shop,3).published());assertEquals(Status.PUBLISHED,row().status());assertEquals(1,api.signatures.get());monitor.poll(shop,3);assertEquals(1,api.signatures.get());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void automaticSigningTimeoutNeverReplaysTheSignatureInTheBackground()throws Exception {
        draft=new Draft("6204430000","6204",1,"Fixture","Brand",Map.of(1L,"Fixture",13933L,"6204430000",ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID,"D:::2025-01-01"),Map.of());
        api.pendingModeration=true;workflow.register(shop,key,draft.withAutoSign(true),true,false,null);api.pendingModeration=false;api.signTimeout=true;var monitor=new ZnackRegistrationMonitor(null,workflow(Runnable::run));monitor.poll(shop,3);assertEquals(Status.SIGNING,row().status());assertEquals(1,api.signatures.get());monitor.poll(shop,3);assertEquals(1,api.signatures.get());api.accepted=true;assertEquals(1,monitor.poll(shop,3).published());assertEquals(1,api.signatures.get());
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
    @Test void linkedCatalogCardCanBeReadAndManuallySignedWithoutInventingAFeedReceipt()throws Exception{
        installExistingCard("draft");assertTrue(workflow.register(shop,key,draft.withAutoSign(true),true,false,null));assertEquals(Status.READY_TO_SIGN,row().status());assertEquals("",row().feedId());assertFalse(RegistrationSigningConsent.allowed(shop,key,row().gtin()));
        assertFalse(workflow.refreshStatusWithoutSigning(shop,key));assertEquals(0,api.signatures.get());assertTrue(sign());assertEquals(Status.PUBLISHED,row().status());assertEquals("",row().feedId());assertEquals(1,api.signatures.get());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    @Test void aSellerReviewedExistingDraftSendsItsSameIdentityToModerationWithoutAllocatingOrSigning()throws Exception{
        installUnsubmittedDraft();api.pendingModeration=true;
        assertFalse(workflow.register(shop,key,draft,false,false,null));assertNull(row());assertEquals(0,api.submissions.get());
        assertTrue(workflow.register(shop,key,draft.withAutoSign(true),true,false,null));
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
        assertTrue(workflow.registerReviewed(shop,prepared,draft,true,false,null));
        assertEquals(Status.READY_TO_SIGN,row().status());assertEquals("feed-1",row().feedId());assertEquals(91L,row().goodId());assertEquals(GtinWriteTestFixture.GTIN,row().gtin());
        assertEquals(1,api.submissions.get());assertEquals(0,api.allocations.get());assertEquals(0,api.signatures.get());
    }
    @Test void anUncertainExistingDraftUpdateIsNeverAutomaticallySentTwice()throws Exception{
        installUnsubmittedDraft();api.feedTimeout=true;api.pendingModeration=true;
        assertTrue(workflow.register(shop,key,draft.withAutoSign(true),true,false,null));
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
        installUnsubmittedDraft();var prior=LicenseRuntime.service();try(var denied=LicenseDomainFixture.denied(root.resolve("draft-denied-license"))){
            try{assertTrue(workflow.register(shop,key,draft,true,false,(state,detail)->{if(state==Status.FEED_SUBMITTING)LicenseRuntime.install(denied);}));}
            finally{LicenseRuntime.install(prior);}
            assertEquals(Status.READY_TO_SUBMIT,row().status());assertEquals(0,api.submissions.get());String frozen=row().payload();assertTrue(ZnackNationalCatalogService.hasReviewedDraft(JsonParser.parseString(frozen).getAsJsonObject()));
            api.forbidQuota=true;assertTrue(workflow.resume(shop,key,false,null));assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(frozen,row().payload());assertEquals(1,api.submissions.get());assertEquals(0,api.allocations.get());assertEquals(0,api.signatures.get());
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
        api.preExisting=ZnackNationalCatalogService.buildPayload(GtinWriteTestFixture.GTIN,draft,"");api.preExisting.addProperty("good_id",91);api.preExisting.addProperty("good_status",status);var states=new JsonArray();states.add(status.equals("published")?"published":"notsigned");api.preExisting.add("good_detailed_status",states);
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
        assertEquals(2,batchWorkflow.submitBatchReviewed(shop,review.ready(),false,true,null).get(10,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(2,api.allocations.get());assertNotNull(repository.find(second));assertNotNull(repository.find(key));
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
        queue.create(shop,keys,false,session().scope(),report);
        assertEquals(0,queue.counts(report).submitted());assertEquals(2,queue.counts(report).remaining());
        var batchWorkflow=new OzonCardRegistrationWorkflow(repository,s->session(),(s,k)->{api.activeKey=k;return snapshots.get(k);},Runnable::run,(s,k,sku,gtin)->false,this::emptyLookup);
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
        var queue=new OzonRegistrationBatchRepository();String run=OzonRegistrationAttemptReports.queue(shop,List.of(key,second));queue.create(shop,List.of(key,second),false,session().scope(),run);
        var worker=new OzonCardRegistrationWorkflow(repository,s->session(),(s,k)->{api.activeKey=k;return snapshots.get(k);},Runnable::run,(s,k,sku,gtin)->false,this::emptyLookup);
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
    private OzonRegistrationBatchRepository singleCardBatch(String state)throws Exception {
        api.knownQuota=true;
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(2,?,?,?,'Fixture',?)")){
            p.setString(1,key.cardId());p.setString(2,key.variantId());p.setString(3,"9001");p.setString(4,Instant.now().toString());p.executeUpdate();
        }
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);
        var queue=new OzonRegistrationBatchRepository();String run=OzonRegistrationAttemptReports.queue(shop,List.of(key));queue.create(shop,List.of(key),false,session().scope(),run);
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
        // Unrelated legacy work still resumes even while another batch remains pending.
        monitor.poll(shop,3);assertEquals(1,api.submissions.get());assertEquals(0,api.allocations.get());assertEquals(Status.READY_TO_SIGN,row().status());
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
    @Test void boundedRetriesRemainPausedAfterReopeningAndPreserveTheFrozenSelection(){
        var queue=new OzonRegistrationBatchRepository();String run=OzonRegistrationAttemptReports.queue(shop,List.of(key));queue.create(shop,List.of(key),true,session().scope(),run);
        for(int attempt=1;attempt<=5;attempt++)assertEquals(attempt<5,queue.defer(run,"NETWORK_TIMEOUT",3600,true,5));
        var reopened=new OzonRegistrationBatchRepository();assertEquals("PAUSED",reopened.latest(shop).state());assertEquals(5,reopened.latest(shop).retryCount());assertNull(reopened.latest(shop).nextRetryAt());assertEquals(1,reopened.counts(run).remaining());assertTrue(reopened.latest(shop).autoSign());assertEquals(run,reopened.create(shop,List.of(new ProductKey(2,Marketplace.OZON,"502","other")),false,session().scope()));
        assertTrue(reopened.control(shop,"QUEUED"));assertEquals(0,reopened.latest(shop).retryCount());assertEquals(key,reopened.next(reopened.latest(shop)).key());assertTrue(reopened.latest(shop).autoSign());
    }
    @Test void futureRetryDeadlineIsHonoredAndCancellationReleasesTheScheduledGuard(){
        var queue=new OzonRegistrationBatchRepository();String run=OzonRegistrationAttemptReports.queue(shop,List.of(key));queue.create(shop,List.of(key),false,session().scope(),run);assertTrue(queue.defer(run,"ZNACK_RATE_LIMIT",3600,true,5));var batch=new OzonRegistrationBatchService(queue,repository,workflow);
        assertTrue(batch.resumePending(shop));assertTrue(OzonRegistrationBatchService.hasPendingJobs());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertTrue(batch.cancel(shop));assertFalse(OzonRegistrationBatchService.hasPendingJobs());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    @Test void aChangedApprovedSourceCannotResumeTheOldKnownUnsentPayload()throws Exception{
        api.feedTimeout=false;var prior=LicenseRuntime.service();try(var denied=LicenseDomainFixture.denied(root.resolve("source-review-license"))){
            try{assertTrue(workflow.register(shop,key,draft,true,false,(status,detail)->{if(status==Status.GTIN_GENERATED)LicenseRuntime.install(denied);}));}finally{LicenseRuntime.install(prior);}
        }
        assertEquals(Status.READY_TO_SUBMIT,row().status());String payload=row().payload(),gtin=row().gtin();var base=snapshot;snapshot=new ProductSnapshot(base.key(),"live:changed","Changed title",base.description(),base.brand(),base.vendorArticle(),base.categoryId(),base.media(),base.attributes(),base.variants(),base.dimensions(),base.documents(),base.checkedAt(),base.metadata());
        assertTrue(workflow.resume(shop,key,null));assertEquals(0,api.submissions.get());assertEquals(1,api.allocations.get());assertEquals(gtin,row().gtin());assertEquals(payload,row().payload());assertEquals(Status.PRE_SUBMIT_ERROR,row().status());
    }
    @Test void unchangedConfiguredShopBrandCanResumeTheSameReviewedPayload()throws Exception{
        new com.vncode.app.integration.ozon.OzonShopBrand().save(shop,"Configured Brand");var base=snapshot;snapshot=new ProductSnapshot(base.key(),base.revision(),base.title(),base.description(),"",base.vendorArticle(),base.categoryId(),base.media(),base.attributes(),base.variants(),base.dimensions(),base.documents(),base.checkedAt(),base.metadata());
        var approved=new Draft(draft.tnved(),draft.feedTnved(),draft.categoryId(),draft.goodName(),"Configured Brand",draft.attributes(),draft.attributeTypes());var prior=LicenseRuntime.service();
        try(var denied=LicenseDomainFixture.denied(root.resolve("configured-brand-license"))){try{assertTrue(workflow.register(shop,key,approved,true,false,(status,detail)->{if(status==Status.GTIN_GENERATED)LicenseRuntime.install(denied);}));}finally{LicenseRuntime.install(prior);}}
        assertEquals(Status.READY_TO_SUBMIT,row().status());String payload=row().payload(),gtin=row().gtin();assertTrue(workflow.resume(shop,key,null));assertEquals(Status.READY_TO_SIGN,row().status());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(gtin,row().gtin());assertEquals(payload,row().payload());assertEquals("Configured Brand",api.lastPayload.get("brand").getAsString());
    }
    @Test void batchSubmissionVerifiesOneCardWithoutWaitingForModerationOrSigning()throws Exception {
        api.pendingModeration=true;var prepared=workflow.prepare(shop,key);
        assertTrue(workflow.registerReviewedForBatch(shop,prepared,draft.withAutoSign(true),null));
        assertEquals(Status.PROCESSING,row().status());assertFalse(row().feedId().isBlank());assertEquals(1,api.submissions.get());assertEquals(1,api.polls.get());assertEquals(0,api.signatures.get());
        assertEquals("CATALOG_CARD_IN_MODERATION",row().error());assertTrue(OzonCardRegistrationWorkflow.verifiedSubmission(row()));
        assertTrue(workflow.resumeForBatch(shop,key,true));assertEquals(1,api.submissions.get());assertEquals(2,api.polls.get());assertEquals(0,api.signatures.get());
    }
    @Test void feedReceiptWithoutCardStaysPendingThenLateCardIsReadWithoutAnotherPost()throws Exception {
        api.pendingModeration=true;api.cardMissing=true;
        var prepared=workflow.prepare(shop,key);
        assertTrue(workflow.registerReviewedForBatch(shop,prepared,draft.withAutoSign(true),null));
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
        var prepared=workflow.prepare(shop,key);assertTrue(workflow.registerReviewedForBatch(shop,prepared,draft,null));
        assertEquals(Status.FEED_SUBMITTED,row().status());assertEquals("CATALOG_READBACK_RETRY_PENDING",row().error());
        assertFalse(OzonCardRegistrationWorkflow.verifiedSubmission(row()));String feed=row().feedId(),gtin=row().gtin();
        assertTrue(workflow.verifySubmissionForBatch(shop,key));assertEquals(1,api.submissions.get());
        api.readbackFailure=false;assertTrue(workflow.verifySubmissionForBatch(shop,key));
        assertEquals(feed,row().feedId());assertEquals(gtin,row().gtin());assertEquals(91L,row().goodId());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());
    }
    @Test void rateLimitedReadbackRetainsSavedReceiptAndDoesNotRepeatPaidAllocationOrFeed()throws Exception {
        api.pendingModeration=true;api.readbackStatus=429;
        var prepared=workflow.prepare(shop,key);assertTrue(workflow.registerReviewedForBatch(shop,prepared,draft,null));
        assertEquals(Status.FEED_SUBMITTED,row().status());assertEquals("CATALOG_READBACK_RETRY_PENDING",row().error());
        String feed=row().feedId(),gtin=row().gtin();api.readbackStatus=0;
        assertTrue(workflow.verifySubmissionForBatch(shop,key));assertTrue(OzonCardRegistrationWorkflow.verifiedSubmission(row()));
        assertEquals(feed,row().feedId());assertEquals(gtin,row().gtin());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void failedAndErrorFeedWithoutDetailsAreReportedInsteadOfWaitingForModeration()throws Exception {
        api.emptyDetailFailure="Failed";
        var prepared=workflow.prepare(shop,key);assertTrue(workflow.registerReviewedForBatch(shop,prepared,draft,null));
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
        api.allocationTimeout=true;assertTrue(workflow.register(shop,key,draft,true,false,null));assertEquals(Status.GTIN_REVIEW_REQUIRED,row().status());
        assertFalse(workflow.resume(shop,key,null));assertTrue(workflow.register(shop,key,draft,true,false,null));assertEquals(1,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    @Test void lostFeedResultKeepsGtinAndRequiresReadbackReview() {
        api.feedTimeout=true;assertTrue(workflow.register(shop,key,draft,true,false,null));assertEquals(Status.FEED_REVIEW_REQUIRED,row().status());assertEquals(GtinWriteTestFixture.GTIN,row().gtin());
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
    @Test void exactMarketplaceReadbackActivatesFirstCardAndCandidateOnlyAfterCompletion() {
        published();assertTrue(workflow.complete(shop,key,true,null));assertEquals("",row().activeGtin());
        assertNull(new OzonProductGtinMappingRepository().findGtin(2,"9001"));onMarketplace=true;
        assertTrue(workflow.complete(shop,key,true,null));String old=row().gtin();assertEquals(old,row().activeGtin());assertEquals(old,new OzonProductGtinMappingRepository().findGtin(2,"9001"));
        api.accepted=false;assertTrue(workflow.register(shop,key,draft,true,true,null));assertEquals(old,row().activeGtin());assertEquals(old,new OzonProductGtinMappingRepository().findGtin(2,"9001"));
        assertTrue(sign());assertEquals("00000000000024",row().gtin());assertEquals(old,row().activeGtin());
        onMarketplace=false;assertTrue(workflow.complete(shop,key,true,null));assertEquals(old,row().activeGtin());onMarketplace=true;assertTrue(workflow.complete(shop,key,true,null));assertEquals("00000000000024",row().activeGtin());assertEquals("00000000000024",new OzonProductGtinMappingRepository().findGtin(2,"9001"));
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
        ProductKey activeKey=key;boolean knownQuota,mandatoryCountry;
        String rejectedFeed="";
        String rejectionStatus="Rejected",rejectionMessage="required attribute missing; token=fixture-secret";
        boolean multipleCategories,allocationTimeout,feedTimeout,signTimeout,changeSigningDocument,accepted,rejected,pendingModeration,laggedModeration,cardMissing,readbackFailure;
        final Set<String> missingGtins=java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Map<String,String> feedGtins=new java.util.concurrent.ConcurrentHashMap<>();
        int readbackStatus;
        String emptyDetailFailure="";
        volatile String invalidFeedIdentity="";
        boolean unitEvidence=true,forbidQuota;
        String currentGtin=GtinWriteTestFixture.GTIN;
        public JsonElement generatedGtins(String base,String token){if(forbidQuota)throw new AssertionError("An existing draft update does not allocate or require allocation quota");return json(knownQuota?"{\"result\":{\"drafts\":[],\"monthly-limit\":{\"limit\":100,\"usage\":0}}}":"{\"result\":{\"drafts\":[]}}");}
        public JsonElement generateGtins(String base,String token,int quantity)throws IOException{assertEquals(Status.ALLOCATING_GTIN,repository.find(activeKey).status());int count=allocations.incrementAndGet();if(allocationTimeout)throw new IOException("fixture allocation timeout");currentGtin=count==1?GtinWriteTestFixture.GTIN:"00000000000024";return json("{\"result\":{\"drafts\":[{\"gtin\":\""+currentGtin+"\"}]}}");}
        public JsonElement nationalCatalogCategories(String base,String token,String tnved){if(tnved.length()==10)return json("{\"result\":[]}");return json(multipleCategories?"{\"result\":[{\"cat_id\":1,\"cat_name\":\"Fixture\",\"category_active\":true},{\"cat_id\":2,\"cat_name\":\"Fixture 2\",\"category_active\":true}]}":"{\"result\":[{\"cat_id\":1,\"cat_name\":\"Fixture\",\"category_active\":true}]}");}
        public JsonElement nationalCatalogAttributes(String base,String token,long category){var response=json("{\"result\":[{\"attr_id\":1,\"attr_name\":\"Fixture\",\"attr_field_type\":\"text\"},{\"attr_id\":23557,\"attr_name\":\"Declaration\",\"attr_field_type\":\"text\"},{\"attr_id\":23561,\"attr_name\":\"Certificate\",\"attr_field_type\":\"text\"}]}").getAsJsonObject();if(mandatoryCountry)response.getAsJsonArray("result").add(json("{\"attr_id\":2630,\"attr_name\":\"Страна производства\",\"attr_field_type\":\"text\",\"attr_preset_only\":true,\"attr_preset\":[]}"));return response;}
        public JsonElement nationalCatalogAllAttributes(String base,String token,long category){var response=nationalCatalogAttributes(base,token,category).getAsJsonObject();var all=response.getAsJsonArray("result");for(String field:List.of("{\"attr_id\":35,\"attr_name\":\"Размер\",\"attr_field_type\":\"text\"}","{\"attr_id\":36,\"attr_name\":\"Цвет\",\"attr_field_type\":\"text\"}","{\"attr_id\":13914,\"attr_name\":\"Артикул\",\"attr_field_type\":\"text\"}"))all.add(json(field));return response;}
        public JsonElement nationalCatalogCountries(String base,String token){countryReads.incrementAndGet();return json("{\"apiversion\":3,\"result\":{\"_etag\":\"fixture-country-etag\",\"_list\":[{\"country_iso\":\"RU\",\"country_name\":\"Россия\"},{\"country_iso\":\"CN\",\"country_name\":\"Китай\"}]}}");}
        public JsonElement submitNationalCatalogFeed(String base,String token,JsonElement body)throws IOException{assertEquals(Status.FEED_SUBMITTING,repository.find(activeKey).status());lastPayload=body.getAsJsonObject().deepCopy();assertFalse(lastPayload.has("_vncode_reviewed_draft"));if(preExisting!=null&&lastPayload.has("moderation")){preExisting=lastPayload.deepCopy();preExisting.addProperty("good_status","draft");var state=new JsonArray();state.add(pendingModeration?"moderation":"notsigned");preExisting.add("good_detailed_status",state);}submissions.incrementAndGet();if(feedTimeout)throw new IOException("fixture feed timeout");String feed="feed-"+submissions.get();feedGtins.put(feed,lastPayload.get("gtin").getAsString());return json("{\"result\":{\"feed_id\":\""+feed+"\"}}");}
        public JsonElement productCards(String base,String token,String gtins)throws IOException {
            if(readbackStatus>0)throw new ZnackApiClient.ZnackApiException("fixture readback deferred",readbackStatus,"{}");
            if(readbackFailure)throw new IOException("fixture readback offline");
            if(cardMissing||missingGtins.contains(gtins))return json("{\"result\":[]}");
            if(preExisting!=null){var card=preExisting.deepCopy();if(accepted){card.addProperty("good_status","published");var state=new JsonArray();state.add("published");card.add("good_detailed_status",state);}unit(card,gtins);var cards=new JsonArray();cards.add(card);var response=new JsonObject();response.add("result",cards);return response;}
            boolean published=accepted||!gtins.equals(currentGtin);
            var card=lastPayload==null?new JsonObject():lastPayload.deepCopy();card.addProperty("gtin",gtins);card.addProperty("good_id",91);if(!card.has("tnved"))card.addProperty("tnved","6204");card.addProperty("good_status",published?"published":"draft");var status=new JsonArray();status.add(published?"published":pendingModeration?"moderation":"notsigned");card.add("good_detailed_status",status);unit(card,gtins);var cards=new JsonArray();cards.add(card);var response=new JsonObject();response.add("result",cards);return response;
        }
        private void unit(JsonObject card,String gtin){if(!unitEvidence){card.remove("identified_by");return;}var values=new JsonArray();var value=new JsonObject();value.addProperty("type","gtin");value.addProperty("value",gtin);value.addProperty("level","trade-unit");values.add(value);card.add("identified_by",values);}
        public JsonElement nationalCatalogFeedStatus(String base,String token,String feed){polls.incrementAndGet();String feedGtin=feedGtins.getOrDefault(feed,currentGtin);
            if(invalidFeedIdentity.equals("missing"))return json("{\"result\":{\"status\":\"Processing\",\"item\":[{\"gtin\":\"00000000000024\",\"good_id\":91}]}}");
            if(invalidFeedIdentity.equals("changed"))return json("{\"result\":{\"status\":\"Processing\",\"item\":[{\"gtin\":\""+currentGtin+"\",\"good_id\":91},{\"gtin\":\""+currentGtin+"\",\"good_id\":92}]}}");
            if(!emptyDetailFailure.isBlank())return json("{\"result\":{\"status\":\""+emptyDetailFailure+"\",\"item\":[]}}");if(rejected||feed.equals(rejectedFeed))return json("{\"result\":{\"status\":\""+rejectionStatus+"\",\"item\":[{\"gtin\":\""+feedGtin+"\",\"status_code\":400,\"message\":\""+rejectionMessage+"\"}]}}");return json("{\"result\":{\"status\":\""+(pendingModeration?"Processing":accepted&&!laggedModeration?"Signed":"Moderated")+"\",\"item\":[{\"gtin\":\""+feedGtin+"\",\"good_id\":91}]}}");}
        public JsonElement nationalCatalogSigningDocument(String base,String token,JsonObject body){int count=documents.incrementAndGet();return json("{\"result\":{\"xmls\":[{\"goodId\":91,\"xml\":\"<good>"+(changeSigningDocument&&count>1?"changed":"reviewed")+"</good>\"}]}}");}
        public JsonElement signNationalCatalogProduct(String base,String token,JsonArray body)throws IOException{assertEquals(Status.SIGNING,repository.find(activeKey).status());assertEquals("SENDING",repository.find(activeKey).signatureState());assertEquals("<good>reviewed</good>",new String(Base64.getDecoder().decode(body.get(0).getAsJsonObject().get("base64Xml").getAsString()),java.nio.charset.StandardCharsets.UTF_8));signatures.incrementAndGet();if(signTimeout)throw new IOException("fixture signing timeout");accepted=true;return json("{\"result\":{\"signed\":[91]}}");}
        JsonElement json(String value){return JsonParser.parseString(value);}
    }
}
