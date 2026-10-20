package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.integration.znack.registration.RegistrationReviewedValues.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationReviewedValuesTest {
    @TempDir Path directory;
    Shop shop;ProductSnapshot snapshot;
    final Attribute weight=new Attribute(900,"Weight","number",false,false,true,false,List.of(),List.of("GRAMS"));
    final Attribute name=new Attribute(2478,"Name","string",false,false,true,false,List.of(),List.of("TEXT"));
    final Attribute brand=new Attribute(2504,"Brand","string",false,false,true,false,List.of(),List.of("TEXT"));
    List<Attribute> schema(){return List.of(weight,name,brand);}
    Map<Long,Value> values(){return Map.of(900L,new Value(" 100.5 ","GRAMS"),2478L,new Value(" New name ","TEXT"),2504L,new Value(" New brand ","TEXT"));}
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        var key=new ProductKey(shop.getId(),Marketplace.WILDBERRIES,"101","201");
        snapshot=new ProductSnapshot(key,"live:fixture","Fixture title","Fixture description","Fixture brand","Fixture article","1",List.of(),List.of(new ProductAttribute("1","ТН ВЭД",List.of("6103109000"),"")),List.of(new ProductVariant("201","Fixture article","Brown","52","",false,"52")),null,List.of(document("fixture",LocalDate.of(2030,1,1),"https://example.invalid/",Instant.now(),Map.of())),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop),"source.category.name","Костюмы спортивные"));
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    void save(){RegistrationReviewedValues.save(shop,List.of(new Approval(snapshot,schema(),values())));}

    @Test void approvedTypedValuesSurviveAStorageRoundTripAndApplyWithoutChangingIdentity(){
        save();var read=RegistrationReviewedValues.load(shop,snapshot,schema());assertEquals(values(),read);assertEquals("100.5",read.get(900L).text());
        var draft=new Draft("6103109000","6103",44,"Before name","Before brand",Map.of(900L,"5",12L,"Existing"),Map.of(900L,"GRAMS"),true,true);
        var result=RegistrationReviewedValues.apply(shop,snapshot,draft,schema());
        assertEquals("100.5",result.attributes().get(900L));assertEquals("GRAMS",result.attributeTypes().get(900L));assertEquals("Existing",result.attributes().get(12L));
        assertEquals("New name",result.goodName());assertEquals("New brand",result.brand());assertEquals(draft.tnved(),result.tnved());assertEquals(draft.feedTnved(),result.feedTnved());assertEquals(draft.categoryId(),result.categoryId());assertTrue(result.autoSign());assertTrue(result.isKit());
    }
    @Test void approvalIsBoundToTheShopAndProductVariant(){
        save();var sibling=copy(snapshot,new ProductKey(shop.getId(),shop.getMarketplace(),"101","202"),snapshot.title(),snapshot.attributes(),snapshot.variants(),snapshot.documents(),snapshot.metadata(),Instant.now());
        assertTrue(RegistrationReviewedValues.load(shop,sibling,schema()).isEmpty());
        var foreign=copy(snapshot,new ProductKey(2,Marketplace.OZON,"101","201"),snapshot.title(),snapshot.attributes(),snapshot.variants(),List.of(),Map.of(),Instant.now());
        assertThrows(SecurityException.class,()->RegistrationReviewedValues.save(shop,List.of(new Approval(foreign,schema(),values()))));
    }
    @Test void rotatingMarketplaceCredentialsDoesNotReuseThePreviousOwnersApproval()throws Exception{
        save();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated-fixture' WHERE id=1");}
        assertThrows(SecurityException.class,()->RegistrationReviewedValues.load(shop,snapshot,schema()));
        var replacement=new Shop(1,shop.getName(),"rotated-fixture");assertTrue(RegistrationReviewedValues.load(replacement,snapshot,schema()).isEmpty());
    }
    @Test void rotatingTheSignerScopeRequiresANewApproval(){
        save();GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        assertTrue(RegistrationReviewedValues.load(shop,snapshot,schema()).isEmpty());
    }
    @Test void approvalsNeverLeakIntoAnotherLocalProfileWithTheSameShopIdentity()throws Exception{
        save();var second=directory.resolve("other-profile");System.setProperty("vncode.appdata.dir",second.toString());Database.initDatabase();var other=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        assertEquals(ListingIdentity.fingerprint(shop),ListingIdentity.fingerprint(other));assertTrue(RegistrationReviewedValues.load(other,snapshot,schema()).isEmpty());
        System.setProperty("vncode.appdata.dir",directory.toString());assertEquals(values(),RegistrationReviewedValues.load(shop,snapshot,schema()));
    }
    @Test void refreshedTimestampsTransportRevisionAndDiagnosticMetadataDoNotInvalidateIdenticalFacts(){
        save();var refreshedDoc=document("fixture",LocalDate.of(2030,1,1),"https://another.example.invalid/",Instant.now().plusSeconds(10),Map.of("diagnostic","refreshed"));
        var refreshed=copy(snapshot,snapshot.key(),snapshot.title(),snapshot.attributes(),snapshot.variants(),List.of(refreshedDoc),Map.of("owner",ListingIdentity.fingerprint(shop),"source.category.name","Костюмы спортивные","diagnostic","new"),Instant.now().plusSeconds(10));
        assertEquals(RegistrationReviewedValues.productRevision(snapshot),RegistrationReviewedValues.productRevision(refreshed));assertEquals(values(),RegistrationReviewedValues.load(shop,refreshed,schema()));
    }
    @Test void changedTitleAttributesVariantDocumentsAndSourceCategoryRequireReviewAgain(){
        save();var changes=List.of(
                copy(snapshot,snapshot.key(),"Changed title",snapshot.attributes(),snapshot.variants(),snapshot.documents(),snapshot.metadata(),Instant.now()),
                copy(snapshot,snapshot.key(),snapshot.title(),List.of(new ProductAttribute("1","ТН ВЭД",List.of("6201400000"),"")),snapshot.variants(),snapshot.documents(),snapshot.metadata(),Instant.now()),
                copy(snapshot,snapshot.key(),snapshot.title(),snapshot.attributes(),List.of(new ProductVariant("201","Fixture article","Brown","54","",false,"54")),snapshot.documents(),snapshot.metadata(),Instant.now()),
                copy(snapshot,snapshot.key(),snapshot.title(),snapshot.attributes(),snapshot.variants(),List.of(document("different-paper",LocalDate.of(2030,1,1),"https://example.invalid/",Instant.now(),Map.of())),snapshot.metadata(),Instant.now()),
                copy(snapshot,snapshot.key(),snapshot.title(),snapshot.attributes(),snapshot.variants(),List.of(document("fixture",LocalDate.of(2031,1,1),"https://example.invalid/",Instant.now(),Map.of())),snapshot.metadata(),Instant.now()),
                copy(snapshot,snapshot.key(),snapshot.title(),snapshot.attributes(),snapshot.variants(),snapshot.documents(),Map.of("source.category.name","Брюки"),Instant.now()));
        for(var changed:changes){var error=assertThrows(RegistrationValidationException.class,()->RegistrationReviewedValues.load(shop,changed,schema()));assertEquals("ZNACK_REVIEWED_VALUES_CHANGED",error.getMessage());}
    }
    @Test void currentSchemaChangesCannotReuseOldValues(){
        save();var changed=new Attribute(weight.id(),weight.name(),weight.fieldType(),weight.presetOnly(),weight.multiple(),weight.firstLayer(),weight.secondLayer(),weight.presets(),List.of("KILOGRAMS"));
        assertEquals("ZNACK_REVIEWED_VALUES_CHANGED",assertThrows(RegistrationValidationException.class,()->RegistrationReviewedValues.load(shop,snapshot,List.of(changed,name,brand))).getMessage());
        var renamed=new Attribute(weight.id(),"Updated required field",weight.fieldType(),weight.presetOnly(),weight.multiple(),weight.firstLayer(),weight.secondLayer(),weight.presets(),weight.valueTypes());
        assertThrows(RegistrationValidationException.class,()->RegistrationReviewedValues.load(shop,snapshot,List.of(renamed,name,brand)));
    }
    @Test void protectedIdentityAndPermitFieldsCannotBeOverriddenEvenWhenTheyAppearInTheSchema(){
        for(long id:List.of(3959L,13933L,23557L,23561L)){
            var field=new Attribute(id,"Protected","string",false,false,true,false,List.of(),List.of("TEXT"));
            assertThrows(RegistrationValidationException.class,()->RegistrationReviewedValues.save(shop,List.of(new Approval(snapshot,List.of(field),Map.of(id,new Value("not-authorized","TEXT"))))));
        }
        assertTrue(RegistrationReviewedValues.load(shop,snapshot,schema()).isEmpty());
    }
    @Test void invalidTypesValuesAndUnknownFieldsCannotBePersisted(){
        for(var invalid:List.of(Map.of(900L,new Value("not-a-number","GRAMS")),Map.of(900L,new Value("100","KILOGRAMS")),Map.of(900L,new Value("100",null)),Map.of(900L,new Value(" ","GRAMS")),Map.of(999L,new Value("unknown","TEXT")))){
            assertThrows(RegistrationValidationException.class,()->RegistrationReviewedValues.save(shop,List.of(new Approval(snapshot,schema(),invalid))));
        }
        var preset=new Attribute(901,"Kind","string",true,false,true,false,List.of("Clothing"),List.of("TEXT"));
        assertThrows(RegistrationValidationException.class,()->RegistrationReviewedValues.validate(List.of(preset),Map.of(901L,new Value("Other","TEXT"))));
        var date=new Attribute(902,"Date","date",false,false,true,false,List.of(),List.of("DATE"));
        assertThrows(RegistrationValidationException.class,()->RegistrationReviewedValues.validate(List.of(date),Map.of(902L,new Value("not-a-date","DATE"))));
    }
    @Test void conditionalFieldsUseTheCurrentNestedSchemaWhenSavedAndLoaded(){
        var parent=new Attribute(901,"Kind","string",true,false,true,false,List.of("Clothing"),List.of("TEXT"),Map.of("Clothing",List.of(weight)));
        var nested=List.of(parent);var approved=Map.of(901L,new Value("Clothing","TEXT"),900L,new Value("100.5","GRAMS"));
        RegistrationReviewedValues.save(shop,List.of(new Approval(snapshot,nested,approved)));assertEquals(approved,RegistrationReviewedValues.load(shop,snapshot,nested));
        var altered=new Attribute(parent.id(),parent.name(),parent.fieldType(),parent.presetOnly(),parent.multiple(),parent.firstLayer(),parent.secondLayer(),parent.presets(),parent.valueTypes(),Map.of());
        assertThrows(RegistrationValidationException.class,()->RegistrationReviewedValues.load(shop,snapshot,List.of(altered)));
    }
    @Test void anInvalidMemberCannotPartiallyPersistTheRestOfAnApprovalBatch(){
        var invalid=new Approval(snapshot,schema(),Map.of(900L,new Value("invalid","GRAMS")));
        assertThrows(RegistrationValidationException.class,()->RegistrationReviewedValues.save(shop,List.of(new Approval(snapshot,schema(),values()),invalid)));
        assertTrue(RegistrationReviewedValues.load(shop,snapshot,schema()).isEmpty());
    }
    @Test void advisoryApprovalIsBoundToTheExactPapersRegistryReasonAndProductFacts(){
        var selected=advisory("fixture-paper","ZNACK_DOCUMENT_TNVED_MISMATCH: 6103 / 6101");
        assertNull(RegistrationReviewedValues.reviewedDocuments(shop,snapshot,selected).automatic());
        RegistrationReviewedValues.save(shop,List.of(new Approval(snapshot,List.of(),Map.of(),RegistrationReviewedValues.permitRevision(selected))));
        var reviewed=RegistrationReviewedValues.reviewedDocuments(shop,snapshot,selected);assertEquals(selected.candidates().getFirst(),reviewed.automatic());assertFalse(reviewed.requiresReview());assertEquals(selected.issues(),reviewed.issues());
        assertNull(RegistrationReviewedValues.reviewedDocuments(shop,snapshot,advisory("other-paper",selected.issues().getFirst())).automatic());
        assertNull(RegistrationReviewedValues.reviewedDocuments(shop,snapshot,advisory("fixture-paper","ZNACK_DOCUMENT_TNVED_MISMATCH: 6103 / 6201")).automatic());
        var changed=copy(snapshot,snapshot.key(),"Changed title",snapshot.attributes(),snapshot.variants(),snapshot.documents(),snapshot.metadata(),Instant.now());assertNull(RegistrationReviewedValues.reviewedDocuments(shop,changed,selected).automatic());
        GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);assertNull(RegistrationReviewedValues.reviewedDocuments(shop,snapshot,selected).automatic());
    }
    @Test void explicitReviewCannotApproveInactiveMissingOrAmbiguousPapers(){
        for(String issue:List.of("ZNACK_DOCUMENT_INACTIVE","ZNACK_DOCUMENT_EXPIRED","ZNACK_DOCUMENT_REGISTRY_NOT_FOUND","ZNACK_DOCUMENT_REGISTRY_PENDING")){
            var selected=advisory("fixture-paper",issue);RegistrationReviewedValues.save(shop,List.of(new Approval(snapshot,List.of(),Map.of(),RegistrationReviewedValues.permitRevision(selected))));
            assertNull(RegistrationReviewedValues.reviewedDocuments(shop,snapshot,selected).automatic());assertTrue(RegistrationReviewedValues.reviewedDocuments(shop,snapshot,selected).requiresReview());
        }
        var one=advisory("fixture-paper","ZNACK_DOCUMENT_TNVED_SCOPE_UNKNOWN");var multiple=new ZnackRegistrationDocuments.Selection(List.of(one.candidates().getFirst(),new ZnackModels.GoodsDocument("CONFORMITY_DECLARATION","second-paper","2026-01-01")),null,ZnackRegistrationDocuments.Source.PRODUCT,true,one.issues());
        RegistrationReviewedValues.save(shop,List.of(new Approval(snapshot,List.of(),Map.of(),RegistrationReviewedValues.permitRevision(multiple))));assertNull(RegistrationReviewedValues.reviewedDocuments(shop,snapshot,multiple).automatic());
    }

    static ZnackRegistrationDocuments.Selection advisory(String number,String issue){return new ZnackRegistrationDocuments.Selection(List.of(new ZnackModels.GoodsDocument("CONFORMITY_DECLARATION",number,"2026-01-01")),null,ZnackRegistrationDocuments.Source.PRODUCT,true,List.of(issue));}
    ProductDocument document(String number,LocalDate until,String source,Instant checked,Map<String,String> metadata){return new ProductDocument("fixture","DECLARATION",number,LocalDate.of(2026,1,1),until,"Fixture","6103",null,Marketplace.WILDBERRIES,DocumentState.UNVERIFIED,source,checked,metadata);}
    static ProductSnapshot copy(ProductSnapshot base,ProductKey key,String title,List<ProductAttribute> attributes,List<ProductVariant> variants,List<ProductDocument> documents,Map<String,String> metadata,Instant checked){return new ProductSnapshot(key,"live:refreshed",title,base.description(),base.brand(),base.vendorArticle(),base.categoryId(),base.media(),attributes,variants,base.dimensions(),documents,checked,metadata);}
}
