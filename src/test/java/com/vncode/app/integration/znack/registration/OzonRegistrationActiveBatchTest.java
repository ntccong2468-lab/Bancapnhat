package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.features.products.ProductSnapshot;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal;
import com.vncode.app.integration.znack.registration.v3.RegistrationJob;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonRegistrationActiveBatchTest {
    @TempDir Path root;
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @Test void aNewSelectionCannotSilentlyAttachToAnExistingBatch() throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        var shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        var scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());
        String now=Instant.now().toString();
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_registration_batches(run_id,shop_id,owner,scope_json,auto_sign,state,created_at,updated_at) VALUES('old',?,?,?,0,'QUEUED',?,?)")){
            p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,new com.google.gson.Gson().toJson(scope));p.setString(4,now);p.setString(5,now);p.executeUpdate();
        }
        var key=new ProductKey(shop.getId(),Marketplace.OZON,"new-product","new-offer");
        var e=assertThrows(IllegalStateException.class,()->new OzonRegistrationBatchRepository().create(shop,List.of(key),false,scope,"new"));
        assertEquals("ZNACK_REGISTRATION_BATCH_ALREADY_RUNNING",e.getMessage());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM ozon_registration_batches")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
    }

    @Test void mismatchedReviewedSelectionRollsBackBatchAndItemsTogether() throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        var shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        var scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());
        var reviewed=new ProductKey(shop.getId(),Marketplace.OZON,"101","offer-1");
        var different=new ProductKey(shop.getId(),Marketplace.OZON,"102","offer-2");
        var snapshot=new ProductSnapshot(reviewed,"r1","Name","","Brand","article","1",List.of(),List.of(),List.of(new com.vncode.app.features.products.ProductVariant(reviewed.variantId(),"article","black","L","",false)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop),"sku","123"));
        var draft=new ZnackCardRegistrationModels.Draft("6204",1,"Name","Brand",Map.of());
        var plan=new RegistrationDynamicPreflight.Plan(snapshot,draft,List.of());
        RegistrationFieldProvenance.save(shop,List.of(RegistrationFieldProvenance.capture(snapshot,snapshot,draft,List.of(),Map.of(),null,false)));
        var consent=RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(plan),Map.of(reviewed,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null)),Map.of(),Map.of(reviewed,RegistrationFieldProvenance.read(shop,snapshot,draft,List.of())),false);
        assertThrows(IllegalArgumentException.class,()->new OzonRegistrationBatchRepository().create(shop,List.of(different),false,scope,"mismatch",consent));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM ozon_registration_batches WHERE run_id='mismatch'")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM ozon_registration_batch_items WHERE run_id='mismatch'")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }

    @Test void exactSourceEvidenceStopsAfterCancellationAndCannotAuthorizeAnUnboundGtin() throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        var shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        var scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());
        var key=new ProductKey(shop.getId(),Marketplace.OZON,"101","offer-1");
        var snapshot=new ProductSnapshot(key,"r1","Name","","Brand","article","1",List.of(),List.of(),List.of(new com.vncode.app.features.products.ProductVariant(key.variantId(),"article","black","L","",false)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop),"sku","123"));
        var draft=new ZnackCardRegistrationModels.Draft("6204",1,"Name","Brand",Map.of());
        var schema=List.<ZnackCardRegistrationModels.Attribute>of();
        RegistrationFieldProvenance.save(shop,List.of(RegistrationFieldProvenance.capture(snapshot,snapshot,draft,schema,Map.of(),null,false)));
        var consent=RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(new RegistrationDynamicPreflight.Plan(snapshot,draft,schema)),Map.of(key,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null)),Map.of(),Map.of(key,RegistrationFieldProvenance.read(shop,snapshot,draft,schema)),true);
        new OzonRegistrationBatchRepository().create(shop,List.of(key),false,scope,"reviewed",consent);
        var blank=ZnackNationalCatalogService.buildPayload("",draft,"");
        SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.require(c,shop,"reviewed",snapshot,schema,blank,RegistrationSourceConsent.Action.CREATE,true);return null;});
        var changed=new ProductSnapshot(key,"r2","Changed after review","","Brand","article","1",List.of(),List.of(),List.of(new com.vncode.app.features.products.ProductVariant(key.variantId(),"article","black","L","",false)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop),"sku","123"));
        assertThrows(RegistrationValidationException.class,()->SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.require(c,shop,"reviewed",changed,schema,blank,RegistrationSourceConsent.Action.CREATE,true);return null;}));
        var unbound=ZnackNationalCatalogService.buildPayload("04601234567893",draft,"");
        assertThrows(RegistrationValidationException.class,()->SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.require(c,shop,"reviewed",snapshot,schema,unbound,RegistrationSourceConsent.Action.CREATE,true);return null;}));
        var owner=OzonCardRegistrationRepository.allocationOwner(shop,scope);
        var command=new RegistrationCommandJournal.Command(RegistrationJob.Action.ALLOCATE_GTIN,RegistrationCommandJournal.ozonAllocationResource(key.cardId(),key.variantId()),"generation|quantity=1");
        var other=new RegistrationCommandJournal.Command(RegistrationJob.Action.ALLOCATE_GTIN,RegistrationCommandJournal.ozonAllocationResource(key.cardId(),"other-variant"),"generation|quantity=1");
        var receipt=new RegistrationCommandJournal().run(owner,List.of(command),sent->{
            assertThrows(RegistrationValidationException.class,()->SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.claimAllocation(c,shop,"reviewed",snapshot,schema,blank,owner,other);return null;}));
            SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.claimAllocation(c,shop,"reviewed",snapshot,schema,blank,owner,command);return null;});
            sent.run();return List.of("04601234567893");
        }).getFirst();
        assertThrows(RegistrationValidationException.class,()->SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.bindAcknowledgedAllocation(c,shop,"reviewed",key,owner,command,"04601234567894");return null;}));
        assertThrows(RegistrationValidationException.class,()->SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.bindAcknowledgedAllocation(c,shop,"reviewed",key,owner,other,"04601234567893");return null;}));
        SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.bindAcknowledgedAllocation(c,shop,"reviewed",key,owner,command,receipt);return null;});
        SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.require(c,shop,"reviewed",snapshot,schema,unbound,RegistrationSourceConsent.Action.CREATE,true);return null;});
        assertTrue(new OzonRegistrationBatchRepository().control(shop,"CANCELLED"));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT phase FROM znack_source_consents WHERE run_id='reviewed'")){assertTrue(r.next());assertEquals("CANCELLED",r.getString(1));}
        assertThrows(RegistrationValidationException.class,()->SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.require(c,shop,"reviewed",snapshot,schema,unbound,RegistrationSourceConsent.Action.CREATE,true);return null;}));
    }
    @Test void unknownAllocationCannotBecomeAnAuthorizedGtinAfterRestart() throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        var shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        var scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings());
        var key=new ProductKey(shop.getId(),Marketplace.OZON,"101","offer-1");
        var snapshot=new ProductSnapshot(key,"r1","Name","","Brand","article","1",List.of(),List.of(),List.of(new com.vncode.app.features.products.ProductVariant(key.variantId(),"article","black","L","",false)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop),"sku","123"));
        var draft=new ZnackCardRegistrationModels.Draft("6204",1,"Name","Brand",Map.of());var schema=List.<ZnackCardRegistrationModels.Attribute>of();
        RegistrationFieldProvenance.save(shop,List.of(RegistrationFieldProvenance.capture(snapshot,snapshot,draft,schema,Map.of(),null,false)));
        var consent=RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(new RegistrationDynamicPreflight.Plan(snapshot,draft,schema)),Map.of(key,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null)),Map.of(),Map.of(key,RegistrationFieldProvenance.read(shop,snapshot,draft,schema)),true);
        new OzonRegistrationBatchRepository().create(shop,List.of(key),false,scope,"unknown",consent);
        var payload=ZnackNationalCatalogService.buildPayload("",draft,"");var owner=OzonCardRegistrationRepository.allocationOwner(shop,scope);
        var command=new RegistrationCommandJournal.Command(RegistrationJob.Action.ALLOCATE_GTIN,RegistrationCommandJournal.ozonAllocationResource(key.cardId(),key.variantId()),"generation|quantity=1");
        assertThrows(java.io.IOException.class,()->new RegistrationCommandJournal().run(owner,List.of(command),sent->{
            SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.claimAllocation(c,shop,"unknown",snapshot,schema,payload,owner,command);return null;});sent.run();throw new java.io.IOException("lost response");
        }));
        assertThrows(RegistrationValidationException.class,()->SqliteWriteTransaction.run(c->{RegistrationSourceConsentGuard.bindAcknowledgedAllocation(c,shop,"unknown",key,owner,command,"04601234567893");return null;}));
        assertThrows(IllegalStateException.class,()->new RegistrationCommandJournal().run(owner,List.of(command),sent->List.of("04601234567893")));
    }
    @Test void successfulAllocationPostThenRateLimitedGetStaysUnknownInJournal()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();var shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        var owner=OzonCardRegistrationRepository.allocationOwner(shop,ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings()));
        var command=new RegistrationCommandJournal.Command(RegistrationJob.Action.ALLOCATE_GTIN,RegistrationCommandJournal.ozonAllocationResource("101","offer"),"generation|quantity=1");
        var reads=new java.util.concurrent.atomic.AtomicInteger();var posts=new java.util.concurrent.atomic.AtomicInteger();
        var api=new com.vncode.app.integration.znack.ZnackApiClient(){
            @Override public com.google.gson.JsonElement generatedGtins(String b,String t)throws java.io.IOException{if(reads.getAndIncrement()==0)return com.google.gson.JsonParser.parseString("{\"result\":{\"drafts\":[]}}");throw new com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException("rate limit",429,"");}
            @Override public com.google.gson.JsonElement generateGtins(String b,String t,int q){posts.incrementAndGet();return com.google.gson.JsonParser.parseString("{\"result\":{}}");}
        };
        var catalog=new ZnackNationalCatalogService(api,null,null,ZnackModels.Settings.empty());
        assertThrows(java.io.IOException.class,()->new RegistrationCommandJournal().run(owner,List.of(command),sent->List.of(catalog.generateFreshOne("token",java.util.Set.of(),sent))));
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT state FROM znack_v3_dispatches WHERE owner_key=? AND resource=?")){p.setString(1,owner.key());p.setString(2,command.resource());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("UNKNOWN",r.getString(1));}}
        assertThrows(IllegalStateException.class,()->new RegistrationCommandJournal().run(owner,List.of(command),sent->List.of(catalog.generateFreshOne("token",java.util.Set.of(),sent))));assertEquals(1,posts.get());
    }
}
