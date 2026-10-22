package com.vncode.app.ui.znackregistration;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.listing.ListingIdentity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RegistrationBatchPreflightTest {
    @TempDir Path directory;
    @AfterEach void clean(){System.clearProperty("vncode.appdata.dir");}
    @Test void missingConfiguredPermitIsReportedForAllSixSelectedSizes()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        var keys=java.util.stream.IntStream.range(0,6).mapToObj(n->new ProductKey(shop.getId(),shop.getMarketplace(),"101",Integer.toString(201+n))).toList();
        var finding=new RegistrationBatchPreflight.Finding("documents",keys.getFirst(),"Article","Missing configured permit","","",null,null,"ZNACK_MISSING_PRODUCT_DOCUMENT",List.of());
        RegistrationPreflightReport.write(shop,keys,List.of(finding),null);
        var reports=new com.vncode.app.features.sync.SyncRunRepository();var run=reports.list(shop.getId(),0,10).stream().filter(r->r.kind().equals(RegistrationPreflightReport.KIND)).findFirst().orElseThrow();
        assertEquals(6,run.total());var rows=reports.items(run.id(),0,10);assertEquals(6,rows.size());
        assertTrue(rows.stream().allMatch(row->row.state().equals("NEEDS_ACTION")&&row.evidence().contains("ZNACK_MISSING_PRODUCT_DOCUMENT")&&row.evidence().contains("\"catalogSubmitted\":false")));
        assertEquals(6,rows.stream().map(row->row.variantId()).distinct().count());
    }
    @Test void validExistingSuitCodeIsNotReclassifiedMerelyFromTheSportsCategory()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);var key=new ProductKey(1,shop.getMarketplace(),"101","201");
        var attrs=List.of(new ProductAttribute("1","ТН ВЭД",List.of("6103109000"),""),new ProductAttribute("2","Пол",List.of("Мужской"),""),new ProductAttribute("3","Состав",List.of("хлопок 80%, полиэстер 20%"),""),new ProductAttribute("4","Тип ткани",List.of("Трикотаж"),""));
        var snapshot=new ProductSnapshot(key,"live:fixture","Костюм спортивный","","Fixture","SUIT","1",List.of(),attrs,List.of(new ProductVariant("201","SUIT","Brown","52","",false)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop),"source.category.name","Костюмы спортивные"));
        var repository=new ProductRepository();repository.saveVerifiedSnapshot(shop,snapshot);
        assertTrue(new com.vncode.app.features.tnved.ProductTnvedAdvisor().propose(snapshot).unique(),"An advisor may still offer a different optional code");
        assertTrue(RegistrationBatchPreflight.read(shop,List.of(key)).keys("tnved").isEmpty());assertEquals(snapshot,repository.localSnapshot(key).orElseThrow());
    }
    @Test void aReviewedUniqueTnvedSuggestionIsReadyToApplyInsteadOfKeepingTheWrongOldCode(){
        var key=new ProductKey(1,Marketplace.WILDBERRIES,"101","201");
        var changed=new RegistrationBatchPreflight.Finding("tnved",key,"Article","6103109000","6112110000");
        var ambiguous=new RegistrationBatchPreflight.Finding("material",key,"Article","","");
        var values=RegistrationBatchPreflight.initialValues(new RegistrationBatchPreflight.Review(List.of(changed,ambiguous)));
        assertEquals("6112110000",values.get(changed));assertEquals("",values.get(ambiguous));
    }
    @Test void proposalTablePrefillsUniqueCatalogChoicesAndPreservesSellerValues(){
        var key=new ProductKey(1,Marketplace.WILDBERRIES,"101","201");
        var attribute=new com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute(999,"Product type","text",true,false,true,false,List.of("Одежда"),List.of("---"));
        var field=new RegistrationBatchPreflight.Finding("catalog:999",key,"Article","","","",attribute,null,"missing",List.of());
        var category=new RegistrationBatchPreflight.Finding("catalog_category",key,"Article","","","",null,null,"missing",List.of("Костюмы спортивные"));
        var review=new RegistrationBatchPreflight.Review(List.of(field,category));
        var values=RegistrationBatchPreflight.initialValues(review);
        assertEquals("Одежда",values.get(field));assertEquals("Костюмы спортивные",values.get(category));
    }
    @Test void missingFactsAreGroupedBeforeAllocationAndTheReviewDoesNotChangeTheSource()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        var key=new ProductKey(1,Marketplace.WILDBERRIES,"101","201");
        var snapshot=new ProductSnapshot(key,"live:fixture","Костюм","","","Article","1",List.of(),List.of(),List.of(new ProductVariant("201","Article","Brown","L","",false)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));
        var repository=new ProductRepository();repository.saveVerifiedSnapshot(shop,snapshot);
        var review=RegistrationBatchPreflight.read(shop,List.of(key));assertEquals(Set.of("brand","material","gender","tnved","documents"),review.findings().stream().map(RegistrationBatchPreflight.Finding::field).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of(key),review.keys("brand"));assertEquals(snapshot,repository.localSnapshot(key).orElseThrow());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM znack_card_registrations")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }
    @Test void eligibleProductDocumentOrConfiguredShopDefaultDoesNotAskForAnotherDocument()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);var key=new ProductKey(1,shop.getMarketplace(),"101","201");
        var document=new ProductDocument("fixture","DECLARATION","SYNTHETIC-DOC",java.time.LocalDate.now().minusDays(1),java.time.LocalDate.now().plusYears(1),"Fixture","",null,shop.getMarketplace(),DocumentState.UNVERIFIED,"https://example.invalid",Instant.now());
        var snapshot=new ProductSnapshot(key,"live:fixture","Костюм","","Fixture","Article","1",List.of(),List.of(),List.of(new ProductVariant("201","Article","Brown","L","",false)),null,List.of(document),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));var repository=new ProductRepository();repository.saveVerifiedSnapshot(shop,snapshot);
        assertTrue(RegistrationBatchPreflight.read(shop,List.of(key)).keys("documents").isEmpty());
        var without=new ProductSnapshot(snapshot.key(),snapshot.revision(),snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),snapshot.attributes(),snapshot.variants(),snapshot.dimensions(),List.of(),snapshot.checkedAt(),snapshot.metadata());repository.saveVerifiedSnapshot(shop,without);
        assertEquals(List.of(key),RegistrationBatchPreflight.read(shop,List.of(key)).keys("documents"));
        var settings=new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(1,shop.getName()));settings.saveSettings(settings.getSettings().withDefaultGoodsDocument("CONFORMITY_DECLARATION","SYNTHETIC-DOC",java.time.LocalDate.now().minusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu"))));
        assertTrue(RegistrationBatchPreflight.read(shop,List.of(key)).keys("documents").isEmpty());
    }    @Test void staleStoredApprovalRequiresFreshExplicitReviewEvenWhenAutomaticValuesAreComplete()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);var key=new ProductKey(shop.getId(),shop.getMarketplace(),"101","201");
        var snapshot=new ProductSnapshot(key,"live:fixture","Name","","Brand","Article","1",List.of(),List.of(),List.of(new ProductVariant("201","Article","Brown","52","",false)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));
        var attribute=new com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute(999,"Catalog field","text",false,false,true,false,List.of());var schema=List.of(attribute);
        com.vncode.app.integration.znack.registration.RegistrationReviewedValues.save(shop,List.of(new com.vncode.app.integration.znack.registration.RegistrationReviewedValues.Approval(snapshot,schema,Map.of(999L,new com.vncode.app.integration.znack.registration.RegistrationReviewedValues.Value("Old approval","")))));
        var changed=new ProductSnapshot(key,"live:new","Name","","Brand","Changed article","1",snapshot.media(),snapshot.attributes(),snapshot.variants(),snapshot.dimensions(),snapshot.documents(),Instant.now(),snapshot.metadata());
        var plan=new com.vncode.app.integration.znack.registration.RegistrationDynamicPreflight.Plan(changed,new com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Draft("6204",1,"Name","Brand",Map.of(999L,"Automatic value")),schema);
        var current=new RegistrationBatchPreflight.Review(List.of(),List.of(key),new com.vncode.app.integration.znack.registration.RegistrationDynamicPreflight.Review(List.of(plan),List.of()));
        var review=RegistrationBatchPreflight.reviewStaleApprovals(shop,current);assertEquals(1,review.findings().size());assertEquals("Automatic value",review.findings().getFirst().suggestion());
        assertThrows(com.vncode.app.integration.znack.registration.RegistrationValidationException.class,()->com.vncode.app.integration.znack.registration.RegistrationReviewedValues.load(shop,changed,schema),"Merely reading a proposal must not change the old approval");
        com.vncode.app.integration.znack.registration.RegistrationReviewedValues.save(shop,com.vncode.app.integration.znack.registration.RegistrationDynamicPreflight.approvals(current.dynamic()));assertEquals("Automatic value",com.vncode.app.integration.znack.registration.RegistrationReviewedValues.load(shop,changed,schema).get(999L).text());assertTrue(RegistrationBatchPreflight.reviewStaleApprovals(shop,current).findings().isEmpty());
    }

}
