package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import com.vncode.app.models.Shop;
import java.net.URI;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationSourceConsentTest {
    @TempDir Path directory;Shop shop;ProductKey key;RegistrationDynamicPreflight.Plan plan;List<RegistrationFieldProvenance.Evidence> facts;
    private final String photo="https://basket-33.wbbasket.ru/fixture.jpg";
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        key=new ProductKey(shop.getId(),Marketplace.WILDBERRIES,"101","201");
        var variant=new ProductVariant("201","Seller article","Blue","L","",false,"48");
        var snapshot=new ProductSnapshot(key,"fixture","Private product","","Private brand","Seller article","category",List.of(new ProductMedia(URI.create(photo),null,null,null,null)),List.of(),List.of(variant),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));
        var draft=new Draft("6103109000","6103",10,"Private product","Private brand",Map.of(),Map.of(),false,false);
        plan=new RegistrationDynamicPreflight.Plan(snapshot,draft,List.of(),null);
        var provenance=RegistrationFieldProvenance.capture(snapshot,snapshot,draft,List.of(),Map.of(),null,false);RegistrationFieldProvenance.save(shop,List.of(provenance));facts=RegistrationFieldProvenance.read(shop,snapshot,draft,List.of());
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    private RegistrationSourceConsent.Prepared prepare(UUID id){return RegistrationSourceConsent.prepare(shop,id,List.of(plan),Map.of(key,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null)),Map.of(key,Set.of(photo)),Map.of(key,facts),true);}

    @Test void approvalAndEnqueueAreOneTransactionAndStoreOnlyHashes()throws Exception{
        var prepared=prepare(UUID.randomUUID());
        assertThrows(IllegalStateException.class,()->SqliteWriteTransaction.run(c->{RegistrationSourceConsent.persist(c,shop,"run-1",List.of(key),prepared);throw new IllegalStateException("fixture rollback");}));
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='znack_source_consents'");var r=p.executeQuery()){assertFalse(r.next());}
        SqliteWriteTransaction.run(c->{RegistrationSourceConsent.persist(c,shop,"run-1",List.of(key),prepared);return null;});
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT * FROM znack_source_consents")){
            assertTrue(r.next());assertEquals("ACTIVE",r.getString("phase"));assertEquals("run-1",r.getString("run_id"));assertEquals(prepared.intent().toString(),r.getString("intent_id"));
            for(int n=1;n<=r.getMetaData().getColumnCount();n++){String cell=Objects.toString(r.getString(n),"");assertFalse(cell.contains("Private product"));assertFalse(cell.contains("Private brand"));assertFalse(cell.contains(photo));assertFalse(cell.contains(shop.getApiKey()));}
        }
    }
    @Test void approvalCannotBeAttachedToDifferentSelectionOrReused()throws Exception{
        var prepared=prepare(UUID.randomUUID());var other=new ProductKey(shop.getId(),Marketplace.WILDBERRIES,"101","202");
        assertThrows(IllegalArgumentException.class,()->SqliteWriteTransaction.run(c->{RegistrationSourceConsent.persist(c,shop,"run-1",List.of(other),prepared);return null;}));
        SqliteWriteTransaction.run(c->{RegistrationSourceConsent.persist(c,shop,"run-1",List.of(key),prepared);return null;});
        assertThrows(SQLException.class,()->SqliteWriteTransaction.run(c->{RegistrationSourceConsent.persist(c,shop,"run-2",List.of(key),prepared);return null;}));
    }
    @Test void existingOrRepairRequiresExactGtinAndPositiveCatalogId(){
        assertThrows(IllegalArgumentException.class,()->new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.EXISTING,"",1L));
        assertThrows(IllegalArgumentException.class,()->new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.REPAIR,GtinWriteTestFixture.GTIN,null));
        assertThrows(IllegalArgumentException.class,()->new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,GtinWriteTestFixture.GTIN,1L));
    }
    @Test void replacementReviewPreservesTheOldIdentitySeparatelyFromTheFutureAllocation()throws Exception{
        assertThrows(IllegalArgumentException.class,()->new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.REPLACE,"",1L));
        assertThrows(IllegalArgumentException.class,()->new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.REPLACE,GtinWriteTestFixture.GTIN,null));
        var target=new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.REPLACE,GtinWriteTestFixture.GTIN,42L);
        var prepared=RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(plan),Map.of(key,target),Map.of(),Map.of(key,facts),false);
        assertEquals(RegistrationSourceConsent.Action.REPLACE,prepared.action(key));
        SqliteWriteTransaction.run(c->{RegistrationSourceConsent.persist(c,shop,"replacement-run",List.of(key),prepared);return null;});
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT action,phase,replaced_gtin_hash,replaced_good_id,expected_gtin_hash,expected_good_id FROM znack_source_consents WHERE run_id=?")){
            p.setString(1,"replacement-run");try(var r=p.executeQuery()){
                assertTrue(r.next());assertEquals("REPLACE",r.getString("action"));assertEquals("ACTIVE",r.getString("phase"));
                assertEquals(ListingIdentity.hash(GtinWriteTestFixture.GTIN),r.getString("replaced_gtin_hash"));assertEquals(42,r.getLong("replaced_good_id"));
                assertEquals("",r.getString("expected_gtin_hash"));assertNull(r.getObject("expected_good_id"));assertFalse(r.next());
            }
        }
    }
    @Test void sellerApprovalCannotUseMissingOrChangedFieldEvidence(){
        var target=Map.of(key,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null));
        assertThrows(IllegalArgumentException.class,()->RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(plan),target,Map.of(),Map.of(key,List.of()),false));
        var changed=new ArrayList<>(facts);changed.removeFirst();
        assertThrows(IllegalArgumentException.class,()->RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(plan),target,Map.of(),Map.of(key,changed),false));
        assertDoesNotThrow(()->RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(plan),target,Map.of(),Map.of(key,facts),false));
    }
    @Test void conflictWhenOneVariantIdHasTwoSizesCannotBeApproved(){
        var original=plan.snapshot();var variants=List.of(new ProductVariant("201","Seller article","Blue","L","",false,"48"),new ProductVariant("201","Seller article","Blue","XL","",false,"50"));
        var conflicting=new ProductSnapshot(key,"fixture","Private product","","Private brand","Seller article","category",original.media(),List.of(),variants,null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));
        var changed=new RegistrationDynamicPreflight.Plan(conflicting,plan.draft(),plan.schema(),null);
        var provenance=RegistrationFieldProvenance.capture(conflicting,conflicting,plan.draft(),plan.schema(),Map.of(),null,false);RegistrationFieldProvenance.save(shop,List.of(provenance));
        var shown=RegistrationFieldProvenance.read(shop,conflicting,plan.draft(),plan.schema());
        assertThrows(IllegalArgumentException.class,()->RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(changed),Map.of(key,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null)),Map.of(),Map.of(key,shown),false));
    }
    @Test void ozonSameOfferWithConflictingSizesCannotBeApproved()throws Exception{
        var ozon=GtinWriteTestFixture.shop(Marketplace.OZON);var offer=new ProductKey(ozon.getId(),Marketplace.OZON,"101","offer-1");
        var variants=List.of(new ProductVariant("offer-1","offer-1","Blue","L","",false,"48"),new ProductVariant("offer-1","offer-1","Blue","XL","",false,"50"));
        var snapshot=new ProductSnapshot(offer,"fixture","Private product","","Private brand","offer-1","category",List.of(),List.of(),variants,null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(ozon),"sku","10001"));
        var conflict=new RegistrationDynamicPreflight.Plan(snapshot,plan.draft(),plan.schema(),null);var source=RegistrationFieldProvenance.capture(snapshot,snapshot,plan.draft(),plan.schema(),Map.of(),null,false);RegistrationFieldProvenance.save(ozon,List.of(source));
        var shown=RegistrationFieldProvenance.read(ozon,snapshot,plan.draft(),plan.schema());
        assertThrows(IllegalArgumentException.class,()->RegistrationSourceConsent.prepare(ozon,UUID.randomUUID(),List.of(conflict),Map.of(offer,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null)),Map.of(),Map.of(offer,shown),false));
    }
    @Test void photoOutsideTheObservedProductCannotBeApproved(){
        assertThrows(IllegalArgumentException.class,()->RegistrationSourceConsent.prepare(shop,UUID.randomUUID(),List.of(plan),Map.of(key,new RegistrationSourceConsent.Target(RegistrationSourceConsent.Action.CREATE,"",null)),Map.of(key,Set.of("https://example.invalid/unrelated.jpg")),Map.of(key,facts),false));
    }
}
