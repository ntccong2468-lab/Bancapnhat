package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
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
        repo.saveCatalog(1,Marketplace.WILDBERRIES,List.of(new RegisteredGtin("00000000000017","PANTS","Đen","XL",true,true,"NK",Instant.now())));
        runner=new GtinSyncCoordinator(repo,k->adapter,Clock.systemUTC(),1,Duration.ZERO);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    Preview preview()throws Exception{return new GtinPreviewService(repo,k->adapter).create(adapter.product.key(),Operation.REPLACE,"old","00000000000017");}
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
}
