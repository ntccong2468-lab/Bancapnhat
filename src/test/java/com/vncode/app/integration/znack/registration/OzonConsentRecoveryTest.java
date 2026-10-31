package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal;
import com.vncode.app.integration.znack.registration.v3.RegistrationJob;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic SQLite recovery: one approved operation, one journal ACK and no network write. */
class OzonConsentRecoveryTest {
    @TempDir Path root;
    private OzonCardRegistrationWorkflowTest fixture;
    private RegistrationIntent intent;
    private String gtin;

    @BeforeEach void setup() throws Exception {
        fixture=new OzonCardRegistrationWorkflowTest();fixture.root=root;fixture.setup();
        var prepared=fixture.workflow.prepare(fixture.shop,fixture.key);
        var snapshot=prepared.snapshot();var schema=prepared.required();var draft=fixture.draft;
        RegistrationFieldProvenance.save(fixture.shop,List.of(RegistrationFieldProvenance.capture(snapshot,snapshot,draft,schema,Map.of(),null,false)));
        var plan=new RegistrationDynamicPreflight.Plan(snapshot,draft,schema);
        var consent=RegistrationSourceConsent.prepare(fixture.shop,UUID.randomUUID(),List.of(plan),
                Map.of(fixture.key,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null)),
                Map.of(),Map.of(fixture.key,RegistrationFieldProvenance.read(fixture.shop,snapshot,draft,schema)),true);
        var scope=fixture.session().scope();new OzonRegistrationBatchRepository().create(fixture.shop,List.of(fixture.key),false,scope,"recovery",consent);
        intent=new RegistrationIntent("recovery",0);
        assertTrue(fixture.repository.claim(fixture.shop,fixture.key,"9001","",scope,false,draft.tnved()));
        fixture.repository.rememberReview(fixture.key,RegistrationReviewedValues.productRevision(snapshot),
                RegistrationReviewedValues.schemaRevision(schema),ZnackNationalCatalogService.buildPayload("",draft,"").toString());
        var owner=OzonCardRegistrationRepository.allocationOwner(fixture.shop,scope);
        var command=new RegistrationCommandJournal.Command(RegistrationJob.Action.ALLOCATE_GTIN,
                RegistrationCommandJournal.ozonAllocationResource(fixture.key.cardId(),fixture.key.variantId()),
                fixture.repository.allocationGeneration(fixture.key)+"|quantity=1");
        var blank=ZnackNationalCatalogService.buildPayload("",draft,"");gtin=GtinWriteTestFixture.GTIN;
        assertEquals(gtin,new RegistrationCommandJournal().run(owner,RegistrationCommandJournal.allocationPool(scope.inn(),scope.environment()),List.of(command),sent->{
            assertTrue(fixture.repository.transition(fixture.key,Status.CHECKING,Status.ALLOCATING_GTIN));
            SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.claimAllocation(c,fixture.shop,intent.runId(),snapshot,schema,blank,owner,command);return null;});
            sent.run();return List.of(gtin);
        }).getFirst());
    }
    @AfterEach void cleanup(){fixture.clear();}

    private String phase() throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT phase FROM znack_source_consents WHERE run_id=?")){
            p.setString(1,intent.runId());try(var r=p.executeQuery()){assertTrue(r.next());return r.getString(1);}
        }
    }

    @Test void acknowledgedReceiptBindsSameOperationAtomicallyAfterRestart() throws Exception {
        fixture.workflow=fixture.workflow(Runnable::run);
        assertEquals("ALLOCATING",phase());
        assertTrue(fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
        assertEquals("ALLOCATED",phase());assertEquals(gtin,fixture.row().gtin());
        assertEquals(Status.READY_TO_SUBMIT,fixture.row().status());
        assertEquals(0,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
        assertFalse(RegistrationSigningConsent.allowed(fixture.shop,fixture.key,gtin));
        assertFalse(RegistrationMarketplaceConsent.allowed(fixture.shop,fixture.key,gtin));
        assertFalse(fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
    }

    @Test void cancellingAfterAckPreventsRecoveryAndNeverSends() throws Exception {
        assertTrue(new OzonRegistrationBatchRepository().control(fixture.shop,"CANCELLED"));
        assertEquals("CANCELLED",phase());
        assertThrows(RegistrationValidationException.class,()->fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
        assertTrue(fixture.row().gtin().isBlank());assertEquals(0,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
    }

    @Test void cancellationAfterRecoveryRevokesTheUnsentPost() throws Exception {
        assertTrue(fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
        assertEquals("ALLOCATED",phase());
        var prepared=fixture.workflow.prepare(fixture.shop,fixture.key);
        var payload=com.google.gson.JsonParser.parseString(fixture.row().payload()).getAsJsonObject();
        assertTrue(new OzonRegistrationBatchRepository().control(fixture.shop,"CANCELLED"));
        assertThrows(RegistrationValidationException.class,()->SqliteWriteTransaction.run(c->{
            RegistrationSourceConsentGuard.claimSubmission(c,fixture.shop,intent,prepared.snapshot(),prepared.required(),payload);return null;
        }));
        assertEquals("CANCELLED",phase());assertEquals(gtin,fixture.row().gtin());assertEquals(0,fixture.api.submissions.get());
    }

    @Test void unknownJournalReceiptCannotBeRecoveredOrBound() throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE znack_v3_dispatches SET state='UNKNOWN',receipt='' WHERE action='ALLOCATE_GTIN'");}
        assertFalse(fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
        assertEquals("ALLOCATING",phase());assertTrue(fixture.row().gtin().isBlank());assertEquals(0,fixture.api.submissions.get());
    }
    private void startUnacknowledgedFeed() throws Exception {
        assertTrue(fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
        var prepared=fixture.workflow.prepare(fixture.shop,fixture.key);
        var payload=com.google.gson.JsonParser.parseString(fixture.row().payload()).getAsJsonObject();
        SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.claimSubmission(c,fixture.shop,intent,prepared.snapshot(),prepared.required(),payload);return null;});
        assertTrue(fixture.repository.transition(fixture.key,Status.READY_TO_SUBMIT,Status.FEED_SUBMITTING));
    }
    @Test void explicitPost429RestoresNativeAndConsentTogether() throws Exception {
        startUnacknowledgedFeed();var expected=fixture.row();
        fixture.repository.releaseRejectedFeed429(fixture.shop,fixture.key,fixture.session().scope(),expected,intent);
        assertEquals(Status.READY_TO_SUBMIT,fixture.row().status());assertEquals("ALLOCATED",phase());assertTrue(fixture.row().feedId().isBlank());
    }
    @Test void unknownFeedDoesNotReleaseClaimOrNativeCheckpoint() throws Exception {
        startUnacknowledgedFeed();
        fixture.workflow=fixture.workflow(Runnable::run);
        assertEquals(Status.FEED_SUBMITTING,fixture.row().status());assertEquals("SUBMITTING",phase());
        assertFalse(fixture.workflow.resumeForBatch(fixture.shop,fixture.key,false,null,intent));
        assertEquals(0,fixture.api.submissions.get());
    }

    @Test void reviewedCorrectionChainAllowsTwoRoundsAndPreserves429Origin() throws Exception {
        assertTrue(fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
        var prepared=fixture.workflow.prepare(fixture.shop,fixture.key);var session=fixture.session();var scope=session.scope();
        var first=com.google.gson.JsonParser.parseString(fixture.row().payload()).getAsJsonObject();
        SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.claimSubmission(c,fixture.shop,intent,prepared.snapshot(),prepared.required(),first);return null;});
        SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.acceptedSubmission(c,intent,fixture.key,first);return null;});
        for(int round=1;round<=3;round++){
            try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_card_registrations SET status='ERROR',feed_id=?,good_id=91 WHERE shop_id=? AND product_id=? AND offer_id=?")){
                p.setString(1,"feed-"+round);p.setInt(2,fixture.shop.getId());p.setString(3,fixture.key.cardId());p.setString(4,fixture.key.variantId());p.executeUpdate();
            }
            var row=fixture.row();var original=com.google.gson.JsonParser.parseString(row.payload()).getAsJsonObject();
            var proof=RegistrationRepairConsent.proof(fixture.shop,fixture.key,scope,intent,session,original,(shop,key)->fixture.snapshot);
            assertNotNull(proof,"reviewed source consent should remain bound after restart");
            var corrected=original.deepCopy();corrected.addProperty("good_id",91);corrected.addProperty("is_kit",round%2==1);
            var proposal=new CatalogAutomaticRepair.Proposal(original,corrected,List.of(new CatalogAutomaticRepair.Change("is_kit","",""+(round%2==1),"fixture")),"CATALOG_REJECTED: fixture",91);
            boolean staged=fixture.repository.retryAutomaticRejected(fixture.shop,fixture.key,row,scope,proposal,proof);
            if(round==3){assertFalse(staged,"the two-round bound must survive later attempts");break;}
            assertTrue(staged);var ready=fixture.row();var output=com.google.gson.JsonParser.parseString(ready.payload()).getAsJsonObject();
            var freshProof=RegistrationRepairConsent.proof(fixture.shop,fixture.key,scope,intent,session,output,(shop,key)->fixture.snapshot);
            assertNotNull(freshProof);
            SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.claimCorrection(c,fixture.shop,intent,fixture.key,scope,output,freshProof);return null;});
            assertEquals("SUBMITTING",phase());
            if(round==1){
                // Simulate a directly proven 429/no-write: restore the correction origin, not CREATE.
                SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.releaseKnownUnsent(c,intent,fixture.key,false);return null;});
                assertEquals("SUBMITTED",phase());
                SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.claimCorrection(c,fixture.shop,intent,fixture.key,scope,output,freshProof);return null;});
            }
            SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.acceptedSubmission(c,intent,fixture.key,output);return null;});
            assertEquals("SUBMITTED",phase());fixture.workflow=fixture.workflow(Runnable::run);
        }
        assertEquals(0,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
    }
}
