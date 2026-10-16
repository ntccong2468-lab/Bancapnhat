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
    Draft draft=new Draft("6204",1,"Fixture","Brand",Map.of());
    FixtureApi api;
    ZnackCardRegistrationWorkflow workflow;
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString()); Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','fixture')");
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,101,'PANTS','Fixture','2026-10-06T00:00:00Z')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,11,101,'XL')");
        }
        repository=new ZnackCardRegistrationRepository(); api=new FixtureApi();
        var auth=new ZnackAuthService(api,null) {
            @Override public String trueApiToken(ZnackModels.Settings settings){return "fixture-token";}
        };
        var catalog=new ZnackNationalCatalogService(api,auth,(payload,context)->new com.vncode.app.integration.znack.signature.CryptoProSigningResult(new byte[]{1},""),ZnackModels.Settings.empty());
        workflow=new ZnackCardRegistrationWorkflow(repository,
                ignored->new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token",catalog),Runnable::run);
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    Sku current(){return repository.search(new SearchCriteria(1,"",List.of(),"ALL",20,0)).getFirst();}
    @Test void registeredSignedCardFlowsThroughCatalogAndRealWbCardUpdateWithReadback()throws Exception {
        com.vncode.app.features.gtinsync.GtinWriteTestFixture.saveSettings(shop,"A".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        api.moderated=true;assertTrue(workflow.start(shop,current(),draft,null));assertEquals(Status.READY_TO_SIGN,current().status());
        assertTrue(workflow.signApproved(shop,List.of(current()),true,null));assertEquals(Status.PUBLISHED,current().status());
        PublishedCardGtinFixture.synchronize(shop,current().gtin(),"101","11","");
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
        var waiting=new ZnackCardRegistrationWorkflow(repository,
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
        var unavailable=new ZnackCardRegistrationWorkflow(repository,
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
        var rejected=new ZnackCardRegistrationWorkflow(repository,
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
        var waiting=new ZnackCardRegistrationWorkflow(repository,
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
        var scoped=new ZnackCardRegistrationWorkflow(repository,ignored->new ZnackCardRegistrationWorkflow.CatalogSession(catalogSession.token(),catalogSession.catalog(),owner),Runnable::run,true,(s,n,v,g)->false);
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
        assertFalse(workflow.signApproved(shop,List.of(current()),false,null));assertEquals(0,api.signatures.get());
        assertTrue(workflow.signApproved(shop,List.of(current()),true,null));
        assertEquals(Status.SIGNING,current().status());assertEquals(1,api.signatures.get());
        assertEquals("RECONCILE_REQUIRED",repository.signingCheckpoint(1,11).state());
        assertTrue(workflow.signApproved(shop,List.of(current()),true,null));assertEquals(1,api.signatures.get());
        api.signatureAccepted=true;
        assertTrue(workflow.signApproved(shop,List.of(current()),true,null));
        assertEquals(Status.PUBLISHED,current().status());assertEquals(1,api.signatures.get());
        assertEquals("CONFIRMED",repository.signingCheckpoint(1,11).state());
    }
    @Test void acceptedSignatureRemainsPendingUntilIndependentExactReadback() {
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        repository.updateProgress(1,11,Status.READY_TO_SIGN,"feed-existing",91L,null,false);
        api.moderated=true;api.delayPublication=true;
        assertTrue(workflow.signApproved(shop,List.of(current()),true,null));
        assertEquals(1,api.signatures.get());assertEquals(Status.SIGNING,current().status());
        assertEquals("RECONCILE_REQUIRED",repository.signingCheckpoint(1,11).state());
        assertTrue(workflow.signApproved(shop,List.of(current()),true,null));assertEquals(1,api.signatures.get());
        api.signatureAccepted=true;
        assertTrue(workflow.signApproved(shop,List.of(current()),true,null));assertEquals(Status.PUBLISHED,current().status());
        assertEquals("CONFIRMED",repository.signingCheckpoint(1,11).state());assertEquals(1,api.signatures.get());
    }
    @Test void moderatedCardReleasesWorkerAndWaitsForExplicitSigning() {
        api.moderated=true;
        assertTrue(workflow.start(shop,current(),draft,null));
        assertEquals(Status.READY_TO_SIGN,current().status());assertEquals(0,api.signatures.get());
        assertTrue(workflow.signApproved(shop,List.of(current()),true,null));
        assertEquals(1,api.signatures.get());assertEquals(Status.PUBLISHED,current().status());
    }
    @Test void backgroundMonitorReadsExistingFeedWithoutAllocatingSubmittingOrSigningAgain()throws Exception {
        api.moderated=true;assertTrue(workflow.start(shop,current(),draft,null));
        var monitor=new ZnackRegistrationMonitor(workflow,null);var waiting=monitor.poll(shop,3);
        assertEquals(1,waiting.checked());assertEquals(0,waiting.published());assertFalse(waiting.pending());assertEquals(0,api.signatures.get());
        api.signatureAccepted=true;var done=monitor.poll(shop,3);assertEquals(1,done.published());assertEquals(Status.PUBLISHED,current().status());
        assertEquals(0,api.signatures.get());assertEquals(1,api.allocations.get());assertEquals(1,api.submissions.get());
    }
    @Test void signingFrozenXmlRequiresFreshRemoteEligibilityAndExactDocument() {
        repository.saveGenerated(1,current(),"04631993764370","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764370",draft,"").toString());
        repository.updateProgress(1,11,Status.READY_TO_SIGN,"feed-existing",91L,null,false);
        api.moderated=true;api.changeSigningDocument=true;
        assertTrue(workflow.signApproved(shop,List.of(current()),true,null));
        assertEquals(0,api.signatures.get());assertEquals(Status.SIGNING,current().status());
        assertEquals("RECONCILE_REQUIRED",repository.signingCheckpoint(1,11).state());
    }
    @Test void newCardConsumesFreshGtinOnlyAfterConfirmationAndKeepsOldUntilMarketplaceReadback() {
        repository.saveGenerated(1,current(),"04631993764363","6204",1,"Fixture",ZnackNationalCatalogService.buildPayload("04631993764363",draft,"").toString());
        repository.updateProgress(1,11,Status.PUBLISHED,"old-feed",90L,null,true);
        Sku old=current();assertFalse(workflow.registerNew(shop,old,draft,false,null));assertEquals(0,api.allocations.get());
        assertTrue(workflow.registerNew(shop,old,draft,true,null));assertEquals(1,api.allocations.get());
        assertEquals("04631993764363",repository.activeGtin(1,11));assertEquals(Status.PUBLISHED,current().status());
        assertTrue(workflow.completeReplacement(shop,current(),true,null));assertEquals("04631993764363",repository.activeGtin(1,11));
        var completing=new ZnackCardRegistrationWorkflow(repository,ignored->fixtureSession(),Runnable::run,false,(s,n,v,g)->g.equals("04631993764370"));
        assertTrue(completing.completeReplacement(shop,current(),true,null));assertEquals("04631993764370",repository.activeGtin(1,11));
    }
    @Test void largeBulkReviewUsesOneQueueSlotAndOneCatalogSessionForThreeHundredCards()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()) {
            for(int i=1;i<=300;i++) {
                long nm=1000+i,chrt=2000+i;String gtin=String.format("0463199376%04d",i),feed="bulk-"+i;
                s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,"+nm+",'Fixture','Fixture','fixture')");
                s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,"+chrt+","+nm+",'XL')");
                api.bulkGtins.put(feed,gtin);api.bulkGoodIds.put(feed,nm);
                try(var insert=c.prepareStatement("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,good_id,feed_id,payload_json,status,created_at,updated_at) VALUES(1,?,?,?,?,?,?,'READY_TO_SIGN','fixture','fixture')")) {
                    insert.setLong(1,chrt);insert.setLong(2,nm);insert.setString(3,gtin);insert.setLong(4,nm);insert.setString(5,feed);insert.setString(6,ZnackNationalCatalogService.buildPayload(gtin,draft,"").toString());insert.executeUpdate();
                }
            }
        }
        var queued=new ArrayList<Runnable>();var sessions=new AtomicInteger();
        var bulk=new ZnackCardRegistrationWorkflow(repository,ignored->{sessions.incrementAndGet();return fixtureSession();},queued::add);
        var frozen=repository.search(new SearchCriteria(1,"",List.of(),"READY_TO_SIGN",1000,0));assertEquals(300,frozen.size());
        assertTrue(bulk.signApproved(shop,frozen,true,null));assertEquals(1,queued.size());assertEquals(0,sessions.get());
        assertTrue(ZnackCardRegistrationWorkflow.hasActiveJobs());queued.getFirst().run();assertFalse(ZnackCardRegistrationWorkflow.hasActiveJobs());
        assertEquals(1,sessions.get());assertEquals(300,repository.search(new SearchCriteria(1,"",List.of(),"COMPLETED",1000,0)).size());
        assertEquals(0,api.allocations.get());assertEquals(0,api.submissions.get());assertEquals(0,api.signatures.get());
    }
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
    class FixtureApi extends ZnackApiClient {
        AtomicInteger allocations=new AtomicInteger(),submissions=new AtomicInteger(),signatures=new AtomicInteger(),documents=new AtomicInteger();
        boolean allocationTimeout,feedTimeout,existingDraft,rejectExistingFeed,readRejected;
        boolean moderated,signTimeout,signatureAccepted,changeSigningDocument,delayPublication;
        Map<String,String> bulkGtins=new HashMap<>();
        Map<String,Long> bulkGoodIds=new HashMap<>();
        String existingFeedStatus="Rejected";
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
            return json("{\"result\":[{\"attr_id\":1,\"attr_name\":\"Fixture\",\"attr_field_type\":\"text\"}]}");
        }
        @Override public JsonElement submitNationalCatalogFeed(String base,String token,JsonElement payload) throws IOException {
            assertEquals(Status.FEED_SUBMITTING,current().status(),"Checkpoint must precede feed submission");
            if(rejectExistingFeed)assertTrue(readRejected,"The old feed must be confirmed rejected before resubmitting");
            submissions.incrementAndGet();
            if(feedTimeout)throw new IOException("fixture feed result lost");
            return json("{\"result\":{\"feed_id\":\"feed-new\"}}");
        }
        @Override public JsonElement nationalCatalogFeedStatus(String base,String token,String feed) {
            if(bulkGtins.containsKey(feed))return json("{\"result\":{\"status\":\"Signed\",\"item\":[{\"gtin\":\""+bulkGtins.get(feed)+"\",\"good_id\":"+bulkGoodIds.get(feed)+"}]}}");
            if(rejectExistingFeed && feed.equals("feed-existing")) {
                readRejected=true;
                return json("{\"result\":{\"status\":\""+existingFeedStatus+"\",\"item\":[{\"gtin\":\"04631993764370\",\"status_code\":400,\"message\":\"required attribute missing\"}]}}");
            }
            return json("{\"result\":{\"status\":\""+(moderated && !signatureAccepted?"Moderated":"Signed")+"\",\"item\":[{\"gtin\":\"04631993764370\",\"good_id\":91}]}}");
        }
        @Override public JsonElement nationalCatalogSigningDocument(String base,String token,JsonObject body) {
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
