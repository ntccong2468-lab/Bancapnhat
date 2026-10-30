package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.marketplace.Marketplace;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static org.junit.jupiter.api.Assertions.*;

class GtinPricePreflightTest {
    @TempDir Path temp;
    private GtinSyncRepository repo;
    private com.vncode.app.features.license.LicenseService oldLicense,license;
    private BatchFixture adapter;
    private GtinSyncCoordinator runner;
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Test','WILDBERRIES','test')");}
        oldLicense=LicenseRuntime.service();license=LicenseDomainFixture.active(temp.resolve("license"));LicenseRuntime.install(license);
        repo=new GtinSyncRepository();adapter=new BatchFixture();
        var proof=new CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),List.of());
        repo.saveCatalog(1,Marketplace.WILDBERRIES,List.of(new RegisteredGtin("00000000000017","PANTS","Đen","XL",true,true,"NK",Instant.now(),proof)));
        runner=new GtinSyncCoordinator(repo,key->adapter,Clock.systemUTC(),1,Duration.ZERO,cohort->{});
    }
    @AfterEach void close()throws Exception {LicenseRuntime.install(oldLicense);license.close();System.clearProperty("vncode.appdata.dir");}

    @Test void pricePendingNeverDispatchesGtinAndVerifiedPriceCanResume()throws Exception {
        var preview=new GtinPreviewService(repo,key->adapter).create(adapter.product.key(),Operation.ADD,"","00000000000017");
        var job=runner.confirm(List.of(preview));adapter.state=GtinBatchAdapter.PreparationState.PENDING;
        runner.runJob(job);var waiting=repo.jobItems(job).getFirst();
        assertEquals(Status.QUEUED,waiting.status());assertEquals("WB_PRICE_READBACK_PENDING",repo.issue(waiting.id()));
        assertNull(waiting.dispatchedAt());assertEquals(0,adapter.sent);
        adapter.state=GtinBatchAdapter.PreparationState.READY;runner.runJob(job);
        assertEquals(Status.SUCCEEDED,repo.jobItems(job).getFirst().status());assertEquals(1,adapter.sent);
    }
    @Test void rejectedPriceStopsGtinAndReportsThePriceReason()throws Exception {
        var preview=new GtinPreviewService(repo,key->adapter).create(adapter.product.key(),Operation.ADD,"","00000000000017");
        var job=runner.confirm(List.of(preview));adapter.state=GtinBatchAdapter.PreparationState.FAILED;
        runner.runJob(job);var failed=repo.jobItems(job).getFirst();
        assertEquals(Status.FAILED,failed.status());assertEquals("WB_PRICE_REJECTED",repo.issue(failed.id()));
        assertNull(failed.dispatchedAt());assertEquals(0,adapter.sent);
    }
    @Test void pauseDuringPricePreparationStopsTheGtinBeforeDispatch()throws Exception {
        var preview=new GtinPreviewService(repo,key->adapter).create(adapter.product.key(),Operation.ADD,"","00000000000017");
        var job=runner.confirm(List.of(preview));adapter.beforeGuard=()->repo.pause(job,true);
        runner.runJob(job);var item=repo.jobItems(job).getFirst();
        assertTrue(repo.paused(job));assertEquals(Status.QUEUED,item.status());assertNull(item.dispatchedAt());assertEquals(0,adapter.sent);
    }
    @Test void twoJobsForTheSameCardArePreparedIndependentlyWithoutMixedAnchor()throws Exception {
        var one=GtinSyncRepositoryTest.preview(1,Marketplace.WILDBERRIES);
        var product=one.before();var other=new ProductSnapshot(new ProductKey(1,Marketplace.WILDBERRIES,"101","12"),product.article(),product.color(),"L",List.of("sibling"),"other-fingerprint",Instant.now());
        var two=new Preview(other,Operation.ADD,"","00000000000024",one.capability());
        var first=repo.createJob(List.of(one));var second=repo.createJob(List.of(two));assertNotEquals(first,second);
        var claimed=repo.claimBatch(repo.pending(1,Marketplace.WILDBERRIES));
        assertEquals(1,claimed.size());assertEquals(1,claimed.stream().map(JobItem::jobId).distinct().count());
        var states=List.of(repo.jobItems(first).getFirst().status(),repo.jobItems(second).getFirst().status());
        assertTrue(states.contains(Status.VALIDATING),states.toString());assertTrue(states.contains(Status.QUEUED),states.toString());
    }
    @Test void pricePermissionDeniedFailsClearlyAndDoesNotSubmitGtin()throws Exception {
        var preview=new GtinPreviewService(repo,key->adapter).create(adapter.product.key(),Operation.ADD,"","00000000000017");
        var job=runner.confirm(List.of(preview));adapter.failure=new com.vncode.app.integration.listing.ListingRejectedException(Marketplace.WILDBERRIES,403,"fixture price permission denied");
        runner.runJob(job);var item=repo.jobItems(job).getFirst();assertEquals(Status.FAILED,item.status());assertEquals("WB_AUTH_REQUIRED",repo.issue(item.id()));assertNull(item.dispatchedAt());assertEquals(0,adapter.sent);
    }
    @Test void rateLimitDuringSourceRefreshKeepsJobQueuedWithoutPause()throws Exception {
        boolean[] limited={false};var rate=rateLimit();var coordinator=new GtinSyncCoordinator(repo,key->adapter,Clock.systemUTC(),1,Duration.ZERO,cohort->{if(limited[0])throw rate;});
        var preview=new GtinPreviewService(repo,key->adapter).create(adapter.product.key(),Operation.ADD,"","00000000000017");var job=coordinator.confirm(List.of(preview));limited[0]=true;
        coordinator.runJob(job);var item=repo.jobItems(job).getFirst();assertEquals(Status.QUEUED,item.status());assertFalse(repo.paused(job));assertEquals("API_COOLDOWN",repo.issue(item.id()));assertEquals(0,adapter.sent);
        limited[0]=false;coordinator.runJob(job);assertEquals(Status.SUCCEEDED,repo.jobItems(job).getFirst().status());assertEquals(1,adapter.sent);
    }
    @Test void rateLimitDuringBatchPreparationKeepsJobQueuedWithoutPause()throws Exception {
        var preview=new GtinPreviewService(repo,key->adapter).create(adapter.product.key(),Operation.ADD,"","00000000000017");var job=runner.confirm(List.of(preview));adapter.prepareFailure=rateLimit();
        runner.runJob(job);var item=repo.jobItems(job).getFirst();assertEquals(Status.QUEUED,item.status());assertFalse(repo.paused(job));assertEquals("API_COOLDOWN",repo.issue(item.id()));assertEquals(0,adapter.sent);
        adapter.prepareFailure=null;runner.runJob(job);assertEquals(Status.SUCCEEDED,repo.jobItems(job).getFirst().status());assertEquals(1,adapter.sent);
    }
    @Test void rateLimitDuringValidationRequeuesWithoutFalseFailure()throws Exception {
        var preview=new GtinPreviewService(repo,key->adapter).create(adapter.product.key(),Operation.ADD,"","00000000000017");var job=runner.confirm(List.of(preview));adapter.validationLimited=true;
        runner.runJob(job);var item=repo.jobItems(job).getFirst();assertEquals(Status.QUEUED,item.status());assertFalse(repo.paused(job));assertEquals("API_COOLDOWN",repo.issue(item.id()));assertEquals(0,adapter.sent);
        adapter.validationLimited=false;runner.runJob(job);assertEquals(Status.SUCCEEDED,repo.jobItems(job).getFirst().status());assertEquals(1,adapter.sent);
    }
    @Test void backgroundDefersLongCooldownWithoutHoldingWorkerOrSendingGtin()throws Exception {
        var preview=new GtinPreviewService(repo,key->adapter).create(adapter.product.key(),Operation.ADD,"","00000000000017");var job=runner.confirm(List.of(preview));adapter.submitDeferred=true;
        long start=System.nanoTime();try(var deferral=com.vncode.app.shared.RateLimitDeferral.enter()){runner.runJob(job);}
        assertTrue(Duration.ofNanos(System.nanoTime()-start).compareTo(Duration.ofSeconds(3))<0);
        var item=repo.jobItems(job).getFirst();assertEquals(Status.QUEUED,item.status());assertFalse(repo.paused(job));assertNull(item.dispatchedAt());assertEquals(0,adapter.sent);
        adapter.submitDeferred=false;runner.runJob(job);assertEquals(Status.SUCCEEDED,repo.jobItems(job).getFirst().status());assertEquals(1,adapter.sent);
    }
    private static com.vncode.app.integration.listing.ListingRateLimitException rateLimit(){return new com.vncode.app.integration.listing.ListingRateLimitException(new okhttp3.Headers.Builder().add("Retry-After","1").build());}
    static class BatchFixture extends GtinPreviewServiceTest.Adapter implements GtinBatchAdapter {
        PreparationState state=PreparationState.READY;int sent;Runnable beforeGuard=()->{};Exception failure,prepareFailure;boolean validationLimited,submitDeferred;
        @Override public void prepareBatch(Collection<ProductKey> keys)throws Exception {if(prepareFailure!=null)throw prepareFailure;}
        @Override public void validateIdentity(ProductSnapshot target,RegisteredGtin source){if(validationLimited)throw new java.io.UncheckedIOException(rateLimit());super.validateIdentity(target,source);}
        @Override public void validateRegistrations(Collection<String> gtins){}
        @Override public List<List<Preview>> partition(List<Preview> previews){return List.of(List.copyOf(previews));}
        @Override public Preparation beforeSubmit(List<JobItem> items,Runnable guard,Runnable priceConsentGuard,SqlGuard claimGuard)throws Exception {assertEquals(1,items.stream().map(JobItem::jobId).distinct().count());beforeGuard.run();guard.run();if(failure!=null)throw failure;return new Preparation(state,state==PreparationState.PENDING?"WB_PRICE_READBACK_PENDING":state==PreparationState.FAILED?"WB_PRICE_REJECTED":"");}
        @Override public Map<ProductKey,Submission> submitBatch(List<Preview> previews,Runnable guard){if(submitDeferred)com.vncode.app.shared.RateLimitDeferral.waiting(Duration.ofMinutes(15).toNanos());guard.run();sent++;return Map.of(previews.getFirst().before().key(),new Submission("fixture",Instant.now()));}
        @Override public Map<ProductKey,VerificationResult> verifyBatch(List<JobItem> items){return Map.of(items.getFirst().preview().before().key(),new VerificationResult(Verification.APPLIED));}
    }
}
