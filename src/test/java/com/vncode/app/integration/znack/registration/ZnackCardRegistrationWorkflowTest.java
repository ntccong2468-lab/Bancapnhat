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
    @Test void manualDraftMissingRequiredCountryCannotAllocateAGtin()throws Exception{
        api.mandatoryCountry=true;
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,11,'seller-existing-barcode')");}
        var before=current().barcodes();
        assertTrue(workflow.start(shop,current(),draft,null));
        assertEquals(Status.PREFLIGHT_ERROR,current().status());assertTrue(current().errorMessage().contains("2630"));
        assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals(0,api.signatures.get());
        assertTrue(current().gtin()==null||current().gtin().isBlank());assertEquals(before,current().barcodes());
    }
    @Test void manualDraftWithAnUnverifiedCountryCannotAllocateAGtin(){
        api.mandatoryCountry=true;
        var invalid=new Draft("6204",1,"Fixture","Brand",Map.of(1L,"Fixture",2630L,"XX"));
        assertTrue(workflow.start(shop,current(),invalid,null));
        assertEquals(Status.PREFLIGHT_ERROR,current().status());assertTrue(current().errorMessage().contains("CATALOG_COUNTRY_DICTIONARY_INVALID"));
        assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals(0,api.signatures.get());
    }
    @Test void validManualCountryIsVerifiedOnceBeforeAllocationAndReusedForSubmission(){
        api.mandatoryCountry=true;api.moderated=true;
        var valid=new Draft("6204",1,"Fixture","Brand",Map.of(1L,"Fixture",2630L,"RU"));
        assertTrue(workflow.start(shop,current(),valid,null));assertEquals(Status.READY_TO_SIGN,current().status());
        assertEquals(1,api.countryReads.get());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
        assertTrue(api.lastPayload.getAsJsonArray("good_attrs").asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(attribute->attribute.get("attr_id").getAsLong()==2630L&&attribute.get("attr_value").getAsString().equals("RU")));
    }
    @Test void storedUnsentDraftMissingCurrentRequiredCountryKeepsItsIdentityWithoutSending(){
        String stored=ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString();
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",stored);
        repository.updateProgress(1,11,Status.READY_TO_SUBMIT,null,null,null,false);api.mandatoryCountry=true;
        assertTrue(workflow.resume(shop,current(),null));
        assertEquals(Status.PRE_SUBMIT_ERROR,current().status());assertEquals("04631993764370",current().gtin());
        assertFalse(current().hasFeed());assertEquals(stored,repository.payload(1,11));
        assertTrue(current().errorMessage().contains("2630"));assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    @Test void externallySignedCardWithLaggedFeedDoesNotSignAgain()throws Exception{
        api.moderated=true;workflow.start(shop,current(),draft,null);assertEquals(Status.READY_TO_SIGN,current().status());
        api.signatureAccepted=true;api.laggedModeration=true;
        assertTrue(workflow.refreshStatus(shop,current()));assertEquals(Status.PUBLISHED,current().status());
        assertEquals(0,api.signatures.get());assertEquals(0,api.documents.get());assertEquals(1,api.submissions.get());
    }
    @Test void definiteRejectionAfterASigningTimeoutShowsTheReasonWithoutReplaying()throws Exception{
        api.moderated=true;api.signTimeout=true;workflow.start(shop,current(),draft,null);
        RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),true,null);assertEquals(Status.SIGNING,current().status());
        api.firstRejection="Permit does not cover product";assertFalse(workflow.refreshStatus(shop,current()));
        assertEquals(Status.ERROR,current().status());assertTrue(current().errorMessage().contains("Permit does not cover product"));
        assertEquals(1,api.signatures.get());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void nextCardWaitsForWorkerCheckpointInsteadOfQueueAcknowledgement() throws Exception {
        var workers=new ArrayDeque<Runnable>();
        var queued=RegistrationWorkflowTestFixtures.workflow(repository,ignored->fixtureSession(),workers::add);
        api.pendingModeration=true;
        assertTrue(queued.start(shop,current(),draft,null));
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
        api.moderated=true;assertTrue(workflow.start(shop,current(),draft,null));assertEquals(Status.READY_TO_SIGN,current().status());
        assertTrue(RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),true,null));assertEquals(Status.PUBLISHED,current().status());
        PublishedCardGtinFixture.synchronize(shop,current().gtin(),"101","11","");
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(1,api.signatures.get());
    }
    @Test void signedRequestWithNoPublishedCardWaitsAndResumesWithoutAllocatingAgain()throws Exception {
        com.vncode.app.features.gtinsync.GtinWriteTestFixture.saveSettings(shop,"A".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        api.moderated=true;api.cardPublished=false;
        assertTrue(workflow.start(shop,current(),draft,null));assertEquals(Status.READY_TO_SIGN,current().status());
        assertTrue(RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),true,null));
        assertNotEquals(Status.PUBLISHED,current().status());assertFalse(current().wbUpdated());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(1,api.signatures.get());
        assertFalse(workflow.refreshStatus(shop,current()));assertNotEquals(Status.PUBLISHED,current().status());
        api.cardPublished=true;assertTrue(workflow.refreshStatus(shop,current()));assertEquals(Status.PUBLISHED,current().status());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(1,api.signatures.get());
        PublishedCardGtinFixture.synchronize(shop,current().gtin(),"101","11","");
    }
    @Test void legacyPublishedLabelIsReconciledWithoutReplacingItsGtinOrFeed()throws Exception {
        assertTrue(workflow.start(shop,current(),draft,null));var saved=current();assertEquals(Status.PUBLISHED,saved.status());
        api.cardPublished=false;assertFalse(workflow.refreshStatus(shop,saved));assertEquals(Status.PROCESSING,current().status());
        assertEquals(saved.gtin(),current().gtin());assertEquals(saved.feedId(),current().feedId());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());
        api.cardPublished=true;assertTrue(workflow.refreshStatus(shop,current()));assertEquals(Status.PUBLISHED,current().status());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void completedFeedWithoutACardCanBeExplicitlyRetriedWithTheSameGtinOnly()throws Exception {
        api.completedWithoutCard=true;assertTrue(workflow.start(shop,current(),draft,null));var failed=current();assertEquals(Status.ERROR,failed.status());
        assertTrue(failed.errorMessage().contains("CATALOG_FEED_COMPLETED_WITHOUT_CARD"));assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
        assertTrue(workflow.start(shop,failed,draft,null));assertEquals(Status.PUBLISHED,current().status());assertEquals(failed.gtin(),current().gtin());
        assertEquals(1,api.allocations.get());assertEquals(2,api.submissions.get());
    }
    @Test void fiveMinuteWaitWithoutACardBecomesActionableAndStillNeverResends()throws Exception {
        api.pendingModeration=true;assertTrue(workflow.start(shop,current(),draft,null));
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("UPDATE znack_card_registrations SET created_at='2020-01-01T00:00:00Z'");}
        api.cardMissing=true;assertFalse(workflow.refreshStatus(shop,current()));assertEquals(Status.ERROR,current().status());assertEquals("CATALOG_CARD_NOT_CREATED_AFTER_WAIT",current().errorMessage());
        assertEquals(1,repository.pendingStatus(1,3).size());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
        api.cardMissing=false;api.pendingModeration=false;assertTrue(workflow.refreshStatus(shop,current()));assertEquals(Status.PUBLISHED,current().status());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void temporarilyMissingSigningDocumentCannotGrantApprovalOrSignUntilExplicitReview()throws Exception {
        api.moderated=true;assertTrue(workflow.start(shop,current(),draft,null));api.signingDocumentMissing=true;
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
                assertTrue(workflow.start(shop,current(),draft,(status,detail)->{if(status==Status.GTIN_GENERATED)LicenseRuntime.install(denied);}));
                assertEquals(Status.READY_TO_SUBMIT,current().status());assertEquals("04631993764370",current().gtin());
                assertEquals(1,api.allocations.get());assertEquals(0,api.submissions.get());
                assertThrows(LicenseDeniedException.class,()->workflow.resume(shop,current(),null));
                LicenseRuntime.install(prior);assertTrue(workflow.resume(shop,current(),null));
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
    @Test void lostAllocationResultPersistsReviewAndCannotBeRetriedNormally() {
        api.allocationTimeout=true;
        assertTrue(workflow.start(shop,current(),draft,null));
        Sku pending=current();
        assertEquals(Status.GTIN_REVIEW_REQUIRED,pending.status());
        assertNull(pending.gtin());
        assertFalse(workflow.start(shop,pending,draft,null));
        assertFalse(workflow.resume(shop,pending,null));
        assertEquals(1,api.allocations.get());
    }
    @Test void failureBeforeAnyAllocationRemainsSafelyRetryable() {
        var unavailable=RegistrationWorkflowTestFixtures.workflow(repository,
                ignored->{throw new IllegalStateException("fixture authentication unavailable");},Runnable::run);
        assertTrue(unavailable.start(shop,current(),draft,null));
        assertEquals("PREFLIGHT_ERROR",current().status().name());
        assertTrue(current().canRegister());assertFalse(current().needsGtinReview());
        assertTrue(workflow.start(shop,current(),draft,null));
        assertEquals(Status.PUBLISHED,current().status());assertEquals(1,api.allocations.get());
    }
    @Test void rejectedExecutorKeepsAnUnsentFeedSafelyRetryable() {
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
        assertTrue(workflow.start(shop,current(),draft,null));
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
    @Test void freshRequestRequiresConfirmationAndDoesNotUseAnOldUnclaimedCode() throws Exception {
        pending("CHECKING"); api.existingDraft=true;
        Sku pending=current();
        assertFalse(workflow.requestNewGtin(shop,pending,draft,false,null));
        assertEquals(0,api.allocations.get());
        assertTrue(workflow.requestNewGtin(shop,pending,draft,true,null));
        assertEquals(1,api.allocations.get());
        assertEquals("04631993764370",current().gtin());
        assertEquals(Status.PUBLISHED,current().status());
        assertFalse(workflow.requestNewGtin(shop,pending,draft,true,null));
        assertEquals(1,api.allocations.get());
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
    @Test void correctedPayloadResubmitsOnlyAfterReadingADefinitivelyRejectedFeed() {
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",
                ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        repository.updateProgress(1,11,Status.ERROR,"feed-existing",null,"required attribute missing",false);
        api.rejectExistingFeed=true;
        assertTrue(workflow.start(shop,current(),draft,null));
        assertEquals(1,api.submissions.get());
        assertEquals(0,api.allocations.get());
        assertEquals(Status.PUBLISHED,current().status());
        assertEquals("feed-new",current().feedId());
        assertEquals("04631993764370",current().gtin());
    }
    @Test void catalogKitCorrectionUsesTheSameGtinAndIsCheckpointedBeforeItsSingleRetry(){
        api.firstRejection="Карточка должна быть создана с типом Комплект";
        assertTrue(workflow.start(shop,current(),draft,null));assertEquals(Status.PUBLISHED,current().status());assertEquals(1,api.allocations.get());assertEquals(2,api.submissions.get());
        assertTrue(JsonParser.parseString(repository.payload(1,11)).getAsJsonObject().get("is_kit").getAsBoolean());assertTrue(api.lastPayload.get("is_kit").getAsBoolean());
    }
    @Test void rejectedRetryPreservesItsExistingCardIdAndRefusesAnUnexpectedReplacement()throws Exception{
        api.firstRejection="Карточка должна быть создана с типом Комплект";api.firstRejectedGoodId=90L;
        assertTrue(workflow.start(shop,current(),draft,null));assertEquals(Status.PRE_SUBMIT_ERROR,current().status());assertEquals(90L,current().goodId());assertEquals(90L,JsonParser.parseString(repository.payload(1,11)).getAsJsonObject().get("good_id").getAsLong());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get(),"A different remote card identity must be blocked before a second POST");
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT good_id,payload_json FROM znack_card_registration_history")){assertTrue(r.next());assertEquals(90L,r.getLong(1));assertFalse(JsonParser.parseString(r.getString(2)).getAsJsonObject().has("is_kit"));}
    }
    @Test void reopeningAPreparedRetryPreservesTheReviewedServerCorrectedPayload()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE wb_product_cards SET subject_name='Костюмы спортивные' WHERE shop_id=1");}
        var repaired=new Draft("6112120000","6112",1,"Fixture","Fixture",draft.attributes(),Map.of(),false,true);
        repository.saveGenerated(1,current(),"04631993764370",repaired.tnved(),1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",repaired,"").toString());repository.updateProgress(1,11,Status.READY_TO_SUBMIT,null,null,null,null);
        assertTrue(workflow.resume(shop,current(),null));assertEquals(Status.PUBLISHED,current().status());assertTrue(api.lastPayload.get("is_kit").getAsBoolean());assertEquals(0,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void aLegacyUnsentEditPersistsItsKnownGoodIdBeforeSubmitting(){
        repository.saveGenerated(1,current(),"04631993764370",draft.tnved(),1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        repository.updateProgress(1,11,Status.READY_TO_SUBMIT,null,91L,null,null);
        assertFalse(JsonParser.parseString(repository.payload(1,11)).getAsJsonObject().has("good_id"));
        assertTrue(workflow.resume(shop,current(),null));assertEquals(Status.PUBLISHED,current().status());
        assertEquals(91L,api.lastPayload.get("good_id").getAsLong());assertEquals(91L,JsonParser.parseString(repository.payload(1,11)).getAsJsonObject().get("good_id").getAsLong());assertEquals(0,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void rejectionDoesNotResetASignatureWhoseResultStillNeedsReadback(){
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());repository.updateProgress(1,11,Status.READY_TO_SIGN,"feed-existing",91L,null,false);
        var document=new ZnackNationalCatalogService.SigningDocument(91L,new byte[]{1});repository.prepareSignature(1,11,"04631993764370",document);assertTrue(repository.claimSignatureSend(1,11,document.sha256()));repository.updateProgress(1,11,Status.ERROR,"feed-existing",91L,"temporary error",false);api.rejectExistingFeed=true;
        assertTrue(workflow.start(shop,current(),draft,null));assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals("SENDING",repository.signingCheckpoint(1,11).state());assertEquals("feed-existing",current().feedId());
    }
    @Test void observerFailuresDoNotTurnSuccessfulRemoteWorkIntoAnError() {
        assertTrue(workflow.start(shop,current(),draft,(status,detail)->{throw new IllegalStateException("fixture observer");}));
        assertEquals(Status.PUBLISHED,current().status());
        assertEquals(1,api.allocations.get());
        assertEquals(1,api.submissions.get());
    }
    @Test void lostFeedResultKeepsTheGtinAndDoesNotRepeatSubmission() {
        api.feedTimeout=true;
        assertTrue(workflow.start(shop,current(),draft,null));
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
    @Test void changedConfigurationAfterAllocationStopsFeedAndPreservesOriginalBinding() {
        var settingsRepository=new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture"));
        settingsRepository.saveSettings(ZnackRegistrationScopeTest.settings("7707083893","a".repeat(40)));
        var owner=ZnackRegistrationScope.fromSettings(settingsRepository.getSettings());
        var catalogSession=fixtureSession();
        var scoped=RegistrationWorkflowTestFixtures.workflow(repository,ignored->new ZnackCardRegistrationWorkflow.CatalogSession(catalogSession.token(),catalogSession.catalog(),owner),Runnable::run,true,(s,n,v,g)->false);
        assertTrue(scoped.start(shop,current(),draft,(status,detail)->{
            if(status==Status.GTIN_GENERATED)settingsRepository.saveSettings(ZnackRegistrationScopeTest.settings("7807083893","b".repeat(40)));
        }));
        assertEquals(1,api.allocations.get());assertEquals(0,api.submissions.get());
        assertEquals(owner,repository.scope(1,11));
        assertEquals(Status.PRE_SUBMIT_ERROR,current().status());
        assertFalse(scoped.resume(shop,current(),null)); // Feed resume is not eligible; a reviewed retry still checks the owner.
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
    @Test void moderatedCardReleasesWorkerAndWaitsForExplicitSigning() {
        api.moderated=true;
        assertTrue(workflow.start(shop,current(),draft,null));
        assertEquals(Status.READY_TO_SIGN,current().status());assertEquals(0,api.signatures.get());
        assertTrue(RegistrationWorkflowTestFixtures.sign(workflow,shop,List.of(current()),true,null));
        assertEquals(1,api.signatures.get());assertEquals(Status.PUBLISHED,current().status());
    }
    @Test void changingTnvedGroupRequiresExplicitNewCardConfirmationBeforeAllocating()throws Exception {
        var oldDraft=new Draft("6104",1,"Fixture","Brand",Map.of(1L,"Fixture"));
        repository.saveGenerated(1,current(),"04631993764363","6104",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764363",oldDraft,"").toString());
        repository.updateProgress(1,11,Status.PUBLISHED,"old-feed",90L,null,true);var old=current();
        assertThrows(RegistrationValidationException.class,()->workflow.start(shop,old,draft,null));
        assertEquals("04631993764363",current().gtin());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
        assertFalse(workflow.registerNew(shop,old,draft,false,null));
        assertTrue(workflow.registerNew(shop,old,draft,true,null));assertEquals(Status.PUBLISHED,current().status());assertEquals("04631993764370",current().gtin());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
        assertEquals("04631993764363",repository.activeGtin(1,11));assertTrue(repository.claimedGtins().contains("04631993764363"));
        try(var c=Database.getConnection();var st=c.createStatement();var r=st.executeQuery("SELECT active_gtin,active_feed_id,active_payload_json FROM znack_card_registration_replacements")){assertTrue(r.next());assertEquals("04631993764363",r.getString(1));assertEquals("old-feed",r.getString(2));assertTrue(r.getString(3).contains("6104"));}
        assertFalse(workflow.start(shop,old,draft,null),"The old reviewed revision cannot claim the replacement card");
        assertEquals("04631993764370",current().gtin());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
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
        api.pendingModeration=true;assertTrue(workflow.start(shop,current(),automaticDraft(),null));assertEquals(Status.PROCESSING,current().status());assertEquals(0,api.signatures.get());
        api.pendingModeration=false;api.moderated=true;var reopened=RegistrationWorkflowTestFixtures.workflow(repository,ignored->fixtureSession(),Runnable::run);var monitor=new ZnackRegistrationMonitor(reopened,null);
        assertEquals(1,monitor.poll(shop,3).published());assertEquals(1,api.signatures.get());assertEquals(Status.PUBLISHED,current().status());monitor.poll(shop,3);assertEquals(1,api.signatures.get());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void automaticSigningTimeoutIsReadBackAfterRestartWithoutSendingTheSignatureAgain()throws Exception {
        api.pendingModeration=true;workflow.start(shop,current(),automaticDraft(),null);api.pendingModeration=false;api.moderated=true;api.signTimeout=true;
        var monitor=new ZnackRegistrationMonitor(RegistrationWorkflowTestFixtures.workflow(repository,ignored->fixtureSession(),Runnable::run),null);monitor.poll(shop,3);assertEquals(Status.SIGNING,current().status());assertEquals(1,api.signatures.get());monitor.poll(shop,3);assertEquals(1,api.signatures.get());api.signatureAccepted=true;assertEquals(1,monitor.poll(shop,3).published());assertEquals(1,api.signatures.get());
    }
    @Test void backgroundMonitorReadsExistingFeedWithoutAllocatingSubmittingOrSigningAgain()throws Exception {
        api.moderated=true;assertTrue(workflow.start(shop,current(),draft,null));
        var monitor=new ZnackRegistrationMonitor(workflow,null);var waiting=monitor.poll(shop,3);
        assertEquals(1,waiting.checked());assertEquals(0,waiting.published());assertFalse(waiting.pending());assertEquals(0,api.signatures.get());
        api.signatureAccepted=true;var done=monitor.poll(shop,3);assertEquals(1,done.published());assertEquals(Status.PUBLISHED,current().status());
        assertEquals(0,api.signatures.get());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void savedUnsentCardsContinueFromTheMonitorWithoutASecondCreateClickOrAnotherAllocation()throws Exception{
        {var state=Status.READY_TO_SUBMIT;
            repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
            repository.updateProgress(1,11,state,null,null,null,false);
            var before=api.submissions.get();var monitor=new ZnackRegistrationMonitor(workflow,null);
            assertEquals(1,monitor.poll(shop,3).checked());
            assertEquals(before+1,api.submissions.get());assertEquals(0,api.allocations.get());
            assertTrue(current().hasFeed());assertNotEquals(state,current().status());
        }
    }
    @Test void temporaryValidationReadFailureContinuesAutomaticallyWithoutASecondCreateClick()throws Exception{
        var payload=ZnackNationalCatalogService.buildPayload("04631993764370",draft,"");payload.addProperty("good_id",91);
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",payload.toString());repository.updateProgress(1,11,Status.READY_TO_SUBMIT,null,91L,null,false);
        api.temporaryCardReads=1;assertTrue(workflow.resume(shop,current(),null));
        assertEquals(Status.READY_TO_SUBMIT,current().status());assertEquals(0,api.submissions.get());
        assertTrue(new com.vncode.app.features.sync.SyncStreamStore().ensure(shop,"ZNACK",java.time.Instant.now()).requested(),"Known-unsent work must wake the worker instead of waiting for its 15-minute interval");
        new ZnackRegistrationMonitor(workflow,null).poll(shop,3);
        assertEquals(1,api.submissions.get());assertEquals(0,api.allocations.get());assertEquals(Status.PUBLISHED,current().status());
    }
    @Test void temporaryCard404AfterCompletedFeedKeepsTheOriginalRequestAndNeverResendsIt()throws Exception{
        api.completedWithoutCard=true;api.cardHttp404=true;assertTrue(workflow.start(shop,current(),draft,null));
        assertEquals(Status.PROCESSING,current().status());assertEquals(1,api.submissions.get());
        new ZnackRegistrationMonitor(workflow,null).poll(shop,3);assertEquals(Status.PROCESSING,current().status());assertEquals(1,api.submissions.get());
        api.cardHttp404=false;api.completedWithoutCard=false;
        assertEquals(1,new ZnackRegistrationMonitor(workflow,null).poll(shop,3).published());assertEquals(1,api.submissions.get());
    }
    @Test void aQueuedContinuationKeepsTheFastPollingLoopActiveUntilItsWorkerStarts()throws Exception{
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());repository.updateProgress(1,11,Status.READY_TO_SUBMIT,null,null,null,false);
        var queued=new java.util.ArrayList<Runnable>();var later=RegistrationWorkflowTestFixtures.workflow(repository,ignored->fixtureSession(),queued::add);
        try{
            var result=new ZnackRegistrationMonitor(later,null).poll(shop,3);
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
    @Test void newCardConsumesFreshGtinOnlyAfterConfirmationAndKeepsOldUntilMarketplaceReadback() {
        repository.saveGenerated(1,current(),"04631993764363","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764363",draft,"").toString());
        repository.updateProgress(1,11,Status.PUBLISHED,"old-feed",90L,null,true);
        Sku old=current();assertFalse(workflow.registerNew(shop,old,draft,false,null));assertEquals(0,api.allocations.get());
        assertTrue(workflow.registerNew(shop,old,draft,true,null));assertEquals(1,api.allocations.get());
        assertEquals("04631993764363",repository.activeGtin(1,11));assertEquals(Status.PUBLISHED,current().status());
        assertTrue(workflow.completeReplacement(shop,current(),true,null));assertEquals("04631993764363",repository.activeGtin(1,11));
        com.vncode.app.features.gtinsync.GtinWriteTestFixture.saveSettings(shop,"A".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        var actual=current();var details=new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@catalog_id",List.of(actual.goodId().toString()),"@catalog_status",List.of("published")),List.of(),List.of());
        new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(1,shop.getMarketplace(),List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(actual.gtin(),"PANTS","","XL",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(shop),java.time.Instant.now(),details)));
        var completing=RegistrationWorkflowTestFixtures.workflow(repository,ignored->fixtureSession(),Runnable::run,false,(s,n,v,g)->g.equals("04631993764370"));
        assertTrue(completing.completeReplacement(shop,current(),true,null));assertEquals("04631993764370",repository.activeGtin(1,11));
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
        api.moderated=true;workflow.start(shop,current(),draft,null);
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
        api.moderated=true;workflow.start(shop,current(),draft,null);
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO znack_registration_signing_consents(shop_id,marketplace,product_id,variant_id,gtin,owner,enabled,updated_at) VALUES(1,'WILDBERRIES','101','11',?,?,1,'fixture')")){
            p.setString(1,current().gtin());p.setString(2,com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop));p.executeUpdate();
        }
        var result=new ZnackRegistrationMonitor(workflow,null).poll(shop,30);
        assertEquals(Status.READY_TO_SIGN,current().status());assertFalse(result.pending());assertEquals(0,api.signatures.get());
    }
    @Test void oldCountOnlyConfirmationCannotGrantSigningConsent(){
        api.moderated=true;workflow.start(shop,current(),draft,null);
        assertFalse(workflow.signApproved(shop,List.of(current()),true,null));assertEquals(0,api.signatures.get());
        assertFalse(RegistrationSigningConsent.allowed(shop,new com.vncode.app.features.products.ProductKey(1,shop.getMarketplace(),"101","11"),current().gtin()));
    }
    @Test void reviewedGenuineDraftUpdatesItsExistingIdentityWithoutAllocationSigningOrMarketplaceGrant()throws Exception{
        prepareReviewedRawDraft();var reviewed=reviewedDraftWorkflow();
        assertTrue(reviewed.start(shop,current(),draft.withAutoSign(true),null));
        assertDraftUpdateOnly();assertTrue(current().hasFeed());assertEquals("feed-new",current().feedId());assertEquals(Status.PROCESSING,current().status());
    }
    @Test void adoptedNativeDraftWithoutFeedCanBeReviewedAndSubmittedOnceWithItsExistingGtin()throws Exception{
        prepareReviewedRawDraft();repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture","");
        repository.updateProgress(1,11,Status.PROCESSING,null,91L,"CATALOG_CARD_DRAFT",false);assertTrue(current().canReviewExistingDraft());
        assertTrue(reviewedDraftWorkflow().start(shop,current(),draft.withAutoSign(true),null));
        assertDraftUpdateOnly();assertTrue(current().hasFeed());assertEquals(Status.PROCESSING,current().status());
    }
    @Test void uncertainDraftSubmissionAndStillDraftReadbackCannotReplayOrGrantAnotherAction()throws Exception{
        prepareReviewedRawDraft();api.feedTimeout=true;var reviewed=reviewedDraftWorkflow();
        assertTrue(reviewed.start(shop,current(),draft.withAutoSign(true),null));assertEquals(Status.FEED_REVIEW_REQUIRED,current().status());assertFalse(current().hasFeed());assertDraftUpdateOnly();
        String frozen=repository.payload(1,11);assertTrue(ZnackNationalCatalogService.hasReviewedDraft(JsonParser.parseString(frozen).getAsJsonObject()));
        assertTrue(reviewed.verifySubmissionForBatch(shop,current()));assertEquals(Status.FEED_REVIEW_REQUIRED,current().status());assertEquals(frozen,repository.payload(1,11));
        assertFalse(reviewed.resumeSubmissionOnly(shop,current(),true,null));assertFalse(reviewed.start(shop,current(),draft,null));assertEquals(1,api.submissions.get());assertEquals(0,api.allocations.get());assertEquals(0,api.signatures.get());
    }
    @Test void restartedKnownUnsentReviewedDraftDispatchesOnlyTheFrozenPayloadWithoutAutoGrants()throws Exception{
        prepareReviewedRawDraft();var approved=new Draft("6204",1,"Frozen reviewed name","Brand",Map.of(1L,"Frozen reviewed value"));
        var body=ZnackNationalCatalogService.buildPayload("04631993764370",approved,"");body.addProperty("good_id",91L);var checkpoint=ZnackNationalCatalogService.reviewedDraftPayload(shop,body);
        repository.saveReadyToSubmitGenerated(1,current(),"04631993764370","6204",1,approved.goodName(),checkpoint.toString());repository.updateProgress(1,11,Status.READY_TO_SUBMIT,null,91L,null,false);
        String frozen=repository.payload(1,11);assertTrue(current().canResume());
        var restarted=reviewedDraftWorkflow();assertTrue(restarted.resumeSubmissionOnly(shop,current(),true,null));assertDraftUpdateOnly();
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
    @Test void sellerCanChooseANewGtinForAProvenRejectedCardWithoutLosingTheOldIdentity()throws Exception{
        failedCardForReplacement();api.rejectExistingFeed=true;
        assertTrue(workflow.registerNew(shop,current(),draft,true,null));assertEquals("04631993764370",current().gtin());assertEquals(Status.PUBLISHED,current().status());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT gtin,feed_id FROM znack_card_registration_history WHERE shop_id=1 AND chrt_id=11");var r=p.executeQuery()){
            assertTrue(r.next());assertEquals("04631993764363",r.getString(1));assertEquals("feed-existing",r.getString(2));
        }
    }
    @Test void aProcessingFeedWithItemErrorsCannotConsumeANewGtinThroughRecovery(){
        failedCardForReplacement();api.rejectExistingFeed=true;api.existingFeedStatus="Processing";
        assertTrue(workflow.registerNew(shop,current(),draft,true,null));assertEquals("04631993764363",current().gtin());assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());
    }
    private void failedCardForReplacement(){
        api.existingFeedGtin="04631993764363";
        repository.saveGenerated(1,current(),"04631993764363","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764363",draft,"").toString());
        repository.updateProgress(1,11,Status.ERROR,"feed-existing",91L,"Rejected",false);
    }
    @Test void acceptedFeedSurvivesRateLimitWithoutAllocatingOrSubmittingAgain()throws Exception{temporaryReadback(429);}
    @Test void acceptedFeedSurvivesNetworkTimeoutWithoutAllocatingOrSubmittingAgain()throws Exception{temporaryReadback(-1);}
    private void temporaryReadback(int failure)throws Exception{
        api.statusReadFailure=failure;assertTrue(workflow.start(shop,current(),draft,null));
        assertEquals(Status.PROCESSING,current().status());assertEquals("CATALOG_READBACK_RETRY_PENDING",current().errorMessage());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
        api.statusReadFailure=0;assertTrue(workflow.refreshStatus(shop,current()));assertEquals(Status.PUBLISHED,current().status());
        assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());assertEquals(0,api.signatures.get());
    }
    @Test void accessDeniedRemainsAnActionableErrorInsteadOfAnAutomaticReadRetry(){
        api.statusReadFailure=403;assertTrue(workflow.start(shop,current(),draft,null));assertEquals(Status.ERROR,current().status());assertEquals(1,api.submissions.get());
    }
    class FixtureApi extends ZnackApiClient {
        AtomicInteger allocations=new AtomicInteger(),submissions=new AtomicInteger(),signatures=new AtomicInteger(),documents=new AtomicInteger(),countryReads=new AtomicInteger();
        boolean mandatoryCountry;
        boolean allocationTimeout,feedTimeout,existingDraft,rejectExistingFeed,readRejected;
        boolean pendingModeration,moderated,signTimeout,signatureAccepted,changeSigningDocument,delayPublication,laggedModeration;
        int statusReadFailure,temporaryCardReads;
        boolean cardHttp404;
        boolean cardPublished=true,cardMissing,completedWithoutCard,signingDocumentMissing;
        Map<String,String> bulkGtins=new HashMap<>();
        Map<String,Long> bulkGoodIds=new HashMap<>();
        String existingFeedStatus="Rejected";
        String existingFeedGtin="04631993764370";
        String firstRejection;Long firstRejectedGoodId;JsonObject lastPayload,preExistingRawDraft;
        @Override public JsonElement generatedGtins(String base,String token) {
            return json(existingDraft?"{\"result\":{\"drafts\":[{\"gtin\":\"04631993764363\"}]}}":"{\"result\":{\"drafts\":[]}}");
        }
        @Override public JsonElement generateGtins(String base,String token,int quantity) throws IOException {
            assertEquals(Status.ALLOCATING_GTIN,current().status(),"Checkpoint must precede the remote allocation");
            allocations.incrementAndGet();
            if(allocationTimeout)throw new IOException("fixture allocation result lost");
            return json("{\"result\":{\"drafts\":[{\"gtin\":\"04631993764370\"}]}}");
        }
        @Override public JsonElement nationalCatalogCategories(String base,String token,String tnved) {
            return json("{\"result\":[{\"cat_id\":1,\"cat_name\":\"Clothes\",\"category_active\":true}]}");
        }
        @Override public JsonElement nationalCatalogAttributes(String base,String token,long category) {
            var response=json("{\"result\":[{\"attr_id\":1,\"attr_name\":\"Fixture\",\"attr_field_type\":\"text\"}]}").getAsJsonObject();
            if(mandatoryCountry)response.getAsJsonArray("result").add(json("{\"attr_id\":2630,\"attr_name\":\"Страна производства\",\"attr_field_type\":\"text\",\"attr_preset_only\":true,\"attr_preset\":[]}"));
            return response;
        }
        @Override public JsonElement nationalCatalogAllAttributes(String base,String token,long category){return nationalCatalogAttributes(base,token,category);}
        @Override public JsonElement nationalCatalogCountries(String base,String token){countryReads.incrementAndGet();return json("{\"apiversion\":3,\"result\":{\"_etag\":\"fixture-country-etag\",\"_list\":[{\"country_iso\":\"RU\",\"country_name\":\"Россия\"},{\"country_iso\":\"CN\",\"country_name\":\"Китай\"}]}}");}
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
            if(firstRejection!=null&&submissions.get()==1)return json("{\"result\":{\"status\":\"Rejected\",\"item\":[{\"gtin\":\"04631993764370\",\"good_id\":"+firstRejectedGoodId+",\"status_code\":400,\"message\":\""+firstRejection+"\"}]}}");
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
            boolean published=cardPublished&&!pendingModeration&&(!moderated||signatureAccepted);
            String details=published?"":moderated?"notsigned":pendingModeration?"moderation":"draft";
            JsonObject card=lastPayload==null?new JsonObject():lastPayload.deepCopy();
            if(lastPayload==null&&!repository.payload(1,11).isBlank())card=JsonParser.parseString(repository.payload(1,11)).getAsJsonObject();
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
