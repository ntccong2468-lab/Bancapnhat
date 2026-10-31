package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal;
import com.vncode.app.integration.znack.registration.v3.RegistrationJob;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationAllocationAtomicTest {
    @TempDir Path root;
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    @Test void ozonAckAndNativeCheckpointAreOneTransaction()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();var shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        var scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());
        var key=new ProductKey(shop.getId(),Marketplace.OZON,"101","offer-1");
        var snapshot=new ProductSnapshot(key,"r1","Name","","Brand","article","1",List.of(),List.of(),List.of(new ProductVariant("offer-1","article","Blue","L","",false)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop),"sku","123"));
        var draft=new ZnackCardRegistrationModels.Draft("6204",1,"Name","Brand",Map.of());var schema=List.<ZnackCardRegistrationModels.Attribute>of();
        RegistrationFieldProvenance.save(shop,List.of(RegistrationFieldProvenance.capture(snapshot,snapshot,draft,schema,Map.of(),null,false)));
        var consent=RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(new RegistrationDynamicPreflight.Plan(snapshot,draft,schema)),Map.of(key,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null)),Map.of(),Map.of(key,RegistrationFieldProvenance.read(shop,snapshot,draft,schema)),true);
        new OzonRegistrationBatchRepository().create(shop,List.of(key),false,scope,"run-atomic",consent);
        var repo=new OzonCardRegistrationRepository();assertTrue(repo.claim(shop,key,"123","",scope,false));assertTrue(repo.transition(key,Status.CHECKING,Status.ALLOCATING_GTIN));
        var owner=OzonCardRegistrationRepository.allocationOwner(shop,scope);String generation=repo.allocationGeneration(key);
        var command=new RegistrationCommandJournal.Command(RegistrationJob.Action.ALLOCATE_GTIN,RegistrationCommandJournal.ozonAllocationResource(key.cardId(),key.variantId()),generation+"|quantity=1");
        var blank=ZnackNationalCatalogService.buildPayload("",draft,"");String gtin=GtinWriteTestFixture.GTIN;
        assertEquals(List.of(gtin),new RegistrationCommandJournal().run(owner,List.of(command),sent->{SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.claimAllocation(c,shop,"run-atomic",snapshot,schema,blank,owner,command);return null;});sent.run();return List.of(gtin);}));
        var receipt=new RegistrationAllocationReceipt(gtin,owner,command,new RegistrationIntent("run-atomic",0));JsonObject payload=ZnackNationalCatalogService.buildPayload(gtin,draft,"");
        // Simulate a failure after the consent bind but before the native UPDATE.
        assertTrue(repo.transition(key,Status.ALLOCATING_GTIN,Status.CHECKING));
        assertThrows(IllegalStateException.class,()->repo.generatedWithSourceReceipt(shop,key,scope,payload.toString(),receipt));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT phase FROM znack_source_consents WHERE run_id='run-atomic'")){assertTrue(r.next());assertEquals("ALLOCATING",r.getString(1));}
        assertTrue(repo.find(key).gtin().isBlank());assertTrue(repo.transition(key,Status.CHECKING,Status.ALLOCATING_GTIN));
        repo.generatedWithSourceReceipt(shop,key,scope,payload.toString(),receipt);
        assertEquals(Status.READY_TO_SUBMIT,repo.find(key).status());assertEquals(gtin,repo.find(key).gtin());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT phase FROM znack_source_consents WHERE run_id='run-atomic'")){assertTrue(r.next());assertEquals("ALLOCATED",r.getString(1));}
        var intent=new RegistrationIntent("run-atomic",0);
        SqliteWriteTransaction.run(c->{RegistrationRepairConsent.grantIfApproved(c,shop,key,scope,intent,snapshot,schema,payload);return null;});
        var proof=SqliteWriteTransaction.run(c->RegistrationSourceConsentGuard.require(c,shop,intent.runId(),snapshot,schema,payload,RegistrationSourceConsent.Action.CREATE,true));
        assertEquals(Boolean.TRUE,SqliteWriteTransaction.run(c->RegistrationRepairConsent.allowed(c,shop,key,scope,gtin,payload,proof)));
        var changed=payload.deepCopy();changed.addProperty("unexpected","different");
        assertEquals(Boolean.FALSE,SqliteWriteTransaction.run(c->RegistrationRepairConsent.allowed(c,shop,key,scope,gtin,changed,proof)));
        var first=payload.deepCopy();first.addProperty("good_id",91);first.addProperty("is_kit",true);
        var firstProposal=new CatalogAutomaticRepair.Proposal(payload,first,List.of(new CatalogAutomaticRepair.Change("is_kit","false","true","proof")),"rejected",91);
        assertEquals(Boolean.TRUE,SqliteWriteTransaction.run(c->CatalogAutomaticRepairLedger.reserve(c,scope,gtin,"feed-1",firstProposal)));
        assertEquals(Boolean.TRUE,SqliteWriteTransaction.run(c->RegistrationRepairConsent.allowed(c,shop,key,scope,gtin,first,proof)));
        var second=first.deepCopy();second.remove("is_kit");var secondProposal=new CatalogAutomaticRepair.Proposal(first,second,List.of(new CatalogAutomaticRepair.Change("is_kit","true","false","proof")),"rejected",91);
        assertEquals(Boolean.TRUE,SqliteWriteTransaction.run(c->CatalogAutomaticRepairLedger.reserve(c,scope,gtin,"feed-2",secondProposal)));
        assertEquals(Boolean.TRUE,SqliteWriteTransaction.run(c->RegistrationRepairConsent.allowed(c,shop,key,scope,gtin,second,proof)));
        var third=second.deepCopy();third.addProperty("is_kit",true);var thirdProposal=new CatalogAutomaticRepair.Proposal(second,third,List.of(new CatalogAutomaticRepair.Change("is_kit","false","true","proof")),"rejected",91);
        assertEquals(Boolean.FALSE,SqliteWriteTransaction.run(c->CatalogAutomaticRepairLedger.reserve(c,scope,gtin,"feed-3",thirdProposal)));
        try(var c=Database.getConnection();var q=c.createStatement()){q.executeUpdate("UPDATE ozon_registration_batches SET state='CANCELLED' WHERE run_id='run-atomic'");}
        assertThrows(RegistrationValidationException.class,()->SqliteWriteTransaction.run(c->RegistrationRepairConsent.allowed(c,shop,key,scope,gtin,payload,proof)));
    }
}
