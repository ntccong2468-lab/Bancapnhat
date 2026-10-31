package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal;
import com.vncode.app.integration.znack.registration.v3.RegistrationJob;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;
import static org.junit.jupiter.api.Assertions.*;

/** Regression using the actual SQLite native checkpoint and durable dispatch journal. */
public class OzonAllocationRecoveryTest {
    @TempDir Path root;
    private OzonCardRegistrationWorkflowTest fixture;
    private String gtin;
    private RegistrationIntent intent;

    @BeforeEach void setup()throws Exception {
        fixture=new OzonCardRegistrationWorkflowTest();fixture.root=root;fixture.setup();
        var session=fixture.session();var scope=session.scope();var prepared=fixture.workflow.prepare(fixture.shop,fixture.key);
        var source=prepared.snapshot();var plan=new RegistrationDynamicPreflight.Plan(source,fixture.draft,prepared.required(),prepared.documents());
        RegistrationFieldProvenance.save(fixture.shop,List.of(RegistrationFieldProvenance.capture(source,source,fixture.draft,prepared.required(),java.util.Map.of(),null,false)));
        var targets=java.util.Map.of(fixture.key,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null));
        var approval=RegistrationSourceConsent.prepare(fixture.shop,java.util.UUID.randomUUID(),List.of(plan),targets,java.util.Map.of(),RegistrationFieldProvenance.readBatch(fixture.shop,List.of(plan)),false);
        String run=OzonRegistrationAttemptReports.queue(fixture.shop,List.of(fixture.key));new OzonRegistrationBatchRepository().create(fixture.shop,List.of(fixture.key),false,scope,run,approval);intent=new RegistrationIntent(run,0);
        assertTrue(fixture.repository.claim(fixture.shop,fixture.key,"9001","",scope,false,fixture.draft.tnved()));
        fixture.repository.rememberReview(fixture.key,RegistrationReviewedValues.productRevision(fixture.snapshot),
                RegistrationReviewedValues.schemaRevision(prepared.required()),ZnackNationalCatalogService.buildPayload("",fixture.draft,"").toString());
        var owner=OzonCardRegistrationRepository.allocationOwner(fixture.shop,scope);
        var command=new RegistrationCommandJournal.Command(RegistrationJob.Action.ALLOCATE_GTIN,
                RegistrationCommandJournal.ozonAllocationResource(fixture.key.cardId(),fixture.key.variantId()),
                fixture.repository.allocationGeneration(fixture.key)+"|quantity=1");
        gtin=GtinWriteTestFixture.GTIN;
        var journal=new RegistrationCommandJournal();
        assertEquals(List.of(gtin),journal.run(owner,RegistrationCommandJournal.allocationPool(scope.inn(),scope.environment()),List.of(command),sent->{
            assertTrue(fixture.repository.transition(fixture.key,Status.CHECKING,Status.ALLOCATING_GTIN));
            com.vncode.app.config.SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.claimAllocation(c,fixture.shop,run,source,prepared.required(),ZnackNationalCatalogService.buildPayload("",fixture.draft,""),owner,command);return null;});
            sent.run();return List.of(gtin);
        }));
    }
    @AfterEach void cleanup(){fixture.clear();}

    @Test void acknowledgedGtinRestoresCheckpointAfterRestartWithoutAnotherAllocationOrImpliedConsent()throws Exception {
        int allocated=fixture.api.allocations.get(),submitted=fixture.api.submissions.get();
        fixture.workflow=fixture.workflow(Runnable::run);
        assertFalse(fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key));
        assertTrue(fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
        assertEquals(Status.READY_TO_SUBMIT,fixture.row().status());assertEquals(gtin,fixture.row().gtin());
        assertEquals(gtin,JsonParser.parseString(fixture.row().payload()).getAsJsonObject().get("gtin").getAsString());
        assertEquals(allocated,fixture.api.allocations.get());assertEquals(submitted,fixture.api.submissions.get());
        assertFalse(RegistrationSigningConsent.allowed(fixture.shop,fixture.key,gtin));
        assertFalse(RegistrationMarketplaceConsent.allowed(fixture.shop,fixture.key,gtin));
    }
    @Test void resumedSingleCardSubmitsSavedDraftWithoutAllocatingAgain()throws Exception {
        int allocated=fixture.api.allocations.get(),submitted=fixture.api.submissions.get();
        assertTrue(fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
        assertTrue(fixture.workflow.resumeForBatch(fixture.shop,fixture.key,false,null,intent));
        assertEquals(allocated,fixture.api.allocations.get());assertEquals(submitted+1,fixture.api.submissions.get());
        assertEquals(gtin,fixture.row().gtin());assertFalse(fixture.row().feedId().isBlank());
        assertFalse(RegistrationSigningConsent.allowed(fixture.shop,fixture.key,gtin));
    }
    @Test void unknownReceiptAndNewGenerationNeverAuthorizeRecovery()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE znack_v3_dispatches SET state='UNKNOWN',receipt='' WHERE action='ALLOCATE_GTIN'");}
        assertFalse(fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
        assertEquals(Status.ALLOCATING_GTIN,fixture.row().status());assertTrue(fixture.row().gtin().isBlank());
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_v3_dispatches SET state='ACKNOWLEDGED',receipt=? WHERE action='ALLOCATE_GTIN'")){p.setString(1,gtin);p.executeUpdate();}
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE ozon_card_registrations SET allocation_generation='different-generation' WHERE shop_id=2");}
        assertFalse(fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
        assertTrue(fixture.row().gtin().isBlank());
    }
    @Test void changedProductDoesNotReuseAnOldReviewedDraft()throws Exception {
        fixture.sourceTnved("6104");int submitted=fixture.api.submissions.get();
        assertThrows(RegistrationValidationException.class,()->fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
        assertEquals(submitted,fixture.api.submissions.get());assertTrue(fixture.row().gtin().isBlank());
    }
    @Test void missingReviewedDraftOrLegacyGenerationCannotRestoreOrSubmit()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE ozon_card_registrations SET payload_json='' WHERE shop_id=2");}
        assertThrows(RuntimeException.class,()->fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
        assertTrue(fixture.row().gtin().isBlank());assertEquals(0,fixture.api.submissions.get());
    }
    @Test void recoveryAfterRestartBeforeSubmittingRetainsTheSameGtin()throws Exception {
        assertTrue(fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
        fixture.workflow=fixture.workflow(Runnable::run);
        assertEquals(Status.READY_TO_SUBMIT,fixture.row().status());
        assertTrue(fixture.workflow.resumeForBatch(fixture.shop,fixture.key,false,null,intent));
        assertEquals(0,fixture.api.allocations.get());assertEquals(1,fixture.api.submissions.get());assertEquals(gtin,fixture.row().gtin());
    }
    @Test void changedCertificateOrSchemaCannotRestorePreviousOwnersApproval()throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_card_registrations SET schema_revision=? WHERE shop_id=2")){
            p.setString(1,"0".repeat(64));p.executeUpdate();
        }
        assertThrows(RegistrationValidationException.class,()->fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE znack_settings SET signer_certificate='changed' WHERE shop_id=2");}
        assertThrows(ZnackRegistrationScope.ScopeChangedException.class,()->fixture.workflow.recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent));
        assertTrue(fixture.row().gtin().isBlank());assertEquals(0,fixture.api.submissions.get());
    }
    @Test void concurrentRecoveryClaimsOnlyOneNativeCheckpoint()throws Exception {
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var one=workers.submit(()->{gate.await();return fixture.workflow(Runnable::run).recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent);});
            var two=workers.submit(()->{gate.await();return fixture.workflow(Runnable::run).recoverAcknowledgedForBatch(fixture.shop,fixture.key,intent);});
            gate.countDown();int recovered=(one.get(15,java.util.concurrent.TimeUnit.SECONDS)?1:0)+(two.get(15,java.util.concurrent.TimeUnit.SECONDS)?1:0);
            assertEquals(1,recovered);assertEquals(gtin,fixture.row().gtin());assertEquals(Status.READY_TO_SUBMIT,fixture.row().status());
            assertEquals(0,fixture.api.allocations.get());assertEquals(0,fixture.api.submissions.get());
        }
    }
    @Test void twoSeparateProcessesCannotBothRecoverTheSameReceipt()throws Exception {
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var command=List.of(javaExecutable,"-Dvncode.appdata.dir="+root,"-cp",classpath,OzonAllocationRecoveryTest.class.getName(),"recover",intent.runId());
        var first=new ProcessBuilder(command).redirectErrorStream(true).start();
        var second=new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(first.waitFor(25,java.util.concurrent.TimeUnit.SECONDS));assertTrue(second.waitFor(25,java.util.concurrent.TimeUnit.SECONDS));
        String a=new String(first.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        String b=new String(second.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0,first.exitValue(),a);assertEquals(0,second.exitValue(),b);
        assertEquals(1,(a.contains("RECOVERED=true")?1:0)+(b.contains("RECOVERED=true")?1:0));
        assertEquals(gtin,fixture.row().gtin());assertEquals(Status.READY_TO_SUBMIT,fixture.row().status());
    }
    /** Child-process entry point: the test uses synthetic identities only and never calls a network. */
    public static void main(String[] args)throws Exception {
        if(args.length!=2||!args[0].equals("recover"))throw new IllegalArgumentException("invalid test invocation");
        var repository=new OzonCardRegistrationRepository();
        var key=new com.vncode.app.features.products.ProductKey(2,com.vncode.app.integration.marketplace.Marketplace.OZON,"501","fixture-offer");
        var shop=new com.vncode.app.models.Shop(2,"Fixture",com.vncode.app.integration.marketplace.Marketplace.OZON,"fixture-client","fixture-token");
        var row=repository.find(key);var scope=new com.google.gson.Gson().fromJson(row.scopeJson(),ZnackRegistrationScope.class);
        var revisions=repository.reviewedRevisions(key);
        boolean recovered=repository.recoverAcknowledgedAllocation(shop,key,row,scope,revisions.getFirst(),revisions.getLast(),new RegistrationIntent(args[1],0)).isPresent();
        System.out.println("RECOVERED="+recovered);
    }
}
