package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

class ZnackCardRegistrationWorkflowTest {
    @TempDir Path temp;
    ZnackCardRegistrationRepository repository;
    Shop shop=new Shop(1,"Fixture","fixture");
    Draft draft=new Draft("6204",1,"Fixture","Brand",Map.of(1L,"Fixture"));
    FixtureApi api;
    ZnackCardRegistrationWorkflow workflow;
    ZnackRegistrationBatchService reviewedBatch;
    RegistrationIntent directIntent;
    ZnackCardRegistrationWorkflow reviewedWorkflow;
    Draft reviewedFrozen;
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString()); Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','fixture')");
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,101,'PANTS','Fixture','2026-10-06T00:00:00Z')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,11,101,'XL')");
        }
        repository=RegistrationWorkflowTestFixtures.repository(shop); api=new FixtureApi();
        var auth=new ZnackAuthService(api,null) {
            @Override public String trueApiToken(ZnackModels.Settings settings){return "fixture-token";}
        };
        var catalog=new ZnackNationalCatalogService(api,auth,(payload,context)->new com.vncode.app.integration.znack.signature.CryptoProSigningResult(new byte[]{1},""),ZnackModels.Settings.empty());
        workflow=RegistrationWorkflowTestFixtures.workflow(repository,
                ignored->new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token",catalog),Runnable::run);
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    Sku current(){return repository.search(new SearchCriteria(1,"",List.of(),"ALL",20,0)).getFirst();}
    boolean startReviewed(Draft requested)throws Exception {return startReviewed(requested,false);}
    boolean startReviewed(Draft requested,boolean repairApproved)throws Exception {return startReviewed(requested,repairApproved,Runnable::run);}
    boolean startReviewed(Draft requested,boolean repairApproved,java.util.concurrent.Executor executor)throws Exception {
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);
        var key=new com.vncode.app.features.products.ProductKey(1,shop.getMarketplace(),"101","11");
        var variant=new com.vncode.app.features.products.ProductVariant("11","PANTS","","XL","",false,"");
        var snapshot=new com.vncode.app.features.products.ProductSnapshot(key,"fixture","Fixture","","Brand","PANTS","category",List.of(),List.of(),List.of(variant),null,List.of(),java.time.Instant.now(),Map.of("owner",com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop)));
        var settings=new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture")).getSettings();var scope=ZnackRegistrationScope.fromSettings(settings);
        var auth=new ZnackAuthService(api,null){@Override public String trueApiToken(ZnackModels.Settings ignored){return "fixture-token";}};
        var catalog=new ZnackNationalCatalogService(api,auth,(payload,context)->new com.vncode.app.integration.znack.signature.CryptoProSigningResult(new byte[]{1},""),settings);
        var session=new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token",catalog,scope);
        var schema=session.catalog().registrationAttributes(requested.categoryId(),session.token());
        var fullCode=new Draft("6204420000",requested.feedTnved(),requested.categoryId(),requested.goodName(),requested.brand(),requested.attributes(),requested.attributeTypes(),requested.autoSign(),requested.isKit());
        var reviewed=ZnackCardRegistrationWorkflow.withSchemaTypes(current().status()==Status.READY_TO_SUBMIT?requested:fullCode.withProductCategory(current().subjectName()),schema,current().wbSize());
        var plan=new RegistrationDynamicPreflight.Plan(snapshot,reviewed,schema,null);
        var facts=RegistrationFieldProvenance.capture(snapshot,snapshot,reviewed,schema,Map.of(),null,false);
        RegistrationFieldProvenance.save(shop,List.of(facts));
        var values=RegistrationFieldProvenance.readBatch(shop,List.of(plan)).get(key);
        var consent=RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(plan),Map.of(key,RegistrationSourceConsent.forIdentity(current().gtin(),current().goodId())),Map.of(),Map.of(key,values),repairApproved);
        reviewedWorkflow=new ZnackCardRegistrationWorkflow(repository,ignored->session,Runnable::run,false,(owner,nm,id,gtin)->false,
                ()->api.preExistingRawDraft==null?RegistrationWorkflowTestFixtures.emptyLookup():new RegistrationExistingCards.LookupContext(
                    (owner,scoped)->{var data=CatalogProductDataParser.parse(api.preExistingRawDraft);return List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin("04631993764370","PANTS","черный","XL",true,false,"fixture",java.time.Instant.now(),data));},
                    (scoped,gtin,id)->scoped.catalog().observedCardStatus(scoped.token(),gtin,id),(owner,scoped)->"fixture"),(owner,product)->snapshot);
        reviewedFrozen=reviewed;
        var batch=new ZnackRegistrationBatchService(repository,reviewedWorkflow,(owner,sku,sign)->reviewed,executor,true);reviewedBatch=batch;
        batch.start(shop,List.of(current()),requested.autoSign(),consent);return true;
    }
    ZnackCardRegistrationWorkflow reviewedDirect(Draft requested,java.util.concurrent.Executor executor,java.util.function.BiConsumer<Status,String> listener)throws Exception {return reviewedDirect(requested,executor,listener,false);}
    ZnackCardRegistrationWorkflow reviewedDirect(Draft requested,java.util.concurrent.Executor executor,java.util.function.BiConsumer<Status,String> listener,boolean strict)throws Exception {
        com.vncode.app.testsupport.RegistrationSourceFixture.ready(shop);
        var key=new com.vncode.app.features.products.ProductKey(1,shop.getMarketplace(),"101","11");
        var variant=new com.vncode.app.features.products.ProductVariant("11","PANTS","","XL","",false,"");
        var snapshot=new com.vncode.app.features.products.ProductSnapshot(key,"fixture","Fixture","","Brand","PANTS","category",List.of(),List.of(),List.of(variant),null,List.of(),java.time.Instant.now(),Map.of("owner",com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop)));
        var settings=new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture")).getSettings();var scope=ZnackRegistrationScope.fromSettings(settings);
        var auth=new ZnackAuthService(api,null){@Override public String trueApiToken(ZnackModels.Settings ignored){return "fixture-token";}};
        var catalog=new ZnackNationalCatalogService(api,auth,(payload,context)->new com.vncode.app.integration.znack.signature.CryptoProSigningResult(new byte[]{1},""),settings);
        var session=new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token",catalog,scope);
        var schema=catalog.registrationAttributes(requested.categoryId(),session.token());
        var fullCode=new Draft("6204420000",requested.feedTnved(),requested.categoryId(),requested.goodName(),requested.brand(),requested.attributes(),requested.attributeTypes(),requested.autoSign(),requested.isKit());
        var reviewed=ZnackCardRegistrationWorkflow.withSchemaTypes(fullCode.withProductCategory(current().subjectName()),schema,current().wbSize());
        var plan=new RegistrationDynamicPreflight.Plan(snapshot,reviewed,schema,null);
        RegistrationFieldProvenance.save(shop,List.of(RegistrationFieldProvenance.capture(snapshot,snapshot,reviewed,schema,Map.of(),null,false)));
        var values=RegistrationFieldProvenance.readBatch(shop,List.of(plan)).get(key);
        var consent=RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(plan),Map.of(key,RegistrationSourceConsent.forIdentity("",null)),Map.of(),Map.of(key,values),false);
        String run=UUID.randomUUID().toString();new ZnackRegistrationBatchStore().create(shop,run,List.of(current()),requested.autoSign(),"",consent);
        var direct=new ZnackCardRegistrationWorkflow(repository,ignored->session,executor,strict,(owner,nm,id,gtin)->false,RegistrationWorkflowTestFixtures::emptyLookup,(owner,product)->snapshot);
        directIntent=new RegistrationIntent(run,0);assertTrue(direct.submitOnly(shop,current(),reviewed,listener,RegistrationWorkflowTestFixtures.emptyLookup(),session,directIntent));
        return direct;
    }
    @Test void existingRegisterActionUsesV3JournalWithoutANewScreen()throws Exception {
        assertTrue(startReviewed(draft));
        assertEquals(Status.PUBLISHED,current().status());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT action,state,receipt FROM znack_v3_dispatches ORDER BY rowid");var r=p.executeQuery()){
            assertTrue(r.next());assertEquals("ALLOCATE_GTIN",r.getString(1));assertEquals("ACKNOWLEDGED",r.getString(2));assertEquals(current().gtin(),r.getString(3));
            assertTrue(r.next());assertEquals("SUBMIT",r.getString(1));assertEquals("ACKNOWLEDGED",r.getString(2));assertEquals(current().feedId(),r.getString(3));assertFalse(r.next());
        }
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void publishedStatusForOlderContentCannotCompleteTheReviewedRegistration()throws Exception {
        api.staleCardContent=true;
        var session=fixtureSession();
        var scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture")).getSettings());
        var scoped=RegistrationWorkflowTestFixtures.workflow(repository,ignored->new ZnackCardRegistrationWorkflow.CatalogSession(session.token(),session.catalog(),scope),Runnable::run);
        assertTrue(startReviewed(automaticDraft().withAutoSign(false)));
        assertNotEquals(Status.PUBLISHED,current().status());assertTrue(current().errorMessage().contains("CATALOG_PUBLICATION_NOT_CONFIRMED"));
        assertEquals(1,api.submissions.get());var saved=current();
        api.staleCardContent=false;assertTrue(scoped.refreshStatusWithoutSigning(shop,saved));assertEquals(Status.PUBLISHED,current().status());
        assertEquals(saved.gtin(),current().gtin());assertEquals(saved.feedId(),current().feedId());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());
    }
    @Test void failedAllocationReadBeforeMutationRetriesNormallyWithoutRequestingAnotherIdentity()throws Exception {
        api.failGeneratedReadNumber=1;
        assertTrue(startReviewed(draft));assertEquals(0,api.allocations.get());
        try(var c=Database.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT state FROM znack_v3_dispatches WHERE action='ALLOCATE_GTIN'")){
            assertTrue(r.next());assertEquals("REJECTED",r.getString(1));
        }
        api.failGeneratedReadNumber=0;
        assertEquals(Status.PREFLIGHT_ERROR,current().status());
        assertFalse(workflow.requestNewGtin(shop,current(),draft,true,null),"No uncertain allocation needs a replacement identity");
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_registration_batches SET retry_at=NULL WHERE id=?")){p.setString(1,reviewedBatch.latest(shop).runId());p.executeUpdate();}
        assertTrue(reviewedBatch.resumePending(shop));assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void explicitRejectedReplacementAdvancesGenerationInsideTheArchiveTransaction()throws Exception {
        api.firstRejection="required attribute missing";startReviewed(draft);assertEquals(Status.ERROR,current().status());
        var journal=new com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal();String first=journal.allocationGeneration(1,101,11);
        assertTrue(repository.claimRejectedReplacement(1,current()));String second=journal.allocationGeneration(1,101,11);assertNotEquals(first,second);
        repository.saveGenerated(1,current(),"04631993764363","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764363",draft,"").toString());
        repository.updateProgress(1,11,Status.ERROR,"second-rejected-feed",92L,"rejected",false);
        assertTrue(repository.claimRejectedReplacement(1,current()));assertNotEquals(second,journal.allocationGeneration(1,101,11));
    }
    @Test void exactExistingCardResolvesLostSubmissionBeforeSeparateReviewedSigning()throws Exception {
        api.feedTimeout=true;api.moderated=true;
        var session=fixtureSession();var scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture")).getSettings());
        var scoped=RegistrationWorkflowTestFixtures.workflow(repository,ignored->new ZnackCardRegistrationWorkflow.CatalogSession(session.token(),session.catalog(),scope),Runnable::run);
        assertTrue(startReviewed(automaticDraft().withAutoSign(false)));assertFalse(current().hasFeed());assertEquals(1,api.submissions.get());
        repository.updateProgress(1,11,Status.FEED_REVIEW_REQUIRED,null,91L,"CATALOG_FEED_READBACK_REQUIRED",false);
        api.feedTimeout=false;assertTrue(scoped.verifySubmissionForBatch(shop,current()));assertEquals(Status.READY_TO_SIGN,current().status());
        try(var c=Database.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT state,receipt FROM znack_v3_dispatches WHERE action='SUBMIT'")){
            assertTrue(r.next());assertEquals("OBSERVED",r.getString(1));assertEquals("",r.getString(2));
        }
        assertTrue(scoped.signApprovedReviewed(shop,scoped.prepareApprovedSigning(shop,List.of(current())),null));
        assertEquals(1,api.signatures.get());assertEquals(1,api.submissions.get());assertEquals(Status.PUBLISHED,current().status());
    }
    @Test void manualDraftMissingRequiredCountryCannotAllocateAGtin()throws Exception{
        api.mandatoryCountry=true;
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,11,'seller-existing-barcode')");}
        var before=current().barcodes();
        assertTrue(startReviewed(draft));
        assertEquals(Status.PREFLIGHT_ERROR,current().status());assertTrue(current().errorMessage().contains("2630"));
        assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals(0,api.signatures.get());
        assertTrue(current().gtin()==null||current().gtin().isBlank());assertEquals(before,current().barcodes());
    }
    @Test void manualDraftWithAnUnverifiedCountryCannotAllocateAGtin()throws Exception{
        api.mandatoryCountry=true;
        var invalid=new Draft("6204",1,"Fixture","Brand",Map.of(1L,"Fixture",2630L,"XX"));
        assertTrue(startReviewed(invalid));
        assertEquals(Status.PREFLIGHT_ERROR,current().status());assertTrue(current().errorMessage().contains("CATALOG_COUNTRY_DICTIONARY_INVALID"));
        assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals(0,api.signatures.get());
    }
    @Test void validManualCountryIsVerifiedOnceBeforeAllocationAndReusedForSubmission()throws Exception{
        api.mandatoryCountry=true;api.moderated=true;
        var valid=new Draft("6204",1,"Fixture","Brand",Map.of(1L,"Fixture",2630L,"RU"));
        assertTrue(startReviewed(valid));assertEquals(Status.READY_TO_SIGN,current().status());
        assertEquals(2,api.countryReads.get(),"Country dictionary is read once for seller review and once for the write; submission reuses its write-scope read");assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
        assertTrue(api.lastPayload.getAsJsonArray("good_attrs").asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(attribute->attribute.get("attr_id").getAsLong()==2630L&&attribute.get("attr_value").getAsString().equals("RU")));
    }
    @Test void storedUnsentDraftMissingCurrentRequiredCountryKeepsItsIdentityWithoutSending()throws Exception{
        String stored=ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString();
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",stored);
        repository.updateProgress(1,11,Status.READY_TO_SUBMIT,null,null,null,false);api.mandatoryCountry=true;
        assertTrue(startReviewed(draft));
        assertEquals(Status.PRE_SUBMIT_ERROR,current().status());assertEquals("04631993764370",current().gtin());
        assertFalse(current().hasFeed());assertEquals(stored,repository.payload(1,11));
        assertTrue(current().errorMessage().contains("2630"));assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    @Test void externallySignedCardWithLaggedFeedDoesNotSignAgain()throws Exception{
        api.moderated=true;startReviewed(draft);assertEquals(Status.READY_TO_SIGN,current().status());
        api.signatureAccepted=true;api.laggedModeration=true;
        assertTrue(workflow.refreshStatus(shop,current()));assertEquals(Status.PUBLISHED,current().status());
        assertEquals(0,api.signatures.get());assertEquals(0,api.documents.get());assertEquals(1,api.submissions.get());
    }
    @Test void definiteRejectionAfterASigningTimeoutShowsTheReasonWithoutReplaying()throws Exception{
        api.moderated=true;api.signTimeout=true;startReviewed(draft);
        RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),true,null);assertEquals(Status.SIGNING,current().status());
        api.firstRejection="Permit does not cover product";assertFalse(workflow.refreshStatus(shop,current()));
        assertEquals(Status.ERROR,current().status());assertTrue(current().errorMessage().contains("Permit does not cover product"));
        assertEquals(1,api.signatures.get());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void nextCardWaitsForWorkerCheckpointInsteadOfQueueAcknowledgement() throws Exception {
        var workers=new ArrayDeque<Runnable>();
        api.pendingModeration=true;
        var queued=reviewedDirect(draft,workers::add,null);
        var finished=queued.completion(shop,current()).toCompletableFuture();
        assertFalse(finished.isDone());assertEquals(0,api.submissions.get());
        var advanced=new AtomicInteger();
        finished.thenRun(()->{assertFalse(queued.isRunning(shop,current()));assertEquals(Status.PROCESSING,current().status());advanced.incrementAndGet();});
        assertEquals(0,advanced.get());workers.removeFirst().run();
        assertTrue(finished.isDone());assertEquals(1,advanced.get());assertEquals(1,api.submissions.get());
        assertNotEquals(Status.PUBLISHED,current().status());
        assertTrue(queued.completion(shop,current()).toCompletableFuture().isDone());
    }
    @Test void failedCardAlsoCompletesBatchCheckpointAndReleasesRunningClaim() {
        var workers=new ArrayDeque<Runnable>();
        var queued=RegistrationWorkflowTestFixtures.workflow(repository,ignored->{throw new IOException("fixture connection unavailable");},workers::add);
        assertTrue(queued.start(shop,current(),draft,null));
        var finished=queued.completion(shop,current()).toCompletableFuture();assertFalse(finished.isDone());
        workers.removeFirst().run();assertTrue(finished.isDone());assertFalse(queued.isRunning(shop,current()));
        assertNotEquals(Status.PUBLISHED,current().status());assertEquals(0,api.submissions.get());
    }
    @Test void registeredSignedCardFlowsThroughCatalogAndRealWbCardUpdateWithReadback()throws Exception {
        com.vncode.app.features.gtinsync.GtinWriteTestFixture.saveSettings(shop,"A".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        api.moderated=true;assertTrue(startReviewed(draft));assertEquals(Status.READY_TO_SIGN,current().status());
        assertTrue(RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),true,null));assertEquals(Status.PUBLISHED,current().status());
        PublishedCardGtinFixture.synchronize(shop,current().gtin(),"101","11","");
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(1,api.signatures.get());
    }
    @Test void signedRequestWithNoPublishedCardWaitsAndResumesWithoutAllocatingAgain()throws Exception {
        com.vncode.app.features.gtinsync.GtinWriteTestFixture.saveSettings(shop,"A".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        api.moderated=true;api.cardPublished=false;
        assertTrue(startReviewed(draft));assertEquals(Status.READY_TO_SIGN,current().status());
        assertTrue(RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),true,null));
        assertNotEquals(Status.PUBLISHED,current().status());assertFalse(current().wbUpdated());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(1,api.signatures.get());
        assertFalse(workflow.refreshStatus(shop,current()));assertNotEquals(Status.PUBLISHED,current().status());
        api.cardPublished=true;assertTrue(workflow.refreshStatus(shop,current()));assertEquals(Status.PUBLISHED,current().status());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(1,api.signatures.get());
        PublishedCardGtinFixture.synchronize(shop,current().gtin(),"101","11","");
    }
    @Test void legacyPublishedLabelIsReconciledWithoutReplacingItsGtinOrFeed()throws Exception {
        assertTrue(startReviewed(draft));var saved=current();assertEquals(Status.PUBLISHED,saved.status());
        api.cardPublished=false;assertFalse(workflow.refreshStatus(shop,saved));assertEquals(Status.PROCESSING,current().status());
        assertEquals(saved.gtin(),current().gtin());assertEquals(saved.feedId(),current().feedId());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());
        api.cardPublished=true;assertTrue(workflow.refreshStatus(shop,current()));assertEquals(Status.PUBLISHED,current().status());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void completedFeedWithoutACardIsNotResubmittedWithoutRejectionEvidence()throws Exception {
        api.completedWithoutCard=true;assertTrue(startReviewed(draft));var failed=current();assertEquals(Status.ERROR,failed.status());
        assertTrue(failed.errorMessage().contains("CATALOG_FEED_COMPLETED_WITHOUT_CARD"));assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
        assertTrue(workflow.start(shop,failed,draft,null));assertEquals(Status.ERROR,current().status());assertEquals(failed.gtin(),current().gtin());
        assertEquals(failed.feedId(),current().feedId());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void aSlowCatalogImportStaysPendingAndNeverBecomesAnInventedRejection()throws Exception {
        api.pendingModeration=true;assertTrue(startReviewed(draft));
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("UPDATE znack_card_registrations SET created_at='2020-01-01T00:00:00Z'");}
        api.cardMissing=true;assertFalse(workflow.refreshStatus(shop,current()));assertEquals(Status.FEED_SUBMITTED,current().status());assertEquals("CATALOG_REQUEST_ACCEPTED_CARD_PENDING",current().errorMessage());
        assertEquals(1,repository.pendingStatus(1,3).size());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
        api.cardMissing=false;api.pendingModeration=false;assertTrue(workflow.refreshStatus(shop,current()));assertEquals(Status.PUBLISHED,current().status());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void temporarilyMissingSigningDocumentCannotGrantApprovalOrSignUntilExplicitReview()throws Exception {
        api.moderated=true;assertTrue(startReviewed(draft));api.signingDocumentMissing=true;
        assertThrows(IOException.class,()->workflow.prepareApprovedSigning(shop,List.of(current())));
        assertEquals(Status.READY_TO_SIGN,current().status());assertEquals(0,api.signatures.get());
        assertFalse(RegistrationSigningConsent.allowed(shop,new com.vncode.app.features.products.ProductKey(1,shop.getMarketplace(),"101","11"),current().gtin()));
        api.signingDocumentMissing=false;
        var review=workflow.prepareApprovedSigning(shop,List.of(current()));assertEquals(1,review.cards().size());
        assertTrue(workflow.signApprovedReviewed(shop,review,null));assertEquals(Status.PUBLISHED,current().status());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(1,api.signatures.get());
    }
    @Test void deniedRegistrationCannotClaimOrAllocate()throws Exception {
        var prior=LicenseRuntime.service();
        try(var denied=LicenseDomainFixture.denied(temp.resolve("denied-license"))) {
            LicenseRuntime.install(denied);
            try {
                assertThrows(LicenseDeniedException.class,()->workflow.start(shop,current(),draft,null));
                assertEquals(Status.NOT_CREATED,current().status());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
            }finally{LicenseRuntime.install(prior);}
        }
    }
    @Test void expiryWhileQueuedBeforeAllocationStaysKnownUnsent()throws Exception {
        var queued=new ArrayList<Runnable>();var prior=LicenseRuntime.service();
        var waiting=RegistrationWorkflowTestFixtures.workflow(repository,
                ignored->{throw new AssertionError("Denied queued work must not authenticate");},queued::add);
        assertTrue(waiting.start(shop,current(),draft,null));
        try(var denied=LicenseDomainFixture.denied(temp.resolve("denied-license"))) {
            LicenseRuntime.install(denied);
            try {
                queued.getFirst().run();
                assertEquals(Status.PREFLIGHT_ERROR,current().status());assertTrue(current().canRegister());
                assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
            }finally{LicenseRuntime.install(prior);}
        }
    }
    @Test void revocationAfterAllocationKeepsGtinForExplicitFeedResume()throws Exception {
        var prior=LicenseRuntime.service();
        try(var denied=LicenseDomainFixture.denied(temp.resolve("denied-license"))) {
            try {
                var direct=reviewedDirect(draft,Runnable::run,(status,detail)->{if(status==Status.GTIN_GENERATED)LicenseRuntime.install(denied);});
                assertEquals(Status.READY_TO_SUBMIT,current().status());assertEquals("04631993764370",current().gtin());
                assertEquals(1,api.allocations.get());assertEquals(0,api.submissions.get());
                assertThrows(LicenseDeniedException.class,()->workflow.resume(shop,current(),null));
                LicenseRuntime.install(prior);assertTrue(direct.resumeSubmissionOnly(shop,current(),null,null,directIntent));
                assertEquals(Status.PUBLISHED,current().status());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
            }finally{LicenseRuntime.install(prior);}
        }
    }
    @Test void submittedFeedCanFinishReadbackWithoutNewRights()throws Exception {
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",
                ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        repository.updateProgress(1,11,Status.FEED_SUBMITTED,"feed-existing",91L,null,null);
        var prior=LicenseRuntime.service();
        try(var denied=LicenseDomainFixture.denied(temp.resolve("denied-license"))) {
            LicenseRuntime.install(denied);
            try {
                assertTrue(workflow.resume(shop,current(),null));assertEquals(Status.PUBLISHED,current().status());
                assertEquals("feed-existing",current().feedId());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
            }finally{LicenseRuntime.install(prior);}
        }
    }
    @Test void lostAllocationResultPersistsReviewAndCannotBeRetriedNormally() throws Exception {
        api.allocationTimeout=true;
        assertTrue(startReviewed(draft));
        Sku pending=current();
        assertEquals(Status.GTIN_REVIEW_REQUIRED,pending.status());
        assertNull(pending.gtin());
        assertFalse(workflow.start(shop,pending,draft,null));
        assertFalse(workflow.resume(shop,pending,null));
        assertEquals(1,api.allocations.get());
    }
    @Test void failureBeforeAnyAllocationRemainsSafelyRetryable() throws Exception {
        var unavailable=RegistrationWorkflowTestFixtures.workflow(repository,
                ignored->{throw new IllegalStateException("fixture authentication unavailable");},Runnable::run);
        assertTrue(unavailable.start(shop,current(),draft,null));
        assertEquals("PREFLIGHT_ERROR",current().status().name());
        assertTrue(current().canRegister());assertFalse(current().needsGtinReview());
        assertTrue(startReviewed(draft));
        assertEquals(Status.PUBLISHED,current().status());assertEquals(1,api.allocations.get());
    }
    @Test void rejectedExecutorKeepsAnUnsentFeedSafelyRetryable() throws Exception {
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",
                ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        repository.updateProgress(1,11,Status.READY_TO_SUBMIT,null,null,null,null);
        var rejected=RegistrationWorkflowTestFixtures.workflow(repository,
                ignored->{throw new AssertionError("No catalog calls before accepted scheduling");},
                ignored->{throw new java.util.concurrent.RejectedExecutionException("fixture queue unavailable");});
        try { rejected.resume(shop,current(),null); }
        catch(java.util.concurrent.RejectedExecutionException expected) { }
        assertTrue(current().canRegister());assertFalse(current().needsFeedReview());
        assertEquals(0,api.submissions.get());assertEquals(0,api.allocations.get());
        assertTrue(startReviewed(draft));
        assertEquals(Status.PUBLISHED,current().status());assertEquals(1,api.submissions.get());
        assertEquals(0,api.allocations.get());
    }
    @Test void nonterminalOrUnknownFeedErrorsCannotTriggerAnotherSubmission() {
        for(String status:List.of("Processing","")) {
            repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",
                    ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
            repository.updateProgress(1,11,Status.ERROR,"feed-existing",null,"temporary error",false);
            api.rejectExistingFeed=true;api.existingFeedStatus=status;
            assertTrue(workflow.start(shop,current(),draft,null));
            assertEquals(0,api.submissions.get(),"Only definitive Rejected can allow resubmission");
            assertEquals("feed-existing",current().feedId());assertEquals(Status.ERROR,current().status());
        }
    }
    @Test void bareFreshGtinConfirmationCannotAllocateWithoutReviewedIntent() throws Exception {
        pending("CHECKING"); api.existingDraft=true;
        Sku pending=current();
        assertFalse(workflow.requestNewGtin(shop,pending,draft,false,null));
        assertEquals(0,api.allocations.get());
        assertFalse(workflow.requestNewGtin(shop,pending,draft,true,null));
        assertEquals(0,api.allocations.get());
        assertFalse(current().hasIdentity());
        assertFalse(workflow.requestNewGtin(shop,pending,draft,true,null));
        assertEquals(0,api.allocations.get());
    }
    @Test void retryUsesExistingGtinAndFeedInsteadOfCreatingAnotherRequest() {
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",
                ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        repository.updateProgress(1,11,Status.ERROR,"feed-existing",91L,"read timed out",false);
        assertTrue(workflow.start(shop,current(),draft,null));
        assertEquals(0,api.allocations.get());
        assertEquals(0,api.submissions.get());
        assertEquals("feed-existing",current().feedId());
        assertEquals("04631993764370",current().gtin());
        assertEquals(Status.PUBLISHED,current().status());
    }
    @Test void retryPreservesThePreviouslyStoredWildberriesUpdateFlag() {
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",
                ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        repository.updateProgress(1,11,Status.ERROR,"feed-existing",91L,"timeout",true);
        assertTrue(workflow.start(shop,current(),draft,null));
        assertEquals(Status.PUBLISHED,current().status());assertTrue(current().wbUpdated());
    }
    @Test void staleRowsCannotRestartAnAlreadyPublishedCard() {
        Sku stale=current();
        repository.saveGenerated(1,stale,"04631993764370","6204",1,"Fixture","{}");
        repository.updateProgress(1,11,Status.PUBLISHED,"feed-existing",91L,null,false);
        assertFalse(workflow.start(shop,stale,draft,null));
        assertEquals(0,api.allocations.get());
        assertEquals(0,api.submissions.get());
        assertEquals(Status.PUBLISHED,current().status());
    }
    @Test void legacyErrorWithGtinButNoFeedCannotRepeatAnUncertainSubmission() {
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",
                ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        repository.updateProgress(1,11,Status.ERROR,null,null,"legacy timeout",false);
        assertFalse(workflow.start(shop,current(),draft,null));
        assertFalse(workflow.resume(shop,current(),null));
        assertEquals(0,api.submissions.get());
        assertEquals("04631993764370",current().gtin());
    }
    @Test void legacyGeneratedCheckpointCannotProveAFeedWasNeverSent() {
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",
                ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        assertFalse(workflow.resume(shop,current(),null));
        assertEquals(0,api.submissions.get());
        assertEquals(Status.GTIN_GENERATED,current().status());
    }
    @Test void unrelatedMissingFieldRejectionIsNotAutomaticallyResubmitted() {
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",
                ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        repository.updateProgress(1,11,Status.ERROR,"feed-existing",null,"required attribute missing",false);
        api.rejectExistingFeed=true;
        assertTrue(workflow.start(shop,current(),draft,null));
        assertEquals(0,api.submissions.get());
        assertEquals(0,api.allocations.get());
        assertEquals(Status.ERROR,current().status());
        assertEquals("feed-existing",current().feedId());
        assertEquals("04631993764370",current().gtin());
    }
    @Test void catalogKitCorrectionUsesTheSameGtinAndIsCheckpointedBeforeItsSingleRetry()throws Exception{
        var settings=new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture")).getSettings();var scope=ZnackRegistrationScope.fromSettings(settings);
        var auth=new ZnackAuthService(api,null){@Override public String trueApiToken(ZnackModels.Settings ignored){return "fixture-token";}};
        var catalog=new ZnackNationalCatalogService(api,auth,(payload,context)->new com.vncode.app.integration.znack.signature.CryptoProSigningResult(new byte[]{1},""),settings);
        workflow=RegistrationWorkflowTestFixtures.workflow(repository,ignored->new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token",catalog,scope),Runnable::run);
        api.rejectionEvidence=true;api.firstRejectedGoodId=91L;api.firstRejection="Карточка должна быть создана с типом Комплект";
        var fullDraft=new Draft("6204420000","6204",1,"Fixture","Brand",Map.of(1L,"Fixture"));
        assertTrue(startReviewed(fullDraft,true));assertEquals(Status.PUBLISHED,current().status(),()->current().errorMessage());assertEquals(1,api.allocations.get());assertEquals(2,api.submissions.get());
        assertTrue(JsonParser.parseString(repository.payload(1,11)).getAsJsonObject().get("is_kit").getAsBoolean());assertTrue(api.lastPayload.get("is_kit").getAsBoolean());
    }
    @Test void rejectedRetryPreservesItsExistingCardIdAndRefusesAnUnexpectedReplacement()throws Exception{
        api.firstRejection="Карточка должна быть создана с типом Комплект";api.firstRejectedGoodId=90L;api.rejectionEvidence=true;
        assertTrue(startReviewed(draft));assertEquals(Status.ERROR,current().status());assertEquals(90L,current().goodId());
        assertFalse(JsonParser.parseString(repository.payload(1,11)).getAsJsonObject().has("is_kit"));assertEquals(1,api.allocations.get());
        assertEquals(1,api.submissions.get(),"A different remote card identity must be blocked before a second POST");
    }
    @Test void reopeningAPreparedRetryPreservesTheReviewedServerCorrectedPayload()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE wb_product_cards SET subject_name='Костюмы спортивные' WHERE shop_id=1");}
        var repaired=new Draft("6112120000","6112",1,"Fixture","Fixture",draft.attributes(),Map.of(),false,true);
        repository.saveGenerated(1,current(),"04631993764370",repaired.tnved(),1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",repaired,"").toString());repository.updateProgress(1,11,Status.READY_TO_SUBMIT,null,null,null,null);
        assertTrue(startReviewed(repaired));assertEquals(Status.PUBLISHED,current().status());assertTrue(api.lastPayload.get("is_kit").getAsBoolean());assertEquals(0,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void aLegacyUnsentEditPersistsItsKnownGoodIdBeforeSubmitting()throws Exception{
        prepareReviewedRawDraft();api.preExistingRawDraft.addProperty("tnved","6204420000");
        repository.saveGenerated(1,current(),"04631993764370",draft.tnved(),1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        repository.updateProgress(1,11,Status.READY_TO_SUBMIT,null,91L,null,null);
        assertFalse(JsonParser.parseString(repository.payload(1,11)).getAsJsonObject().has("good_id"));
        assertTrue(workflow.resume(shop,current(),null));assertEquals(0,api.submissions.get());assertEquals(91L,current().goodId());
        // A fresh Catalog observation exposes the exact draft for a new seller review.
        repository.saveGenerated(1,current(),"04631993764370","6204420000",1,"Fixture","");
        repository.updateProgress(1,11,Status.PROCESSING,null,91L,"CATALOG_CARD_DRAFT",false);
        assertTrue(startReviewed(draft));assertEquals(91L,api.lastPayload.get("good_id").getAsLong());
        assertEquals(91L,JsonParser.parseString(repository.payload(1,11)).getAsJsonObject().get("good_id").getAsLong());assertEquals(0,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void rejectionDoesNotResetASignatureWhoseResultStillNeedsReadback(){
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());repository.updateProgress(1,11,Status.READY_TO_SIGN,"feed-existing",91L,null,false);
        var document=new ZnackNationalCatalogService.SigningDocument(91L,new byte[]{1});repository.prepareSignature(1,11,"04631993764370",document);assertTrue(repository.claimSignatureSend(1,11,document.sha256()));repository.updateProgress(1,11,Status.ERROR,"feed-existing",91L,"temporary error",false);api.rejectExistingFeed=true;
        assertTrue(workflow.start(shop,current(),draft,null));assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals("SENDING",repository.signingCheckpoint(1,11).state());assertEquals("feed-existing",current().feedId());
    }
    @Test void observerFailuresDoNotTurnSuccessfulRemoteWorkIntoAnError() throws Exception {
        reviewedDirect(draft,Runnable::run,(status,detail)->{throw new IllegalStateException("fixture observer");});
        assertEquals(Status.PUBLISHED,current().status());
        assertEquals(1,api.allocations.get());
        assertEquals(1,api.submissions.get());
    }
    @Test void lostFeedResultKeepsTheGtinAndDoesNotRepeatSubmission() throws Exception {
        api.feedTimeout=true;
        assertTrue(startReviewed(draft));
        Sku pending=current();
        assertEquals(Status.FEED_REVIEW_REQUIRED,pending.status());
        assertEquals("04631993764370",pending.gtin());
        assertFalse(workflow.start(shop,pending,draft,null));
        assertFalse(workflow.resume(shop,pending,null));
        assertFalse(workflow.requestNewGtin(shop,pending,draft,true,null));
        assertEquals(1,api.allocations.get());
        assertEquals(1,api.submissions.get());
    }
    @Test void anotherWorkflowCannotClaimAnAllocationThatIsCurrentlyRunning() throws Exception {
        var queued=new ArrayList<Runnable>();
        var waiting=RegistrationWorkflowTestFixtures.workflow(repository,
                ignored->{throw new IllegalStateException("fixture stopped before API access");},queued::add);
        Sku stale=current();
        assertTrue(waiting.start(shop,stale,draft,null));
        try {
            assertEquals(Status.CHECKING,current().status());
            assertFalse(workflow.requestNewGtin(shop,current(),draft,true,null));
            assertFalse(workflow.start(shop,stale,draft,null));
            assertEquals(0,api.allocations.get());
        } finally {
            // Let the queued claim release its in-process ownership without touching an API.
            queued.getFirst().run();
        }
    }
    @Test void changedConfigurationAfterAllocationStopsFeedAndPreservesOriginalBinding() throws Exception {
        var settingsRepository=new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture"));
        var original=new java.util.concurrent.atomic.AtomicReference<ZnackRegistrationScope>();
        var scoped=reviewedDirect(draft,Runnable::run,(status,detail)->{
            if(status==Status.GTIN_GENERATED){original.set(repository.scope(1,11));settingsRepository.saveSettings(ZnackRegistrationScopeTest.settings("7807083893","b".repeat(40)));}
        },true);
        assertEquals(1,api.allocations.get());assertEquals(0,api.submissions.get());
        assertNotNull(original.get());assertEquals(original.get(),repository.scope(1,11));
        assertTrue(Set.of(Status.READY_TO_SUBMIT,Status.PRE_SUBMIT_ERROR).contains(current().status()));
        assertFalse(current().hasFeed());assertEquals("04631993764370",current().gtin());
        assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->scoped.start(shop,current(),draft,null));
    }
    @Test void uncertainSignatureIsNeverReplayedAndReadbackCanResolveIt() {
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        repository.updateProgress(1,11,Status.READY_TO_SIGN,"feed-existing",91L,null,false);
        api.moderated=true;api.signTimeout=true;
        assertFalse(RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),false,null));assertEquals(0,api.signatures.get());
        assertTrue(RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),true,null));
        assertEquals(Status.SIGNING,current().status());assertEquals(1,api.signatures.get());
        assertEquals("RECONCILE_REQUIRED",repository.signingCheckpoint(1,11).state());
        assertTrue(RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),true,null));assertEquals(1,api.signatures.get());
        api.signatureAccepted=true;
        assertTrue(RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),true,null));
        assertEquals(Status.PUBLISHED,current().status());assertEquals(1,api.signatures.get());
        assertEquals("CONFIRMED",repository.signingCheckpoint(1,11).state());
    }
    @Test void acceptedSignatureRemainsPendingUntilIndependentExactReadback() {
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        repository.updateProgress(1,11,Status.READY_TO_SIGN,"feed-existing",91L,null,false);
        api.moderated=true;api.delayPublication=true;
        assertTrue(RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),true,null));
        assertEquals(1,api.signatures.get());assertEquals(Status.SIGNING,current().status());
        assertEquals("RECONCILE_REQUIRED",repository.signingCheckpoint(1,11).state());
        assertTrue(RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),true,null));assertEquals(1,api.signatures.get());
        api.signatureAccepted=true;
        assertTrue(RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),true,null));assertEquals(Status.PUBLISHED,current().status());
        assertEquals("CONFIRMED",repository.signingCheckpoint(1,11).state());assertEquals(1,api.signatures.get());
    }
    @Test void moderatedCardReleasesWorkerAndWaitsForExplicitSigning() throws Exception {
        api.moderated=true;
        assertTrue(startReviewed(draft));
        assertEquals(Status.READY_TO_SIGN,current().status());assertEquals(0,api.signatures.get());
        assertTrue(RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),true,null));
        assertEquals(1,api.signatures.get());assertEquals(Status.PUBLISHED,current().status());
    }
    @Test void changingTnvedGroupCannotReplaceWithoutReviewedIntent()throws Exception {
        var oldDraft=new Draft("6104",1,"Fixture","Brand",Map.of(1L,"Fixture"));
        repository.saveGenerated(1,current(),"04631993764363","6104",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764363",oldDraft,"").toString());
        repository.updateProgress(1,11,Status.PUBLISHED,"old-feed",90L,null,true);var old=current();
        assertThrows(RegistrationValidationException.class,()->workflow.start(shop,old,draft,null));
        assertEquals("04631993764363",current().gtin());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
        assertFalse(workflow.registerNew(shop,old,draft,false,null));
        assertFalse(workflow.registerNew(shop,old,draft,true,null));
        assertEquals("04631993764363",current().gtin());assertEquals("old-feed",current().feedId());
        assertTrue(repository.claimedGtins().contains("04631993764363"));assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    @Test void changingGroupOfRejectedCardRetainsTheOldCheckpointWithoutActivatingIt()throws Exception {
        var oldDraft=new Draft("6104",1,"Fixture","Brand",Map.of(1L,"Fixture"));
        repository.saveGenerated(1,current(),"04631993764363","6104",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764363",oldDraft,"").toString());repository.updateProgress(1,11,Status.ERROR,"old-feed",90L,"Rejected",false);
        api.moderated=true;assertThrows(RegistrationValidationException.class,()->workflow.start(shop,current(),draft,null));
        assertEquals(Status.ERROR,current().status());assertEquals("04631993764363",current().gtin());assertEquals("old-feed",current().feedId());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals("",repository.activeGtin(1,11));
        assertTrue(repository.claimedGtins().contains("04631993764363"));assertEquals(0,api.signatures.get());
    }
    @Test void interruptedSavedFeedIsRecoveredByReadOnlyMonitorWithoutRepeatingRegistration()throws Exception {
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());repository.updateProgress(1,11,Status.ERROR,"feed-existing",91L,"Registration interrupted",false);
        assertTrue(current().canResume());var result=new ZnackRegistrationMonitor(workflow,null).poll(shop,3);assertEquals(1,result.checked());assertEquals(1,result.published());assertEquals(Status.PUBLISHED,current().status());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals(0,api.signatures.get());
    }
    @Test void confirmedAutomaticSigningSurvivesReopeningAndOnlySignsItsExactCardOnce()throws Exception {
        api.pendingModeration=true;assertTrue(startReviewed(automaticDraft()));assertEquals(Status.PROCESSING,current().status());assertEquals(0,api.signatures.get());
        api.pendingModeration=false;api.moderated=true;var reopened=RegistrationWorkflowTestFixtures.workflow(repository,ignored->fixtureSession(),Runnable::run);var monitor=new ZnackRegistrationMonitor(reopened,null);
        assertEquals(1,monitor.poll(shop,3).published());assertEquals(1,api.signatures.get());assertEquals(Status.PUBLISHED,current().status());monitor.poll(shop,3);assertEquals(1,api.signatures.get());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void automaticSigningTimeoutIsReadBackAfterRestartWithoutSendingTheSignatureAgain()throws Exception {
        api.pendingModeration=true;startReviewed(automaticDraft());api.pendingModeration=false;api.moderated=true;api.signTimeout=true;
        var monitor=new ZnackRegistrationMonitor(RegistrationWorkflowTestFixtures.workflow(repository,ignored->fixtureSession(),Runnable::run),null);monitor.poll(shop,3);assertEquals(Status.SIGNING,current().status());assertEquals(1,api.signatures.get());monitor.poll(shop,3);assertEquals(1,api.signatures.get());api.signatureAccepted=true;assertEquals(1,monitor.poll(shop,3).published());assertEquals(1,api.signatures.get());
    }
    @Test void backgroundMonitorReadsExistingFeedWithoutAllocatingSubmittingOrSigningAgain()throws Exception {
        api.moderated=true;assertTrue(startReviewed(draft));
        var monitor=new ZnackRegistrationMonitor(workflow,null);var waiting=monitor.poll(shop,3);
        assertEquals(1,waiting.checked());assertEquals(0,waiting.published());assertFalse(waiting.pending());assertEquals(0,api.signatures.get());
        api.signatureAccepted=true;var done=monitor.poll(shop,3);assertEquals(1,done.published());assertEquals(Status.PUBLISHED,current().status());
        assertEquals(0,api.signatures.get());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void savedUnsentReviewedCardContinuesAfterRestartWithoutAnotherAllocation()throws Exception{
        {var state=Status.READY_TO_SUBMIT;
            repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
            repository.updateProgress(1,11,state,null,null,null,false);
            var before=api.submissions.get();var workers=new ArrayDeque<Runnable>();
            assertTrue(startReviewed(draft,false,workers::add));String run=reviewedBatch.latest(shop).runId();
            assertEquals(0,api.submissions.get());assertTrue(reviewedBatch.pause(shop));workers.removeFirst().run();
            var restarted=new ZnackRegistrationBatchService(repository,reviewedWorkflow,(owner,sku,sign)->reviewedFrozen,Runnable::run,true);
            assertTrue(restarted.resumePaused(shop));assertEquals(run,restarted.latest(shop).runId());
            assertEquals(before+1,api.submissions.get());assertEquals(0,api.allocations.get());
            assertTrue(current().hasFeed());assertNotEquals(state,current().status());
        }
    }
    @Test void temporaryValidationReadFailureContinuesAutomaticallyWithoutASecondCreateClick()throws Exception{
        prepareReviewedRawDraft();api.preExistingRawDraft.addProperty("tnved","6204420000");
        repository.saveGenerated(1,current(),"04631993764370","6204420000",1,"Fixture","");repository.updateProgress(1,11,Status.PROCESSING,null,91L,"CATALOG_CARD_DRAFT",false);
        var workers=new ArrayDeque<Runnable>();assertTrue(startReviewed(draft,false,workers::add));
        var payload=ZnackNationalCatalogService.buildPayload("04631993764370",reviewedFrozen,"");payload.addProperty("good_id",91L);payload=ZnackNationalCatalogService.reviewedDraftPayload(shop,payload);
        repository.saveReadyToSubmitGenerated(1,current(),"04631993764370",reviewedFrozen.tnved(),1,"Fixture",payload.toString());repository.updateProgress(1,11,Status.READY_TO_SUBMIT,null,91L,null,false);
        api.temporaryCardReads=1;workers.removeFirst().run();assertEquals(Status.READY_TO_SUBMIT,current().status());assertEquals(0,api.submissions.get());
        assertTrue(new com.vncode.app.features.sync.SyncStreamStore().ensure(shop,"ZNACK",java.time.Instant.now()).requested(),"Known-unsent work must wake the worker instead of waiting for its 15-minute interval");
        String run=reviewedBatch.latest(shop).runId();try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_registration_batches SET retry_at=NULL WHERE id=?")){p.setString(1,run);p.executeUpdate();}
        assertTrue(reviewedBatch.resumePending(shop));workers.removeFirst().run();assertEquals(1,api.submissions.get());assertEquals(0,api.allocations.get());assertEquals("04631993764370",current().gtin());assertEquals(91L,current().goodId());assertEquals(Status.PROCESSING,current().status());
    }
    @Test void temporaryCard404AfterCompletedFeedKeepsTheOriginalRequestAndNeverResendsIt()throws Exception{
        api.completedWithoutCard=true;api.cardHttp404=true;assertTrue(startReviewed(draft));
        assertEquals(Status.PROCESSING,current().status());assertEquals(1,api.submissions.get());
        new ZnackRegistrationMonitor(workflow,null).poll(shop,3);assertEquals(Status.PROCESSING,current().status());assertEquals(1,api.submissions.get());
        api.cardHttp404=false;api.completedWithoutCard=false;
        assertEquals(1,new ZnackRegistrationMonitor(workflow,null).poll(shop,3).published());assertEquals(1,api.submissions.get());
    }
    @Test void aQueuedContinuationKeepsTheFastPollingLoopActiveUntilItsWorkerStarts()throws Exception{
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());repository.updateProgress(1,11,Status.READY_TO_SUBMIT,null,null,null,false);
        var queued=new java.util.ArrayList<Runnable>();assertTrue(startReviewed(draft,false,queued::add));
        try{
            var result=new ZnackRegistrationMonitor(reviewedWorkflow,null).poll(shop,3);
            assertTrue(result.pending(),"A queued worker must not fall back to the 15-minute idle sync interval");assertEquals(0,api.submissions.get());
        }finally{queued.forEach(Runnable::run);}
        assertEquals(1,api.submissions.get());assertEquals(0,api.allocations.get());
    }
    @Test void monitorNeverResendsAnAmbiguousFeedSubmission()throws Exception{
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        repository.updateProgress(1,11,Status.FEED_REVIEW_REQUIRED,null,null,"uncertain send",false);
        assertEquals(0,new ZnackRegistrationMonitor(workflow,null).poll(shop,3).checked());
        assertEquals(0,api.submissions.get());assertEquals(0,api.allocations.get());
    }
    @Test void signingFrozenXmlRequiresFreshRemoteEligibilityAndExactDocument() {
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        repository.updateProgress(1,11,Status.READY_TO_SIGN,"feed-existing",91L,null,false);
        api.moderated=true;api.changeSigningDocument=true;
        assertTrue(RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),true,null));
        assertEquals(0,api.signatures.get());assertEquals(Status.READY_TO_SIGN,current().status());
        assertNull(repository.signingCheckpoint(1,11),"A document changed before POST; the exact signature was never sent");
    }
    @Test void newCardCannotReplacePublishedCardWithBareConfirmation() {
        repository.saveGenerated(1,current(),"04631993764363","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764363",draft,"").toString());
        repository.updateProgress(1,11,Status.PUBLISHED,"old-feed",90L,null,true);
        Sku old=current();assertFalse(workflow.registerNew(shop,old,draft,false,null));assertEquals(0,api.allocations.get());
        assertFalse(workflow.registerNew(shop,old,draft,true,null));assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
        assertEquals("04631993764363",current().gtin());assertEquals("old-feed",current().feedId());assertEquals(Status.PUBLISHED,current().status());
    }
    @Test void candidateActivationRejectsAnotherCatalogIdentityAndAChangedRealWbVariant() {
        prepareActivationCandidate();var reads=new AtomicInteger();var card=activationWbCard();
        var adapter=new com.vncode.app.integration.wb.WbGtinAdapter(shop,(path,body,mutation)->{
            assertFalse(mutation);assertEquals("content/v2/get/cards/list",path);reads.incrementAndGet();
            var result=new JsonObject();var cards=new JsonArray();cards.add(card.deepCopy());result.add("cards",cards);return result;
        });
        var errors=new ArrayList<String>();
        var completing=RegistrationWorkflowTestFixtures.workflow(repository,ignored->{throw new AssertionError("No Catalog mutation during activation");},Runnable::run,true,
                (s,n,v,g)->ZnackCardRegistrationWorkflow.candidatePresent(s,n,v,g,adapter));
        saveActivationObservation("92");
        assertTrue(completing.completeReplacement(shop,current(),true,(status,error)->errors.add(error)));
        assertEquals(0,reads.get());assertTrue(errors.getLast().contains("CATALOG_IDENTITY_CHANGED"));
        saveActivationObservation("91");
        card.getAsJsonArray("characteristics").get(0).getAsJsonObject().getAsJsonArray("value").set(0,new JsonPrimitive("Red"));
        assertTrue(completing.completeReplacement(shop,current(),true,(status,error)->errors.add(error)));
        assertTrue(errors.getLast().contains("GTIN_VARIANT_IDENTITY_UNVERIFIED"));
        card.getAsJsonArray("characteristics").get(0).getAsJsonObject().getAsJsonArray("value").set(0,new JsonPrimitive("Black"));
        card.getAsJsonArray("sizes").get(0).getAsJsonObject().addProperty("techSize","L");
        assertTrue(completing.completeReplacement(shop,current(),true,(status,error)->errors.add(error)));
        assertTrue(errors.getLast().contains("GTIN_VARIANT_IDENTITY_UNVERIFIED"));
        assertEquals("04631993764363",repository.activeGtin(1,11));assertTrue(repository.hasPendingReplacement(1,11));
        assertEquals("04631993764370",current().gtin());assertEquals(91L,current().goodId());
    }
    @Test void candidateActivationUsesTheExactPublishedCardAndActualWbVariant() {
        prepareActivationCandidate();saveActivationObservation("91");var reads=new AtomicInteger();var card=activationWbCard();
        var adapter=new com.vncode.app.integration.wb.WbGtinAdapter(shop,(path,body,mutation)->{
            assertFalse(mutation);assertEquals("content/v2/get/cards/list",path);reads.incrementAndGet();
            var result=new JsonObject();var cards=new JsonArray();cards.add(card.deepCopy());result.add("cards",cards);return result;
        });
        var errors=new ArrayList<String>();
        var completing=RegistrationWorkflowTestFixtures.workflow(repository,ignored->{throw new AssertionError("No Catalog mutation during activation");},Runnable::run,true,
                (s,n,v,g)->ZnackCardRegistrationWorkflow.candidatePresent(s,n,v,g,adapter));
        assertTrue(completing.completeReplacement(shop,current(),true,(status,error)->errors.add(error)));
        assertEquals(List.of(""),errors);assertTrue(reads.get()>0);assertEquals("04631993764370",repository.activeGtin(1,11));
        assertFalse(repository.hasPendingReplacement(1,11));
    }
    private void prepareActivationCandidate(){
        repository.saveGenerated(1,current(),"04631993764363","6204",1,"Fixture","{}");
        repository.updateProgress(1,11,Status.PUBLISHED,"old-feed",90L,null,true);assertTrue(repository.claimReplacement(1,current()));
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture","{}");repository.updateProgress(1,11,Status.PUBLISHED,"new-feed",91L,null,false);
    }
    private void saveActivationObservation(String id){
        var details=new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@catalog_id",List.of(id),"@catalog_status",List.of("published"),"tnved",List.of("6204420000")),List.of(),List.of());
        new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(1,shop.getMarketplace(),List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin("04631993764370","PANTS","Black","XL",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(shop),java.time.Instant.now(),details)));
    }
    private static JsonObject activationWbCard(){
        return JsonParser.parseString("{\"nmID\":101,\"vendorCode\":\"PANTS\",\"dimensions\":{\"length\":1,\"width\":2,\"height\":3,\"weightBrutto\":0.1},\"characteristics\":[{\"id\":99,\"name\":\"Цвет\",\"value\":[\"Black\"]},{\"id\":15000001,\"name\":\"ТН ВЭД\",\"value\":[\"6204420000\"]}],\"sizes\":[{\"chrtID\":11,\"techSize\":\"XL\",\"skus\":[\"04631993764370\"]}]}").getAsJsonObject();
    }
    @Test void largeReadOnlyReviewDoesNotQueueOrGrantConsentForAlreadyPublishedCards()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()) {
            for(int i=1;i<=300;i++) {
                long nm=1000+i,chrt=2000+i;String gtin=syntheticGtin(i),feed="bulk-"+i;
                s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,"+nm+",'Fixture','Fixture','fixture')");
                s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,"+chrt+","+nm+",'XL')");
                api.bulkGtins.put(feed,gtin);api.bulkGoodIds.put(feed,nm);
                try(var insert=c.prepareStatement("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,good_id,feed_id,payload_json,status,created_at,updated_at) VALUES(1,?,?,?,?,?,?,'READY_TO_SIGN','fixture','fixture')")) {
                    insert.setLong(1,chrt);insert.setLong(2,nm);insert.setString(3,gtin);insert.setLong(4,nm);insert.setString(5,feed);insert.setString(6,ZnackNationalCatalogService.buildPayload(gtin,draft,"").toString());insert.executeUpdate();
                }
            }
        }
        var queued=new ArrayList<Runnable>();var sessions=new AtomicInteger();
        var bulk=RegistrationWorkflowTestFixtures.workflow(repository,ignored->{sessions.incrementAndGet();return fixtureSession();},queued::add);
        var frozen=repository.search(new SearchCriteria(1,"",List.of(),"READY_TO_SIGN",1000,0));assertEquals(300,frozen.size());
        var review=bulk.prepareApprovedSigning(shop,frozen);assertTrue(review.cards().isEmpty());
        assertFalse(bulk.signApprovedReviewed(shop,review,null));assertTrue(queued.isEmpty());assertFalse(ZnackCardRegistrationWorkflow.hasActiveJobs());
        assertEquals(1,sessions.get());assertEquals(300,repository.search(new SearchCriteria(1,"",List.of(),"READY_TO_SIGN",1000,0)).size());
        assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals(0,api.signatures.get());
    }
    private static String syntheticGtin(int index){
        String body=String.format("046319937%04d",index);
        for(int check=0;check<10;check++){String gtin=body+check;if(com.vncode.app.features.gtinsync.RegisteredGtinValidator.isValid(gtin))return gtin;}
        throw new AssertionError("Could not create a valid fixture GTIN");
    }
    @Test void unknownExistingCatalogReadBlocksAllocationAndLeavesSellerBarcodeUntouched()throws Exception{
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,11,'seller-barcode')");}
        var checked=new AtomicInteger();
        var guarded=new ZnackCardRegistrationWorkflow(repository,ignored->fixtureSession(),Runnable::run,false,(a,b,c,d)->false,
                ()->new RegistrationExistingCards.LookupContext((owner,session)->{checked.incrementAndGet();throw new java.net.SocketTimeoutException("Catalog unknown");},(session,gtin,id)->{throw new AssertionError();},(owner,session)->"fixture"));
        var before=current().barcodes();assertTrue(guarded.start(shop,current(),draft,null));
        assertEquals(1,checked.get());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals(before,current().barcodes());
        assertTrue(current().gtin()==null||current().gtin().isBlank());
    }
    @Test void verifiedExistingCardIsAdoptedWithoutAllocationFeedOrInventedApprovedPayload()throws Exception{
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,101,14177449,'Цвет','[\"черный\"]')");}
        var data=new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("article",List.of("PANTS"),"color",List.of("черный"),"size",List.of("XL"),"tnved",List.of("6204"),"@catalog_id",List.of("91")),List.of(),List.of());
        var candidate=new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin("04631993764370","PANTS","черный","XL",true,true,"fixture",java.time.Instant.now(),data);
        var proof=new ZnackNationalCatalogService.ObservedCard(91L,candidate.gtin(),"published",new ZnackNationalCatalogService.FeedProgress("Signed",91L,List.of()),data,java.time.Instant.now());
        var checks=new AtomicInteger();
        var guarded=new ZnackCardRegistrationWorkflow(repository,ignored->fixtureSession(),Runnable::run,false,(a,b,c,d)->false,
                ()->new RegistrationExistingCards.LookupContext((owner,session)->List.of(candidate),(session,gtin,id)->{checks.incrementAndGet();return proof;},(owner,session)->"fixture"));
        assertTrue(guarded.start(shop,current(),draft,null));assertEquals(1,checks.get());
        assertEquals(Status.PUBLISHED,current().status());assertEquals(candidate.gtin(),current().gtin());assertEquals(91L,current().goodId());
        assertFalse(current().hasFeed());assertEquals("",repository.payload(1,11));assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
        assertTrue(guarded.verifySubmissionForBatch(shop,current()));assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    @Test void transientReadOfAnAdoptedCardPreservesItsRemoteIdentityWithoutAnUnsentLabel()throws Exception{
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture","");
        repository.updateProgress(1,11,Status.PROCESSING,null,91L,"CATALOG_CARD_IN_MODERATION",false);
        api.temporaryCardReads=1;
        assertThrows(java.net.SocketTimeoutException.class,()->workflow.verifySubmissionForBatch(shop,current()));
        assertEquals(Status.PROCESSING,current().status());assertEquals("CATALOG_READBACK_RETRY_PENDING",current().errorMessage());
        assertEquals("04631993764370",current().gtin());assertEquals(91L,current().goodId());assertFalse(current().hasFeed());
        assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
        api.cardMissing=true;assertThrows(RegistrationValidationException.class,()->workflow.verifySubmissionForBatch(shop,current()));
        assertEquals(Status.ERROR,current().status());assertEquals("04631993764370",current().gtin());assertEquals(91L,current().goodId());
        assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    @Test void currentMandatoryFieldMustBeCompleteBeforeAnyGtinIsAllocated(){
        var incomplete=new Draft("6204",1,"Fixture","Brand",Map.of());assertTrue(workflow.start(shop,current(),incomplete,null));
        assertEquals(Status.PREFLIGHT_ERROR,current().status());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
        assertTrue(current().errorMessage().contains("1"));
    }
    @Test void manualSigningShowsReadOnlyExactXmlBeforeGrantAndRejectsAChangedRevision()throws Exception{
        api.moderated=true;startReviewed(draft);
        var key=new com.vncode.app.features.products.ProductKey(1,shop.getMarketplace(),"101","11");
        var review=workflow.prepareApprovedSigning(shop,List.of(current()));
        assertEquals(1,review.cards().size());assertEquals(91L,review.cards().getFirst().goodId());
        assertEquals("<good>frozen</good>",new String(review.cards().getFirst().document().xml(),java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(RegistrationSigningConsent.allowed(shop,key,current().gtin()));assertNull(repository.signingCheckpoint(1,11));assertEquals(0,api.signatures.get());
        api.changeSigningDocument=true;assertTrue(workflow.signApprovedReviewed(shop,review,null));
        assertEquals(0,api.signatures.get());assertEquals(Status.READY_TO_SIGN,current().status());assertTrue(current().errorMessage().contains("SIGNING_DOCUMENT_CHANGED"));
        assertFalse(RegistrationSigningConsent.allowed(shop,key,current().gtin()));
    }
    @Test void aLegacyEnabledFlagDoesNotKeepAnUnapprovedReadyCardPermanentlyPending()throws Exception{
        api.moderated=true;startReviewed(draft);
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_registration_signing_consents(shop_id,marketplace,product_id,variant_id,gtin,owner,enabled,updated_at) VALUES(1,'WILDBERRIES','101','11',?,?,1,'fixture')")){
            p.setString(1,current().gtin());p.setString(2,com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop));p.executeUpdate();
        }
        var result=new ZnackRegistrationMonitor(workflow,null).poll(shop,30);
        assertEquals(Status.READY_TO_SIGN,current().status());assertFalse(result.pending());assertEquals(0,api.signatures.get());
    }
    @Test void oldCountOnlyConfirmationCannotGrantSigningConsent()throws Exception{
        api.moderated=true;startReviewed(draft);
        assertFalse(workflow.signApproved(shop,List.of(current()),true,null));assertEquals(0,api.signatures.get());
        assertFalse(RegistrationSigningConsent.allowed(shop,new com.vncode.app.features.products.ProductKey(1,shop.getMarketplace(),"101","11"),current().gtin()));
    }
    @Test void reviewedGenuineDraftUpdatesItsExistingIdentityWithoutAllocationSigningOrMarketplaceGrant()throws Exception{
        prepareReviewedRawDraft();api.preExistingRawDraft.addProperty("tnved","6204420000");
        // A CREATE review cannot silently become permission to edit an existing card.
        assertTrue(startReviewed(draft));assertEquals(0,api.submissions.get());assertFalse(current().hasIdentity());
        // Model the Catalog sync exposing the exact draft for an explicit second review.
        repository.saveGenerated(1,current(),"04631993764370","6204420000",1,"Fixture","");
        repository.updateProgress(1,11,Status.PROCESSING,null,91L,"CATALOG_CARD_DRAFT",false);
        assertTrue(startReviewed(draft.withAutoSign(true)));
        assertDraftUpdateOnly();assertTrue(current().hasFeed());assertEquals("feed-new",current().feedId());assertEquals(Status.PROCESSING,current().status());
    }
    @Test void adoptedNativeDraftWithoutFeedCanBeReviewedAndSubmittedOnceWithItsExistingGtin()throws Exception{
        prepareReviewedRawDraft();api.preExistingRawDraft.addProperty("tnved","6204420000");repository.saveGenerated(1,current(),"04631993764370","6204420000",1,"Fixture","");
        repository.updateProgress(1,11,Status.PROCESSING,null,91L,"CATALOG_CARD_DRAFT",false);assertTrue(current().canReviewExistingDraft());
        assertTrue(startReviewed(draft.withAutoSign(true)));
        assertDraftUpdateOnly();assertTrue(current().hasFeed());assertEquals(Status.PROCESSING,current().status());
    }
    @Test void reviewedDraftRejectsDifferentCatalogOwnerBeforePosting()throws Exception{
        prepareReviewedRawDraft();api.preExistingRawDraft.addProperty("tnved","6204420000");api.preExistingRawDraft.addProperty("owner_inn","9999999999");
        repository.saveGenerated(1,current(),"04631993764370","6204420000",1,"Fixture","");repository.updateProgress(1,11,Status.PROCESSING,null,91L,"CATALOG_CARD_DRAFT",false);
        assertTrue(startReviewed(draft));assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals(91L,current().goodId());
    }
    @Test void reviewedDraftWithoutOwnerOrIndexProofCannotPost()throws Exception{
        prepareReviewedRawDraft();api.preExistingRawDraft.addProperty("tnved","6204420000");api.preExistingRawDraft.remove("owner_inn");
        repository.saveGenerated(1,current(),"04631993764370","6204420000",1,"Fixture","");repository.updateProgress(1,11,Status.PROCESSING,null,91L,"CATALOG_CARD_DRAFT",false);
        assertTrue(startReviewed(draft));assertTrue(api.ownerIndexReads>0);assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals(91L,current().goodId());
    }
    @Test void reviewedDraftCanUseExactParticipantIndexProofWhenOwnerIsOmitted()throws Exception{
        prepareReviewedRawDraft();api.preExistingRawDraft.addProperty("tnved","6204420000");api.preExistingRawDraft.remove("owner_inn");api.ownerIndexContains=true;
        repository.saveGenerated(1,current(),"04631993764370","6204420000",1,"Fixture","");repository.updateProgress(1,11,Status.PROCESSING,null,91L,"CATALOG_CARD_DRAFT",false);
        assertTrue(startReviewed(draft));assertTrue(api.ownerIndexReads>0);assertDraftUpdateOnly();
    }
    @Test void createConsentCannotEditAnUnexpectedExistingDraft()throws Exception{
        prepareReviewedRawDraft();api.preExistingRawDraft.addProperty("tnved","6204420000");
        assertTrue(startReviewed(draft));
        assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertFalse(current().hasIdentity());
        assertTrue(current().errorMessage().contains("ZNACK_SOURCE_REVIEW_REQUIRED"));
    }
    @Test void changedDraftIdentityAfterReviewCannotBeAdoptedOrSubmitted()throws Exception{
        prepareReviewedRawDraft();api.preExistingRawDraft.addProperty("tnved","6204420000");
        repository.saveGenerated(1,current(),"04631993764370","6204420000",1,"Fixture","");
        repository.updateProgress(1,11,Status.PROCESSING,null,91L,"CATALOG_CARD_DRAFT",false);
        var workers=new ArrayDeque<Runnable>();assertTrue(startReviewed(draft,false,workers::add));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE znack_card_registrations SET good_id=92 WHERE shop_id=1 AND chrt_id=11");}
        api.preExistingRawDraft.addProperty("good_id",92);workers.removeFirst().run();
        assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals(92L,current().goodId());assertFalse(current().hasFeed());
    }
    @Test void uncertainDraftSubmissionAndStillDraftReadbackCannotReplayOrGrantAnotherAction()throws Exception{
        prepareReviewedRawDraft();api.preExistingRawDraft.addProperty("tnved","6204420000");repository.saveGenerated(1,current(),"04631993764370","6204420000",1,"Fixture","");
        repository.updateProgress(1,11,Status.PROCESSING,null,91L,"CATALOG_CARD_DRAFT",false);api.feedTimeout=true;
        assertTrue(startReviewed(draft.withAutoSign(true)));var reviewed=reviewedWorkflow;
        assertEquals(Status.FEED_REVIEW_REQUIRED,current().status());assertFalse(current().hasFeed());assertDraftUpdateOnly();
        String frozen=repository.payload(1,11);assertTrue(ZnackNationalCatalogService.hasReviewedDraft(JsonParser.parseString(frozen).getAsJsonObject()));
        assertTrue(reviewed.verifySubmissionForBatch(shop,current()));assertEquals(Status.FEED_REVIEW_REQUIRED,current().status());assertEquals(frozen,repository.payload(1,11));
        assertFalse(reviewed.resumeSubmissionOnly(shop,current(),true,null));assertFalse(reviewed.start(shop,current(),draft,null));assertEquals(1,api.submissions.get());assertEquals(0,api.allocations.get());assertEquals(0,api.signatures.get());
    }
    @Test void restartedKnownUnsentReviewedDraftDispatchesOnlyTheFrozenPayloadWithoutAutoGrants()throws Exception{
        prepareReviewedRawDraft();api.preExistingRawDraft.addProperty("tnved","6204420000");
        repository.saveGenerated(1,current(),"04631993764370","6204420000",1,"Fixture","");
        repository.updateProgress(1,11,Status.PROCESSING,null,91L,"CATALOG_CARD_DRAFT",false);
        var approved=new Draft("6204",1,"Frozen reviewed name","Brand",Map.of(1L,"Frozen reviewed value"));
        var workers=new ArrayDeque<Runnable>();assertTrue(startReviewed(approved,false,workers::add));
        var body=ZnackNationalCatalogService.buildPayload("04631993764370",reviewedFrozen,"");body.addProperty("good_id",91L);var checkpoint=ZnackNationalCatalogService.reviewedDraftPayload(shop,body);
        repository.saveReadyToSubmitGenerated(1,current(),"04631993764370",reviewedFrozen.tnved(),1,approved.goodName(),checkpoint.toString());repository.updateProgress(1,11,Status.READY_TO_SUBMIT,null,91L,null,false);
        String frozen=repository.payload(1,11);assertTrue(current().canResume());String run=reviewedBatch.latest(shop).runId();
        assertTrue(reviewedBatch.pause(shop));workers.removeFirst().run();
        var restarted=new ZnackRegistrationBatchService(repository,reviewedWorkflow,(owner,sku,sign)->reviewedFrozen,Runnable::run,true);assertTrue(restarted.resumePaused(shop));assertEquals(run,restarted.latest(shop).runId());assertDraftUpdateOnly();
        assertEquals(ZnackNationalCatalogService.normalizedFeed(body),api.lastPayload);assertEquals(frozen,repository.payload(1,11));assertEquals(Status.PROCESSING,current().status());
    }
    private void prepareReviewedRawDraft()throws Exception{
        com.vncode.app.features.gtinsync.GtinWriteTestFixture.saveSettings(shop,com.vncode.app.features.gtinsync.GtinWriteTestFixture.CERTIFICATE,ZnackModels.PRODUCTION_TRUE_API);
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,101,14177449,'Цвет','[\"черный\"]')");}
        api.preExistingRawDraft=JsonParser.parseString("""
            {"good_id":91,"gtin":"04631993764370","good_status":"draft","good_signed":false,
             "good_detailed_status":["draft"],"article":"PANTS","color":"черный","size":"XL","tnved":"6204",
             "good_name":"Fixture","brand":"Brand","good_attrs":[{"attr_id":1,"attr_value":"Fixture"}],
             "identified_by":[{"type":"gtin","value":"04631993764370","level":"trade-unit"}]}
            """).getAsJsonObject();
        api.preExistingRawDraft.addProperty("owner_inn",ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture")).getSettings()).inn());
    }
    private ZnackCardRegistrationWorkflow reviewedDraftWorkflow(){
        return new ZnackCardRegistrationWorkflow(repository,owner->{
            var settings=new ZnackRepository(new ZnackModels.ShopContext(owner.getId(),owner.getName())).getSettings();
            var auth=new ZnackAuthService(api,null){@Override public String trueApiToken(ZnackModels.Settings ignored){return "fixture-token";}};
            return new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token",new ZnackNationalCatalogService(api,auth,
                    (payload,context)->{throw new AssertionError("An ordinary draft review is not permission to sign");},settings));
        },Runnable::run,false,(owner,nm,variant,gtin)->{throw new AssertionError("Draft review cannot write marketplace data");},()->new RegistrationExistingCards.LookupContext(
                (owner,session)->{var data=CatalogProductDataParser.parse(api.preExistingRawDraft);return List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin("04631993764370","PANTS","черный","XL",true,false,"fixture",java.time.Instant.now(),data));},
                (session,gtin,id)->session.catalog().observedCardStatus(session.token(),gtin,id),(owner,session)->"fixture"));
    }
    private void assertDraftUpdateOnly(){
        assertEquals(0,api.allocations.get());assertEquals(0,api.signatures.get());assertEquals(1,api.submissions.get());assertEquals(91L,current().goodId());assertEquals("04631993764370",current().gtin());
        assertNotNull(api.lastPayload);assertEquals(91L,api.lastPayload.get("good_id").getAsLong());assertEquals("04631993764370",api.lastPayload.get("gtin").getAsString());assertEquals(1,api.lastPayload.get("moderation").getAsInt());
        assertTrue(api.lastPayload.keySet().stream().noneMatch(key->key.startsWith("_vncode")));var key=new com.vncode.app.features.products.ProductKey(1,shop.getMarketplace(),"101","11");
        assertFalse(RegistrationSigningConsent.allowed(shop,key,current().gtin()));assertFalse(RegistrationMarketplaceConsent.allowed(shop,key,current().gtin()));
    }
    Draft automaticDraft(){return new Draft("6204420000","6204420000",1,"Fixture","Brand",Map.of(1L,"Fixture"),Map.of(),true);}
    ZnackCardRegistrationWorkflow.CatalogSession fixtureSession() {
        var auth=new ZnackAuthService(api,null){@Override public String trueApiToken(ZnackModels.Settings settings){return "fixture-token";}};
        return new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token",new ZnackNationalCatalogService(api,auth,
                (payload,context)->new com.vncode.app.integration.znack.signature.CryptoProSigningResult(new byte[]{1},""),ZnackModels.Settings.empty()));
    }
    void pending(String status) throws Exception {
        try(var c=Database.getConnection();var s=c.prepareStatement("""
                INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,status,created_at,updated_at)
                VALUES(1,11,101,?,'2026-10-06T00:00:00Z','2026-10-06T00:00:00Z')
                """)){s.setString(1,status);s.executeUpdate();}
    }
    @Test void rejectedCardCannotBeReplacedByBareConfirmation()throws Exception{
        failedCardForReplacement();api.rejectExistingFeed=true;
        assertFalse(workflow.registerNew(shop,current(),draft,true,null));assertEquals("04631993764363",current().gtin());assertEquals(Status.ERROR,current().status());
        assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT gtin,feed_id FROM znack_card_registration_history WHERE shop_id=1 AND chrt_id=11");var r=p.executeQuery()){
            assertFalse(r.next(),"An unreviewed replacement must not archive the original card");
        }
    }
    @Test void aProcessingFeedWithItemErrorsCannotConsumeANewGtinThroughRecovery(){
        failedCardForReplacement();api.rejectExistingFeed=true;api.existingFeedStatus="Processing";
        assertFalse(workflow.registerNew(shop,current(),draft,true,null));assertEquals("04631993764363",current().gtin());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    private void failedCardForReplacement(){
        api.existingFeedGtin="04631993764363";
        repository.saveGenerated(1,current(),"04631993764363","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764363",draft,"").toString());
        repository.updateProgress(1,11,Status.ERROR,"feed-existing",91L,"Rejected",false);
    }
    @Test void acceptedFeedSurvivesRateLimitWithoutAllocatingOrSubmittingAgain()throws Exception{temporaryReadback(429);}
    @Test void acceptedFeedSurvivesNetworkTimeoutWithoutAllocatingOrSubmittingAgain()throws Exception{temporaryReadback(-1);}
    private void temporaryReadback(int failure)throws Exception{
        api.statusReadFailure=failure;assertTrue(startReviewed(draft));
        assertEquals(Status.PROCESSING,current().status());assertEquals("CATALOG_READBACK_RETRY_PENDING",current().errorMessage());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
        api.statusReadFailure=0;assertTrue(workflow.refreshStatus(shop,current()));assertEquals(Status.PUBLISHED,current().status());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());
    }
    @Test void accessDeniedRemainsAnActionableErrorInsteadOfAnAutomaticReadRetry()throws Exception{
        api.statusReadFailure=403;assertTrue(startReviewed(draft));assertEquals(Status.ERROR,current().status());assertEquals(1,api.submissions.get());
    }
    @Test void preparedSessionWithoutReviewedIntentCannotAllocateOrSubmit()throws Exception{
        var prepared=fixtureSession();prepared.catalog().preflight(draft.tnved());
        var sending=RegistrationWorkflowTestFixtures.workflow(repository,ignored->{throw new AssertionError("Reuse this card's verified session");},Runnable::run);
        try(var lookup=RegistrationWorkflowTestFixtures.emptyLookup()){
            assertTrue(sending.submitOnly(shop,current(),draft,null,lookup,prepared));
        }
        assertInstanceOf(RegistrationValidationException.class,sending.consumeSubmissionFailure(shop,current()));
        assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertFalse(current().hasIdentity());
    }
    @Test void preparedBatchSessionCannotSendAfterItsCredentialGuardChanges()throws Exception{
        var prepared=fixtureSession();prepared.catalog().preflight(draft.tnved());api.invalidReadScope=true;
        try(var lookup=RegistrationWorkflowTestFixtures.emptyLookup()){
            assertTrue(workflow.submitOnly(shop,current(),draft,null,lookup,prepared));
        }
        assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
        assertInstanceOf(SecurityException.class,workflow.consumeSubmissionFailure(shop,current()));
    }
    class FixtureApi extends ZnackApiClient {
        AtomicInteger allocations=new AtomicInteger(),submissions=new AtomicInteger(),signatures=new AtomicInteger(),documents=new AtomicInteger(),countryReads=new AtomicInteger();
        boolean mandatoryCountry,staleCardContent;
        boolean allocationTimeout,feedTimeout,existingDraft,rejectExistingFeed,readRejected,rejectionEvidence;
        boolean pendingModeration,moderated,signTimeout,signatureAccepted,changeSigningDocument,delayPublication,laggedModeration;
        int statusReadFailure,temporaryCardReads,generatedReads,failGeneratedReadNumber,categoryReads,ownerIndexReads;
        boolean ownerIndexContains;
        boolean invalidReadScope;
        @Override public void assertCatalogReadScope(){if(invalidReadScope)throw new SecurityException("fixture credential changed");}
        boolean cardHttp404;
        boolean cardPublished=true,cardMissing,completedWithoutCard,signingDocumentMissing;
        Map<String,String> bulkGtins=new HashMap<>();
        Map<String,Long> bulkGoodIds=new HashMap<>();
        String existingFeedStatus="Rejected";
        String existingFeedGtin="04631993764370";
        String firstRejection;Long firstRejectedGoodId;JsonObject lastPayload,preExistingRawDraft;
        @Override public JsonElement generatedGtins(String base,String token)throws IOException {
            if(++generatedReads==failGeneratedReadNumber)throw new java.net.SocketTimeoutException("initial allocation read");
            return json(existingDraft?"{\"result\":{\"drafts\":[{\"gtin\":\"04631993764363\"}]}}":"{\"result\":{\"drafts\":[]}}");
        }
        @Override public JsonElement generateGtins(String base,String token,int quantity) throws IOException {
            assertEquals(Status.ALLOCATING_GTIN,current().status(),"Checkpoint must precede the remote allocation");
            allocations.incrementAndGet();
            if(allocationTimeout)throw new IOException("fixture allocation result lost");
            return json("{\"result\":{\"drafts\":[{\"gtin\":\"04631993764370\"}]}}");
        }
        @Override public JsonElement nationalCatalogCategories(String base,String token,String tnved) {
            categoryReads++;
            return json("{\"result\":[{\"cat_id\":1,\"cat_name\":\"Clothes\",\"category_active\":true}]}");
        }
        @Override public JsonElement nationalCatalogAttributes(String base,String token,long category) {
            var response=json("{\"result\":[{\"attr_id\":1,\"attr_name\":\"Fixture\",\"attr_field_type\":\"text\"}]}").getAsJsonObject();
            if(mandatoryCountry)response.getAsJsonArray("result").add(json("{\"attr_id\":2630,\"attr_name\":\"Страна производства\",\"attr_field_type\":\"text\",\"attr_preset_only\":true,\"attr_preset\":[]}"));
            return response;
        }
        @Override public JsonElement nationalCatalogAllAttributes(String base,String token,long category){return nationalCatalogAttributes(base,token,category);}
        @Override public JsonElement nationalCatalogCountries(String base,String token){countryReads.incrementAndGet();return json("{\"apiversion\":3,\"result\":{\"_etag\":\"fixture-country-etag\",\"_list\":[{\"country_iso\":\"RU\",\"country_name\":\"Россия\"},{\"country_iso\":\"CN\",\"country_name\":\"Китай\"}]}}");}
        @Override public JsonElement catalogVersions(String base,String token,String ownerInn,int offset){ownerIndexReads++;assertEquals(0,offset);return json(ownerIndexContains?"{\"result\":{\"total\":1,\"offset\":0,\"goods_count\":1,\"last_product_number\":1,\"goods\":[{\"good_id\":91}]}}":"{\"result\":{\"total\":0,\"offset\":0,\"goods_count\":0,\"last_product_number\":0,\"goods\":[]}}");}
        @Override public JsonElement submitNationalCatalogFeed(String base,String token,JsonElement payload) throws IOException {
            assertEquals(Status.FEED_SUBMITTING,current().status(),"Checkpoint must precede feed submission");
            if(rejectExistingFeed)assertTrue(readRejected,"The old feed must be confirmed rejected before resubmitting");
            lastPayload=payload.getAsJsonObject().deepCopy();submissions.incrementAndGet();
            if(feedTimeout)throw new IOException("fixture feed result lost");
            return json("{\"result\":{\"feed_id\":\"feed-new\"}}");
        }
        @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed)throws IOException {
            if(statusReadFailure==-1)throw new java.net.SocketTimeoutException("fixture readback timeout");
            if(statusReadFailure>0)throw new ZnackApiClient.ZnackApiException("fixture",statusReadFailure,"fixture status read unavailable");
            if(firstRejection!=null&&submissions.get()==1)return json("{\"result\":{\"feed_id\":\""+feed+"\",\"status\":\"Rejected\",\"item\":[{\"gtin\":\"04631993764370\",\"good_id\":"+firstRejectedGoodId+",\"status_code\":400,\"message\":\""+firstRejection+"\"}]}}");
            if(completedWithoutCard&&submissions.get()==1)return json("{\"result\":{\"status\":\"Done\",\"item\":[{\"gtin\":\"04631993764370\"}]}}");
            if(bulkGtins.containsKey(feed))return json("{\"result\":{\"status\":\"Signed\",\"item\":[{\"gtin\":\""+bulkGtins.get(feed)+"\",\"good_id\":"+bulkGoodIds.get(feed)+"}]}}");
            if(rejectExistingFeed && feed.equals("feed-existing")) {
                readRejected=true;
                return json("{\"result\":{\"status\":\""+existingFeedStatus+"\",\"item\":[{\"gtin\":\""+existingFeedGtin+"\",\"status_code\":400,\"message\":\"required attribute missing\"}]}}");
            }
            return json("{\"result\":{\"status\":\""+(pendingModeration?"Processing":laggedModeration||moderated && !signatureAccepted?"Moderated":"Signed")+"\",\"item\":[{\"gtin\":\"04631993764370\",\"good_id\":91}]}}");
        }
        @Override public JsonElement productCards(String base,String token,String gtins)throws IOException {
            if(temporaryCardReads-->0)throw new java.net.SocketTimeoutException("temporary card read");
            if(cardHttp404)throw new ZnackApiClient.ZnackApiException("fixture",404,"temporary card lookup");
            if(cardMissing||completedWithoutCard&&submissions.get()==1)return json("{\"result\":[]}");
            if(preExistingRawDraft!=null){var cards=new JsonArray();cards.add(preExistingRawDraft.deepCopy());var response=new JsonObject();response.add("result",cards);return response;}
            long id=91;
            for(var entry:bulkGtins.entrySet())if(entry.getValue().equals(gtins))id=bulkGoodIds.get(entry.getKey());
            boolean rejectedCard=rejectionEvidence&&firstRejection!=null&&submissions.get()==1;
            boolean published=cardPublished&&!pendingModeration&&(!moderated||signatureAccepted)&&!rejectedCard;
            String details=rejectedCard?"errors":published?"":moderated?"notsigned":pendingModeration?"moderation":"draft";
            JsonObject card=lastPayload==null?new JsonObject():lastPayload.deepCopy();
            if(lastPayload==null&&!repository.payload(1,11).isBlank())card=JsonParser.parseString(repository.payload(1,11)).getAsJsonObject();
            if(staleCardContent)card.addProperty("good_name","Older content");card.addProperty("good_signed",published);
            if(rejectedCard)card.addProperty("owner_inn",ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture")).getSettings()).inn());
            card.addProperty("gtin",gtins);card.addProperty("good_id",id);card.addProperty("good_status",published?"published":"draft");
            var states=new JsonArray();states.add(details);card.add("good_detailed_status",states);var cards=new JsonArray();cards.add(card);var result=new JsonObject();result.add("result",cards);return result;
        }
        @Override public JsonElement nationalCatalogSigningDocument(String base,String token,JsonObject body)throws IOException {
            if(signingDocumentMissing)throw new ZnackApiClient.ZnackApiException("fixture",404,"not found");
            int count=documents.incrementAndGet();return json("{\"result\":{\"xmls\":[{\"goodId\":91,\"xml\":\"<good>"+(changeSigningDocument && count>1?"changed":"frozen")+"</good>\"}]}}");
        }
        @Override public JsonElement signNationalCatalogProduct(String base,String token,JsonArray body)throws IOException {
            assertEquals(Status.SIGNING,current().status());assertEquals("SENDING",repository.signingCheckpoint(1,11).state());
            String xml=new String(Base64.getDecoder().decode(body.get(0).getAsJsonObject().get("base64Xml").getAsString()),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("<good>frozen</good>",xml);signatures.incrementAndGet();if(signTimeout)throw new IOException("fixture signature result lost");if(!delayPublication)signatureAccepted=true;
            return json("{\"result\":{\"signed\":[91]}}");
        }
        JsonElement json(String value){return JsonParser.parseString(value);}
    }
}
