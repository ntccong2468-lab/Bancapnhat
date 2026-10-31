package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.integration.znack.registration.RegistrationFieldProvenance.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationFieldProvenanceTest {
    @TempDir Path directory;
    Shop shop;
    @BeforeEach void setUp()throws Exception {System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.OZON);}
    @AfterEach void tearDown(){System.clearProperty("vncode.appdata.dir");}
    private ProductSnapshot snapshot(String brand,List<ProductAttribute> attributes,String variant){
        var key=new ProductKey(shop.getId(),Marketplace.OZON,"100",variant);
        return new ProductSnapshot(key,"fixture-revision","Shirt","",brand,"Seller article","5",List.of(),attributes,
                List.of(new ProductVariant(variant,"Seller article","Blue","L","",false,"48")),null,List.of(),Instant.now(),
                Map.of("owner",ListingIdentity.fingerprint(shop),"token","fixture-token-must-not-be-persisted"));
    }
    private Draft draft(String brand,String origin){return new Draft("6103109000","6103",10,"Shirt",brand,
            Map.of(2483L,"cotton",2630L,origin,35L,"L",36L,"Blue"),Map.of(),false,false);}
    private List<Attribute> schema(){return List.of(new Attribute(35,"Размер","string",false,false,true,false,List.of(),List.of()),new Attribute(36,"Цвет","string",false,false,true,false,List.of(),List.of()),new Attribute(2483,"Состав","string",false,false,true,false,List.of(),List.of()),new Attribute(2630,"Страна производства","string",false,false,true,false,List.of(),List.of()));}
    private Map<String,Evidence> byField(Prepared prepared){var map=new HashMap<String,Evidence>();prepared.fields().forEach(e->map.put(e.field(),e));return map;}

    @Test void recordsActualSourceWithoutTreatingSellerValuesAsVerified(){
        var source=snapshot("",List.of(new ProductAttribute("2","ТН ВЭД",List.of("6103109000"),""),new ProductAttribute("3","Состав",List.of("cotton"),"")),"200");
        var configured=new ProductSnapshot(source.key(),source.revision(),source.title(),source.description(),"Configured brand",source.vendorArticle(),source.categoryId(),source.media(),source.attributes(),source.variants(),null,List.of(),source.checkedAt(),source.metadata());
        var prepared=capture(source,configured,draft("Configured brand","RU"),schema(),Map.of(36L,new RegistrationReviewedValues.Value("Blue","TEXT")),null,false);var fields=byField(prepared);
        assertEquals(Source.SHOP_CONFIGURATION,fields.get("product.brand").source());
        assertEquals(Source.MARKETPLACE_UNVERIFIED,fields.get("product.tnved").source());
        assertEquals(Source.MARKETPLACE_UNVERIFIED,fields.get("product.material").source());
        assertEquals(Source.SELLER_INPUT_UNCONFIRMED,fields.get("catalog.attribute.36").source());
        assertEquals(Source.UNKNOWN,fields.get("product.origin").source());
        assertEquals(Source.UNKNOWN,fields.get("product.document").source());
        assertEquals(ListingIdentity.hash("RU"),fields.get("product.origin").valueHash());
    }
    @Test void persistenceIsScopedToVariantOwnerCertificateAndExactDraftWithoutRawValues()throws Exception{
        var source=snapshot("Real brand",List.of(new ProductAttribute("2","ТН ВЭД",List.of("6103109000"),"")),"200");var current=draft("Real brand","RU");
        var prepared=capture(source,source,current,schema(),Map.of(),null,false);save(shop,List.of(prepared));
        assertEquals(prepared.fields().size(),read(shop,source,current,schema()).size());
        assertTrue(read(shop,source,draft("Changed brand","RU"),schema()).isEmpty());
        var sibling=snapshot("Real brand",source.attributes(),"201");assertTrue(read(shop,sibling,current,schema()).isEmpty());
        assertTrue(read(shop,source,current,List.of()).isEmpty());
        try(var c=Database.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT * FROM znack_registration_field_provenance")){
            while(rows.next())for(int i=1;i<=rows.getMetaData().getColumnCount();i++){String text=Objects.toString(rows.getString(i),"");assertFalse(text.contains("fixture-token"));assertFalse(text.contains("Real brand"));assertFalse(text.contains("Seller article"));}
        }
        GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);assertTrue(read(shop,source,current,schema()).isEmpty());
        GtinWriteTestFixture.saveSettings(shop,"A".repeat(40),ZnackModels.PRODUCTION_TRUE_API);assertEquals(prepared.fields().size(),read(shop,source,current,schema()).size());
    }
    @Test void conflictingSourcesAndTransformedCountryStayUnknown(){
        var source=snapshot("Brand",List.of(new ProductAttribute("1","ТН ВЭД",List.of("6103109000","6201400000"),""),new ProductAttribute("2","Страна производства",List.of("Россия","Китай"),"")),"200");
        var values=byField(capture(source,source,draft("Brand","Россия"),schema(),Map.of(),null,false));
        assertEquals(Source.UNKNOWN,values.get("product.tnved").source());assertEquals(Source.UNKNOWN,values.get("product.origin").source());
    }
    @Test void preflightNeverPicksTheFirstOfConflictingOrInvalidMarketplaceTariffs(){
        var missing=snapshot("Brand",List.of(),"200");
        assertEquals("ZNACK_MISSING_TNVED",assertThrows(RegistrationValidationException.class,()->RegistrationDynamicPreflight.sourceTnved(missing)).getMessage());
        var conflicting=snapshot("Brand",List.of(new ProductAttribute("1","ТН ВЭД",List.of("6103109000","6201400000"),"")),"200");
        assertEquals("ZNACK_TNVED_CONFLICT",assertThrows(RegistrationValidationException.class,()->RegistrationDynamicPreflight.sourceTnved(conflicting)).getMessage());
        var malformed=snapshot("Brand",List.of(new ProductAttribute("1","ТН ВЭД",List.of("6103109000","not-a-code"),"")),"200");
        assertEquals("ZNACK_TNVED_CONFLICT",assertThrows(RegistrationValidationException.class,()->RegistrationDynamicPreflight.sourceTnved(malformed)).getMessage());
        var repeated=snapshot("Brand",List.of(new ProductAttribute("1","ТН ВЭД",List.of("6103109000","6103109000"),"")),"200");
        assertEquals("6103109000",RegistrationDynamicPreflight.sourceTnved(repeated));
    }
    @Test void batchedReadKeepsEveryVariantAndRejectsChangedDraftOrForeignIdentity(){
        var plans=new ArrayList<RegistrationDynamicPreflight.Plan>();var captured=new ArrayList<Prepared>();
        for(int n=0;n<1000;n++){
            var source=snapshot("Brand",List.of(new ProductAttribute("2","ТН ВЭД",List.of("6103109000"),"")),Integer.toString(1000+n));
            var draft=draft("Brand","");plans.add(new RegistrationDynamicPreflight.Plan(source,draft,schema()));captured.add(capture(source,source,draft,schema(),Map.of(),null,false));
        }
        save(shop,captured);long start=System.nanoTime();var read=readBatch(shop,plans);
        long elapsed=(System.nanoTime()-start)/1_000_000;assertEquals(1000,read.size());
        for(int i:new int[]{0,1,499,999}){var p=plans.get(i);assertEquals(read(shop,p.snapshot(),p.draft(),p.schema()),read.get(p.snapshot().key()));}
        assertTrue(read.values().stream().allMatch(fields->fields.size()==captured.getFirst().fields().size()));
        var changed=new RegistrationDynamicPreflight.Plan(plans.getFirst().snapshot(),draft("Different",""),schema());
        assertTrue(readBatch(shop,List.of(changed)).get(changed.snapshot().key()).isEmpty());
        assertThrows(IllegalArgumentException.class,()->readBatch(shop,List.of(plans.getFirst(),plans.getFirst())));
        var foreign=snapshot("Brand",List.of(),"3000");var foreignKey=new ProductKey(123,Marketplace.OZON,foreign.key().cardId(),foreign.key().variantId());
        var wrong=new ProductSnapshot(foreignKey,foreign.revision(),foreign.title(),foreign.description(),foreign.brand(),foreign.vendorArticle(),foreign.categoryId(),foreign.media(),foreign.attributes(),foreign.variants(),foreign.dimensions(),foreign.documents(),foreign.checkedAt(),foreign.metadata());
        assertThrows(SecurityException.class,()->readBatch(shop,List.of(new RegistrationDynamicPreflight.Plan(wrong,draft("Brand",""),schema()))));
        System.out.println("Registration provenance: 1000 synthetic variants batched read in "+elapsed+" ms");
    }
    @Test void inferredGenderIsNotReportedAsAMarketplaceFact(){
        var source=snapshot("Brand",List.of(new ProductAttribute("1","ТН ВЭД",List.of("6103109000"),"")),"200");
        var draft=new Draft("6103109000","6103",10,"Shirt","Brand",Map.of(14013L,"Мужской"),Map.of(),false,false);
        var gender=new Attribute(14013,"Пол","string",false,false,true,false,List.of(),List.of());
        var inferred=byField(capture(source,source,draft,List.of(gender),Map.of(),null,false,Map.of(14013L,"Мужской")));
        assertEquals(Source.INFERRED_UNVERIFIED,inferred.get("product.gender").source());assertEquals(Source.INFERRED_UNVERIFIED,inferred.get("catalog.attribute.14013").source());
        var entered=byField(capture(source,source,draft,List.of(gender),Map.of(14013L,new RegistrationReviewedValues.Value("Мужской","TEXT")),null,false,Map.of(14013L,"Мужской")));
        assertEquals(Source.SELLER_INPUT_UNCONFIRMED,entered.get("product.gender").source());
    }
    @Test void arbitraryRawValuesOrSecretsCannotBeInsertedAsEvidence(){
        assertThrows(IllegalArgumentException.class,()->new Evidence("token",ListingIdentity.hash("safe"),Source.UNKNOWN,Instant.now()));
        assertThrows(IllegalArgumentException.class,()->new Evidence("product.brand","fixture-secret",Source.UNKNOWN,Instant.now()));
        assertThrows(IllegalArgumentException.class,()->new Evidence("product.origin",ListingIdentity.hash("CN"),Source.MARKETPLACE_MAPPED_UNVERIFIED,Instant.now()));
    }
    @Test void thousandVariantsArePersistedTogetherWithoutLosingOrMixingEvidence()throws Exception{
        var rows=new ArrayList<Prepared>();
        for(int n=0;n<1000;n++){
            var source=snapshot("Brand",List.of(new ProductAttribute("2","ТН ВЭД",List.of("6103109000"),"")),Integer.toString(1000+n));
            rows.add(capture(source,source,draft("Brand",""),schema(),Map.of(),null,false));
        }
        long start=System.nanoTime();save(shop,rows);long elapsed=(System.nanoTime()-start)/1_000_000;
        try(var c=Database.getConnection();var s=c.createStatement();var data=s.executeQuery("SELECT COUNT(*), COUNT(DISTINCT variant_id) FROM znack_registration_field_provenance")){
            assertTrue(data.next());assertEquals(rows.getFirst().fields().size()*1000,data.getInt(1));assertEquals(1000,data.getInt(2));
        }
        System.out.println("Registration field provenance: 1000 synthetic variants saved in "+elapsed+" ms");
    }
}
