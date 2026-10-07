package com.tuandev.fbsbarcode.integration.znack.registration;

import com.google.gson.*;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.znack.*;
import com.tuandev.fbsbarcode.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.tuandev.fbsbarcode.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

class ZnackCardRegistrationWorkflowTest {
    @TempDir Path temp;
    ZnackCardRegistrationRepository repository;
    Shop shop=new Shop(1,"Fixture","fixture");
    Draft draft=new Draft("6204",1,"Fixture","Brand",Map.of());
    FixtureApi api;
    ZnackCardRegistrationWorkflow workflow;
    @BeforeEach void setup() throws Exception {
        System.setProperty("wcode.appdata.dir",temp.toString()); Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','fixture')");
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,101,'PANTS','Fixture','2026-10-06T00:00:00Z')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,11,101,'XL')");
        }
        repository=new ZnackCardRegistrationRepository(); api=new FixtureApi();
        var auth=new ZnackAuthService(api,null) {
            @Override public String trueApiToken(ZnackModels.Settings settings){return "fixture-token";}
        };
        var catalog=new ZnackNationalCatalogService(api,auth,null,ZnackModels.Settings.empty());
        workflow=new ZnackCardRegistrationWorkflow(repository,
                ignored->new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token",catalog),Runnable::run);
    }
    @AfterEach void cleanup(){System.clearProperty("wcode.appdata.dir");}
    Sku current(){return repository.search(new SearchCriteria(1,"",List.of(),"ALL",20,0)).getFirst();}
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
    void pending(String status) throws Exception {
        try(var c=Database.getConnection();var s=c.prepareStatement("""
                INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,status,created_at,updated_at)
                VALUES(1,11,101,?,'2026-10-06T00:00:00Z','2026-10-06T00:00:00Z')
                """)){s.setString(1,status);s.executeUpdate();}
    }
    class FixtureApi extends ZnackApiClient {
        AtomicInteger allocations=new AtomicInteger(),submissions=new AtomicInteger();
        boolean allocationTimeout,feedTimeout,existingDraft,rejectExistingFeed,readRejected;
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
            if(rejectExistingFeed && feed.equals("feed-existing")) {
                readRejected=true;
                return json("{\"result\":{\"status\":\""+existingFeedStatus+"\",\"item\":[{\"gtin\":\"04631993764370\",\"status_code\":400,\"message\":\"required attribute missing\"}]}}");
            }
            return json("{\"result\":{\"status\":\"Signed\",\"item\":[{\"gtin\":\"04631993764370\",\"good_id\":91}]}}");
        }
        JsonElement json(String value){return JsonParser.parseString(value);}
    }
}
