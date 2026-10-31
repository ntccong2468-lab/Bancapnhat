package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.features.products.ProductSnapshot;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal;
import com.vncode.app.integration.znack.registration.v3.RegistrationJob;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** The WB allocation receipt and approved selection survive a native-checkpoint crash. */
class WbConsentRecoveryTest {
    @TempDir Path root;
    private com.vncode.app.models.Shop shop;
    private ZnackCardRegistrationRepository repository;
    private ZnackCardRegistrationWorkflow workflow;
    private ProductKey key;
    private Sku sku;
    private Draft draft;
    private RegistrationIntent intent;
    private ProductSnapshot snapshot;
    private List<Attribute> schema;
    private ZnackCardRegistrationWorkflow.CatalogSession session;

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,101,'ARTICLE','Fixture','2026-10-06T00:00:00Z')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,11,101,'XL')");
        }
        repository=RegistrationWorkflowTestFixtures.repository(shop);
        sku=repository.search(new SearchCriteria(shop.getId(),"",List.of(),"ALL",20,0)).getFirst();
        key=new ProductKey(shop.getId(),Marketplace.WILDBERRIES,"101","11");
        snapshot=new ProductSnapshot(key,"r1","Fixture","","Brand","ARTICLE","1",List.of(),List.of(),List.of(new com.vncode.app.features.products.ProductVariant("11","ARTICLE","","XL","",false)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));
        draft=new Draft("6204",1,"Fixture","Brand",Map.of());schema=List.of();
        RegistrationFieldProvenance.save(shop,List.of(RegistrationFieldProvenance.capture(snapshot,snapshot,draft,schema,Map.of(),null,false)));
        var consent=RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(new RegistrationDynamicPreflight.Plan(snapshot,draft,schema)),
                Map.of(key,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null)),Map.of(),
                Map.of(key,RegistrationFieldProvenance.read(shop,snapshot,draft,schema)),false);
        var scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());
        new ZnackRegistrationBatchStore().create(shop,"wb-recovery",List.of(sku),false,new com.google.gson.Gson().toJson(scope),consent);
        intent=new RegistrationIntent("wb-recovery",0);
        assertTrue(repository.claim(shop.getId(),sku,RegistrationAction.REGISTER));
        repository.updateProgress(shop.getId(),sku.chrtId(),Status.ALLOCATING_GTIN,null,null,null,null);
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var api=new ZnackApiClient();var auth=new ZnackAuthService(api,null);
        var catalog=new ZnackNationalCatalogService(api,auth,(bytes,context)->{throw new AssertionError("no signing");},settings);
        session=new ZnackCardRegistrationWorkflow.CatalogSession("fixture-token",catalog,scope);
        workflow=RegistrationWorkflowTestFixtures.workflow(repository,ignored->session,Runnable::run);
        var owner=workflow.commandOwner(shop,session);
        var journal=new RegistrationCommandJournal();
        var command=new RegistrationCommandJournal.Command(RegistrationJob.Action.ALLOCATE_GTIN,"101/11",journal.allocationGeneration(shop.getId(),101,11)+"|quantity=1");
        var blank=ZnackNationalCatalogService.buildPayload("",draft,"");
        assertEquals(GtinWriteTestFixture.GTIN,journal.run(owner,RegistrationCommandJournal.allocationPool(scope.inn(),scope.environment()),List.of(command),sent->{
            SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.claimAllocation(c,shop,intent.runId(),snapshot,schema,blank,owner,command);return null;});
            sent.run();return List.of(GtinWriteTestFixture.GTIN);
        }).getFirst());
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    private String phase() throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT phase FROM znack_source_consents WHERE run_id=?")){
            p.setString(1,intent.runId());try(var r=p.executeQuery()){assertTrue(r.next());return r.getString(1);}
        }
    }
    @Test void acknowledgedReceiptRecoversTheApprovedNativeCheckpoint() throws Exception {
        assertEquals("ALLOCATING",phase());
        assertTrue(workflow.recoverAcknowledgedForBatch(shop,repository.find(shop.getId(),sku.chrtId()),draft,intent));
        assertEquals("ALLOCATED",phase());assertEquals(GtinWriteTestFixture.GTIN,repository.find(shop.getId(),sku.chrtId()).gtin());
        assertEquals(Status.READY_TO_SUBMIT,repository.find(shop.getId(),sku.chrtId()).status());
        assertFalse(RegistrationSigningConsent.allowed(shop,key,GtinWriteTestFixture.GTIN));
    }
    @Test void cancellationAfterAckStopsRecoveryAndDoesNotBindGtin() throws Exception {
        assertTrue(new ZnackRegistrationBatchStore().control(shop,intent.runId(),"CANCELLED","cancelled"));
        assertEquals("CANCELLED",phase());
        assertThrows(RegistrationValidationException.class,()->workflow.recoverAcknowledgedForBatch(shop,repository.find(shop.getId(),sku.chrtId()),draft,intent));
        assertFalse(repository.find(shop.getId(),sku.chrtId()).hasGtin());
    }
    @Test void unknownReceiptCannotBecomeAnAuthorizedGtin() throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE znack_v3_dispatches SET state='UNKNOWN',receipt='' WHERE action='ALLOCATE_GTIN'");}
        assertFalse(workflow.recoverAcknowledgedForBatch(shop,repository.find(shop.getId(),sku.chrtId()),draft,intent));
        assertEquals("ALLOCATING",phase());assertFalse(repository.find(shop.getId(),sku.chrtId()).hasGtin());
    }
    private void acknowledgedFeed() throws Exception {
        assertTrue(workflow.recoverAcknowledgedForBatch(shop,repository.find(shop.getId(),sku.chrtId()),draft,intent));
        var payload=com.google.gson.JsonParser.parseString(repository.payload(shop.getId(),sku.chrtId())).getAsJsonObject();
        SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.claimSubmission(c,shop,intent,snapshot,schema,payload);return null;});
        repository.updateProgress(shop.getId(),sku.chrtId(),Status.FEED_SUBMITTING,null,null,null,null);
        var owner=workflow.commandOwner(shop,session);var command=new RegistrationCommandJournal.Command(RegistrationJob.Action.SUBMIT,"101/11",payload.toString());
        assertEquals("feed-ack",new RegistrationCommandJournal().run(owner,List.of(command),sent->{sent.run();return List.of("feed-ack");}).getFirst());
    }
    @Test void acknowledgedFeedRestoresNativeReceiptAndConsentWithoutAnotherPost() throws Exception {
        acknowledgedFeed();
        assertTrue(workflow.recoverAcknowledgedFeedForBatch(shop,repository.find(shop.getId(),sku.chrtId()),intent));
        assertEquals("feed-ack",repository.find(shop.getId(),sku.chrtId()).feedId());assertEquals("SUBMITTED",phase());
        assertFalse(workflow.recoverAcknowledgedFeedForBatch(shop,repository.find(shop.getId(),sku.chrtId()),intent));
    }
    @Test void cancelledOperationStillPreservesAcknowledgedFeedButNeverReauthorizes() throws Exception {
        acknowledgedFeed();assertTrue(new ZnackRegistrationBatchStore().control(shop,intent.runId(),"CANCELLED","cancelled"));
        assertTrue(workflow.recoverAcknowledgedFeedForBatch(shop,repository.find(shop.getId(),sku.chrtId()),intent));
        assertEquals("feed-ack",repository.find(shop.getId(),sku.chrtId()).feedId());assertEquals("CANCELLED",phase());
    }
    @Test void unknownOrDifferentPayloadNeverRestoresAFeed() throws Exception {
        acknowledgedFeed();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE znack_v3_dispatches SET state='UNKNOWN',receipt='' WHERE action='SUBMIT'");}
        assertFalse(workflow.recoverAcknowledgedFeedForBatch(shop,repository.find(shop.getId(),sku.chrtId()),intent));
        assertEquals("SUBMITTING",phase());assertFalse(repository.find(shop.getId(),sku.chrtId()).hasFeed());
    }
}
