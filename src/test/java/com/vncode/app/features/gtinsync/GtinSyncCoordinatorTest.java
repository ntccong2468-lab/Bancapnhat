package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseRuntime;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static org.junit.jupiter.api.Assertions.*;

class GtinSyncCoordinatorTest {
    @TempDir Path temp;
    GtinSyncRepository repo;GtinPreviewServiceTest.Adapter adapter;GtinSyncCoordinator runner;
    @BeforeEach void init() throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Test','WILDBERRIES','test')");}
        repo=new GtinSyncRepository();adapter=new GtinPreviewServiceTest.Adapter();
        repo.saveCatalog(1,Marketplace.WILDBERRIES,List.of(new RegisteredGtin("00000000000017","PANTS","Đen","XL",true,true,"NK",Instant.now(),new com.vncode.app.features.catalogsync.CatalogProductData(java.util.Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),List.of()))));
        runner=new GtinSyncCoordinator(repo,k->adapter,Clock.systemUTC(),1,Duration.ZERO,cohort->{});
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    Preview preview()throws Exception{return new GtinPreviewService(repo,k->adapter).create(adapter.product.key(),Operation.REPLACE,"old","00000000000017");}
    @Test void migrationCreatesPriceReceiptTableBeforeCancellingOldQueuedJob()throws Exception {
        var job=repo.createJob(List.of(preview()));
        try(var c=Database.getConnection();var statement=c.createStatement()){statement.execute("DROP TABLE wb_gtin_price_repairs");}
        Database.initDatabase();runner.cancelQueued(job);
        assertEquals(Status.CANCELLED,repo.jobItems(job).getFirst().status());assertTrue(repo.paused(job));
    }
    @Test void deniedConfirmationCreatesNoNewJob()throws Exception {
        var selection=List.of(preview());var prior=LicenseRuntime.service();
        try(var denied=LicenseDomainFixture.denied(temp.resolve("denied-license"))) {
            LicenseRuntime.install(denied);
            try {
                assertThrows(LicenseDeniedException.class,()->runner.confirm(selection));
                assertTrue(repo.history(1,Marketplace.WILDBERRIES).isEmpty());assertEquals(0,adapter.sends);
            }finally{LicenseRuntime.install(prior);}
        }
    }
    @Test void expiryAfterSendClaimPausesKnownUnsentInsteadOfCreatingAmbiguity()throws Exception {
        var job=runner.confirm(List.of(preview()));var prior=LicenseRuntime.service();
        try(var denied=LicenseDomainFixture.denied(temp.resolve("denied-license"))) {
            try {
                runner.runPending(1,Marketplace.WILDBERRIES,item->{if(item.status()==Status.SENDING)LicenseRuntime.install(denied);});
                var item=repo.jobItems(job).getFirst();
                assertEquals(Status.QUEUED,item.status());assertTrue(repo.paused(job));assertEquals(0,adapter.sends);
                assertThrows(LicenseDeniedException.class,()->runner.resume(job));
                LicenseRuntime.install(prior);repo.recoverInterrupted();runner.runPending(1,Marketplace.WILDBERRIES);
                assertEquals(0,adapter.sends,"Restoring rights/restarting does not confirm the paused job");
                runner.resume(job);runner.runPending(1,Marketplace.WILDBERRIES);
                assertEquals(Status.SUCCEEDED,repo.jobItems(job).getFirst().status());assertEquals(1,adapter.sends);
            }finally{LicenseRuntime.install(prior);}
        }
    }
    @Test void revocationInsideAcceptedSubmissionStillStoresAndReconcilesResult()throws Exception {
        var prior=LicenseRuntime.service();
        try(var denied=LicenseDomainFixture.denied(temp.resolve("denied-license"))) {
            var accepting=new GtinPreviewServiceTest.Adapter(){
                @Override public Submission submit(Preview p)throws java.io.IOException {
                    var response=super.submit(p);LicenseRuntime.install(denied);return response;
                }
            };
            var coordinator=new GtinSyncCoordinator(repo,k->accepting,Clock.systemUTC(),1,Duration.ZERO,cohort->{});
            var job=coordinator.confirm(List.of(preview()));
            try {
                coordinator.runPending(1,Marketplace.WILDBERRIES);
                assertEquals(Status.SUCCEEDED,repo.jobItems(job).getFirst().status());assertEquals(1,accepting.sends);
                coordinator.runPending(1,Marketplace.WILDBERRIES);assertEquals(1,accepting.sends);
            }finally{LicenseRuntime.install(prior);}
        }
    }
    @Test void duplicateConfirmCreatesOneJobAndOneMutation()throws Exception {
        var p=preview();var id=runner.confirm(List.of(p));assertEquals(id,runner.confirm(List.of(p)));
        runner.runPending(1,Marketplace.WILDBERRIES);runner.runPending(1,Marketplace.WILDBERRIES);
        assertEquals(1,adapter.sends);assertEquals(Status.SUCCEEDED,repo.history(1,Marketplace.WILDBERRIES).getFirst().status());
    }
    @Test void timeoutThenRestartReconcilesWithoutResending()throws Exception {
        runner.confirm(List.of(preview()));adapter.loseResponse=true;adapter.outcome=Verification.UNKNOWN;
        runner.runPending(1,Marketplace.WILDBERRIES);repo.recoverInterrupted();runner.runPending(1,Marketplace.WILDBERRIES);
        assertEquals(1,adapter.sends);var item=repo.history(1,Marketplace.WILDBERRIES).getFirst();assertEquals(Status.RECONCILE_REQUIRED,item.status());
        adapter.outcome=Verification.APPLIED;runner.reconcile(item.id());
        assertEquals(Status.SUCCEEDED,repo.item(item.id()).status());assertEquals(1,adapter.sends);
    }
    @Test void changedProductOrCapabilityStopsBeforeSending()throws Exception {
        var p=preview();runner.confirm(List.of(p));
        adapter.product=new ProductSnapshot(p.before().key(),"PANTS","Đen","XL",List.of("changed"),"different",Instant.now());
        runner.runPending(1,Marketplace.WILDBERRIES);assertEquals(0,adapter.sends);assertEquals(Status.FAILED,repo.history(1,Marketplace.WILDBERRIES).getFirst().status());
    }
    @Test void pauseAndCancelOnlyUnsentItems()throws Exception {
        var id=runner.confirm(List.of(preview()));runner.pause(id);runner.runPending(1,Marketplace.WILDBERRIES);
        assertEquals(0,adapter.sends);runner.cancelQueued(id);assertEquals(Status.CANCELLED,repo.history(1,Marketplace.WILDBERRIES).getFirst().status());
    }
    @Test void asyncRejectionIsRecordedWithoutRetry()throws Exception {
        runner.confirm(List.of(preview()));adapter.outcome=Verification.REJECTED;runner.runPending(1,Marketplace.WILDBERRIES);
        assertEquals(Status.FAILED,repo.history(1,Marketplace.WILDBERRIES).getFirst().status());assertEquals(1,adapter.sends);
    }
    @Test void cancelCancelsValidationThatHasNotSent()throws Exception {
        var id=runner.confirm(List.of(preview()));var item=repo.jobItems(id).getFirst();
        repo.transition(item.id(),Status.QUEUED,Status.VALIDATING,null);runner.cancelQueued(id);
        assertEquals(Status.CANCELLED,repo.item(item.id()).status());assertEquals(0,adapter.sends);
    }
    @Test void pauseArrivingAtSendClaimLeavesItemResumable()throws Exception {
        var job=runner.confirm(List.of(preview()));var item=repo.jobItems(job).getFirst();
        // Model a pause arriving exactly when the guarded send claim is rejected.
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("""
                CREATE TRIGGER pause_at_send BEFORE UPDATE OF status ON gtin_sync_items
                WHEN NEW.status='SENDING' AND OLD.status='VALIDATING'
                BEGIN
                  UPDATE gtin_sync_jobs SET paused=1 WHERE id=OLD.job_id;
                  SELECT RAISE(IGNORE);
                END
                """);
        }
        runner.runPending(1,Marketplace.WILDBERRIES);
        assertEquals(Status.QUEUED,repo.item(item.id()).status());assertEquals(0,adapter.sends);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DROP TRIGGER pause_at_send");}
        runner.resume(job);runner.runPending(1,Marketplace.WILDBERRIES);
        assertEquals(Status.SUCCEEDED,repo.item(item.id()).status());assertEquals(1,adapter.sends);
    }
    @Test void freshConfirmationAfterUnsentCancellationCreatesNewAttempt()throws Exception {
        var original=runner.confirm(List.of(preview()));runner.cancelQueued(original);
        var next=runner.confirm(List.of(preview()));assertNotEquals(original,next);
        assertEquals(next,runner.confirm(List.of(preview())));
        runner.runPending(1,Marketplace.WILDBERRIES);
        assertEquals(Status.CANCELLED,repo.jobItems(original).getFirst().status());
        assertEquals(Status.SUCCEEDED,repo.jobItems(next).getFirst().status());assertEquals(1,adapter.sends);
    }
    @Test void definitiveRejectionAllowsExplicitNewConfirmation()throws Exception {
        var original=runner.confirm(List.of(preview()));adapter.outcome=Verification.REJECTED;
        runner.runPending(1,Marketplace.WILDBERRIES);
        adapter.outcome=Verification.APPLIED;var next=runner.confirm(List.of(preview()));
        assertNotEquals(original,next);runner.runPending(1,Marketplace.WILDBERRIES);
        assertEquals(2,adapter.sends);assertEquals(Status.SUCCEEDED,repo.jobItems(next).getFirst().status());
    }
    @Test void progressDisplayFailureDoesNotInterruptMutationOrVerification()throws Exception {
        var job=runner.confirm(List.of(preview()));
        assertDoesNotThrow(()->runner.runPending(1,Marketplace.WILDBERRIES,item->{throw new IllegalStateException("display_unavailable");}));
        assertEquals(1,adapter.sends);assertEquals(Status.SUCCEEDED,repo.jobItems(job).getFirst().status());
    }
    @Test void queuedJobMustBeCancelledBeforeDeletingItsShop()throws Exception {
        var job=runner.confirm(List.of(preview()));var shops=new com.vncode.app.features.shop.ShopRepository();
        assertThrows(IllegalStateException.class,()->shops.delete(1));
        assertNotNull(shops.findById(1));assertEquals(Status.QUEUED,repo.jobItems(job).getFirst().status());
        runner.cancelQueued(job);assertEquals(1,shops.delete(1));assertNull(shops.findById(1));
    }
    @Test void unknownRemoteOutcomeKeepsShopAndHistoryUntilReconciled()throws Exception {
        var job=runner.confirm(List.of(preview()));adapter.loseResponse=true;adapter.outcome=Verification.UNKNOWN;
        runner.runPending(1,Marketplace.WILDBERRIES);
        var item=repo.jobItems(job).getFirst();assertEquals(Status.RECONCILE_REQUIRED,item.status());
        var shops=new com.vncode.app.features.shop.ShopRepository();
        assertThrows(IllegalStateException.class,()->shops.delete(1));
        assertNotNull(shops.findById(1));assertEquals(Status.RECONCILE_REQUIRED,repo.item(item.id()).status());
        adapter.outcome=Verification.APPLIED;runner.reconcile(item.id());
        assertEquals(Status.SUCCEEDED,repo.item(item.id()).status());assertEquals(1,shops.delete(1));
    }
    @Test void actualDispatchTimeSurvivesUnknownResponseAndRestartWithoutFreshMutationReads()throws Exception{
        int[] refreshes={0};var coordinator=new GtinSyncCoordinator(repo,k->adapter,Clock.systemUTC(),1,Duration.ZERO,cohort->{refreshes[0]++;assertEquals(java.util.Map.of(adapter.product.key(),"00000000000017"),cohort);});
        var job=coordinator.confirm(List.of(preview()));assertEquals(1,refreshes[0]);assertNull(repo.jobItems(job).getFirst().dispatchedAt());
        adapter.loseResponse=true;adapter.outcome=Verification.UNKNOWN;var before=Instant.now();coordinator.runJob(job);
        var sent=repo.jobItems(job).getFirst();assertEquals(Status.RECONCILE_REQUIRED,sent.status());assertNotNull(sent.dispatchedAt());assertFalse(sent.dispatchedAt().isBefore(before));assertEquals(2,refreshes[0]);
        repo.recoverInterrupted();coordinator.runJob(job);assertEquals(sent.dispatchedAt(),repo.item(sent.id()).dispatchedAt());assertEquals(2,refreshes[0]);assertEquals(1,adapter.sends);
    }
    @Test void transientFreshSourceFailureKeepsQueuedMutationRetryableAndCannotSendOrCreateFalseResult()throws Exception{
        boolean[] fail={false};var unavailable=new java.net.SocketTimeoutException("synthetic source timeout");
        var coordinator=new GtinSyncCoordinator(repo,k->adapter,Clock.systemUTC(),1,Duration.ZERO,cohort->{if(fail[0])throw unavailable;});
        var job=coordinator.confirm(List.of(preview()));fail[0]=true;coordinator.runJob(job);
        var item=repo.jobItems(job).getFirst();assertEquals(Status.QUEUED,item.status());assertFalse(repo.paused(job));assertNull(item.dispatchedAt());assertEquals(0,adapter.sends);
        assertSame(unavailable,assertThrows(java.net.SocketTimeoutException.class,()->coordinator.confirm(List.of(preview()))));assertEquals(1,repo.history(1,Marketplace.WILDBERRIES).size());
        fail[0]=false;coordinator.runJob(job);assertEquals(Status.SUCCEEDED,repo.jobItems(job).getFirst().status());assertEquals(1,adapter.sends);
    }
    @Test void explicitHttp400AfterDispatchIsFailedAndNeverReplayedOnRestart()throws Exception{
        var rejected=new GtinPreviewServiceTest.Adapter(){@Override public Submission submit(Preview p)throws java.io.IOException{sends++;throw new com.vncode.app.integration.listing.ListingRejectedException(Marketplace.WILDBERRIES,400,"synthetic rejected request");}};
        var coordinator=new GtinSyncCoordinator(repo,k->rejected,Clock.systemUTC(),1,Duration.ZERO,cohort->{});var job=coordinator.confirm(List.of(preview()));
        coordinator.runJob(job);var item=repo.jobItems(job).getFirst();assertEquals(Status.FAILED,item.status());assertEquals("WB_HTTP_400",repo.issue(item.id()));assertNotNull(item.dispatchedAt());
        repo.recoverInterrupted();coordinator.runJob(job);assertEquals(1,rejected.sends);assertEquals(Status.FAILED,repo.item(item.id()).status());
    }

}
