package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationDynamicPreflightTest {
    private static final String REGULATION="ТР ТС 017/2011 \"О безопасности продукции легкой промышленности\"";
    @TempDir Path directory;Shop shop;FixtureApi api;RegistrationDynamicPreflight service;
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.OZON);api=new FixtureApi();
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var auth=new ZnackAuthService(api,null){@Override public String trueApiToken(ZnackModels.Settings ignored){return "fixture";}};
        var catalog=new ZnackNationalCatalogService(api,auth,null,settings);
        service=new RegistrationDynamicPreflight(s->new ZnackCardRegistrationWorkflow.CatalogSession("fixture",catalog,ZnackRegistrationScope.fromSettings(settings)));
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    ProductSnapshot snapshot(String id){
        var key=new ProductKey(shop.getId(),Marketplace.OZON,id,"offer-"+id);
        var document=new ProductDocument("D","DECLARATION","SYNTHETIC-DOC",LocalDate.parse("2025-01-01"),LocalDate.now().plusYears(1),"Fixture","",null,Marketplace.OZON,DocumentState.VALID,"https://example.invalid",Instant.now());
        return new ProductSnapshot(key,"read","Fixture","","Brand","offer-"+id,"1",List.of(),List.of(new ProductAttribute("tnved","ТН ВЭД",List.of("6204"),"")),List.of(new ProductVariant(key.variantId(),key.variantId(),"Brown","52","",false,"52")),null,List.of(document),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop),"source.category.name","Fixture"));
    }
    @Test void allSelectedCardsExposeExactMissingAttributeAndReuseSchemaPaperAndTnvedReads()throws Exception{
        var snapshots=java.util.stream.IntStream.rangeClosed(1,25).mapToObj(n->snapshot(Integer.toString(n))).toList();
        var review=service.read(shop,snapshots,Map.of());
        assertEquals(25,review.plans().size());assertEquals(25,review.missing().size());
        for(var missing:review.missing()){assertEquals(999,missing.attribute().id());assertEquals("Catalog mandatory detail",missing.attribute().name());assertEquals(missing.key().variantId(),missing.variant());assertTrue(missing.suggestion().isBlank());}
        assertEquals(1,api.categories);assertEquals(1,api.attributes);assertEquals(1,api.permits);assertEquals(1,api.quota);
        assertThrows(RegistrationValidationException.class,()->RegistrationDynamicPreflight.approvals(review));assertEquals(0,api.writes);
    }
    @Test void draftCandidateCatalogIsLoadedOnceForTheEntireReviewBatch()throws Exception{
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var auth=new ZnackAuthService(api,null){@Override public String trueApiToken(ZnackModels.Settings ignored){return "fixture";}};
        var catalog=new ZnackNationalCatalogService(api,auth,null,settings);
        var checked=new RegistrationDynamicPreflight(s->new ZnackCardRegistrationWorkflow.CatalogSession("fixture",catalog,ZnackRegistrationScope.fromSettings(settings)),s->{reads.incrementAndGet();return new com.vncode.app.features.gtinsync.GtinSyncRepository().catalog(s.getId(),s.getMarketplace());});
        var selected=java.util.stream.IntStream.rangeClosed(1,25).mapToObj(n->snapshot(Integer.toString(n))).toList();
        assertEquals(25,checked.read(shop,selected,Map.of()).plans().size());assertEquals(1,reads.get());assertEquals(1,api.quota);assertEquals(0,api.writes);
    }
    @Test void onlyAScopedRecentUniquePhysicalDraftMayBypassExhaustedQuotaDuringReview()throws Exception{
        api.exhausted=true;var selected=snapshot("1");
        var details=new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("draft"),"@catalog_detailed_status",List.of("draft")),List.of(),List.of());
        new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(shop.getId(),shop.getMarketplace(),List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(GtinWriteTestFixture.GTIN,"offer-1","Brown","52",true,false,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(shop),Instant.now(),details)));
        assertEquals(1,service.read(shop,List.of(selected),Map.of()).plans().size());assertEquals(0,api.quota);assertEquals(0,api.writes);
        var wrong=snapshot("2");assertThrows(IllegalStateException.class,()->service.read(shop,List.of(wrong),Map.of()));
        assertEquals(1,api.quota);assertEquals(0,api.writes);
        new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(shop.getId(),shop.getMarketplace(),List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(GtinWriteTestFixture.GTIN,"offer-1","Brown","52",true,false,"National Catalog@foreign",Instant.now(),details)));
        assertThrows(IllegalStateException.class,()->service.read(shop,List.of(selected),Map.of()));assertEquals(2,api.quota);assertEquals(0,api.writes);
    }
    @Test void enteredExactTypedValuesCompleteEveryCardWithoutAllocatingOrSubmitting()throws Exception{
        var one=snapshot("1");var two=snapshot("2");var entered=Map.of(one.key(),Map.of(999L,new RegistrationReviewedValues.Value("A","")),two.key(),Map.of(999L,new RegistrationReviewedValues.Value("B","")));
        var review=service.read(shop,List.of(one,two),entered);assertTrue(review.missing().isEmpty());assertTrue(review.blocked().isEmpty());
        var approvals=RegistrationDynamicPreflight.approvals(review);assertEquals(2,approvals.size());assertEquals("A",approvals.getFirst().values().get(999L).text());assertEquals(0,api.writes);
        try(var c=Database.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT COUNT(*) FROM ozon_card_registrations")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
    }
    @Test void verifiedPermitRegulationFillsEverySelectedCardWithoutAChoiceOrExtraReads()throws Exception{
        api.requiredRegulation=true;
        var selected=java.util.stream.IntStream.rangeClosed(1,25).mapToObj(n->snapshot(Integer.toString(n))).toList();
        var entered=new LinkedHashMap<ProductKey,Map<Long,RegistrationReviewedValues.Value>>();
        for(var item:selected)entered.put(item.key(),Map.of(999L,new RegistrationReviewedValues.Value("A","")));
        var review=service.read(shop,selected,entered);
        assertEquals(25,review.plans().size());assertTrue(review.missing().isEmpty());assertTrue(review.blocked().isEmpty());assertTrue(review.documents().isEmpty());
        for(var plan:review.plans())assertEquals(REGULATION,plan.draft().attributes().get(13836L));
        assertEquals(25,RegistrationDynamicPreflight.approvals(review).size());assertEquals(1,api.permits);assertEquals(1,api.attributes);assertEquals(0,api.writes);
    }
    @Test void invalidExplicitRegulationStillNeedsReviewInsteadOfBeingReplacedByPermitDefault()throws Exception{
        api.requiredRegulation=true;var original=snapshot("1");var attributes=new ArrayList<>(original.attributes());
        attributes.add(new ProductAttribute("source-regulation","Технический регламент",List.of("Unknown regulation"),""));
        var source=new ProductSnapshot(original.key(),original.revision(),original.title(),original.description(),original.brand(),original.vendorArticle(),original.categoryId(),original.media(),attributes,original.variants(),original.dimensions(),original.documents(),original.checkedAt(),original.metadata());
        var review=service.read(shop,List.of(source),Map.of(source.key(),Map.of(999L,new RegistrationReviewedValues.Value("A",""))));
        assertEquals(1,review.missing().size());assertEquals(13836L,review.missing().getFirst().attribute().id());assertEquals(0,api.writes);
    }
    @Test void announcedProductionCountryDefaultsToRussiaWithoutAnyRegulationProofOrSellerChoice()throws Exception{
        api.announcedCountry=true;var one=snapshot("1");var two=snapshot("2");
        var entered=Map.of(one.key(),Map.of(999L,new RegistrationReviewedValues.Value("A","")),two.key(),Map.of(999L,new RegistrationReviewedValues.Value("B","")));
        var review=service.read(shop,List.of(one,two),entered);
        assertTrue(review.missing().isEmpty());assertEquals(2,review.plans().size());for(var plan:review.plans()){assertEquals("RU",plan.draft().attributes().get(2630L));assertFalse(plan.draft().attributes().containsKey(13836L));}
        assertEquals(1,api.countries);assertEquals(1,api.fullModels);assertEquals(1,api.permits);assertEquals(0,api.writes);
    }
    @Test void actualChineseSourceCountryIsKeptAsItsOfficialIsoInsteadOfUsingRussiaDefault()throws Exception{
        api.announcedCountry=true;var original=snapshot("1");var attributes=new ArrayList<>(original.attributes());attributes.add(new ProductAttribute("country","Страна производства",List.of("Китай"),""));
        var source=new ProductSnapshot(original.key(),original.revision(),original.title(),original.description(),original.brand(),original.vendorArticle(),original.categoryId(),original.media(),attributes,original.variants(),original.dimensions(),original.documents(),original.checkedAt(),original.metadata());
        var review=service.read(shop,List.of(source),Map.of(source.key(),Map.of(999L,new RegistrationReviewedValues.Value("A",""))));
        assertTrue(review.missing().isEmpty());assertEquals("CN",review.plans().getFirst().draft().attributes().get(2630L));assertEquals(0,api.writes);
    }
    @Test void missingOrAmbiguousCategoriesAreReportedForEverySelectedCard()throws Exception{
        api.ambiguous=true;var review=service.read(shop,List.of(snapshot("1"),snapshot("2")),Map.of());
        assertEquals(2,review.blocked().size());assertTrue(review.plans().isEmpty());assertTrue(review.blocked().stream().allMatch(b->b.field().equals("catalog_category")&&b.categories().size()==2));assertEquals(0,api.writes);
        var corrected=service.read(shop,List.of(snapshot("1"),snapshot("2")),Map.of(),Map.of(),Map.of(snapshot("1").key(),2L,snapshot("2").key(),1L));
        assertTrue(corrected.blocked().isEmpty());assertEquals(List.of(2L,1L),corrected.plans().stream().map(p->p.draft().categoryId()).toList());
    }
    @Test void registryScopeAdvisoryRemainsVisibleAndOnlyExactPaperConsentSuppressesIt()throws Exception{
        api.scope="6101,6201";var one=snapshot("1");var first=service.read(shop,List.of(one),Map.of());
        assertEquals(1,first.documents().size());assertTrue(first.documents().getFirst().detail().contains("ZNACK_DOCUMENT_TNVED_MISMATCH"));
        assertThrows(RegistrationValidationException.class,()->RegistrationDynamicPreflight.approvals(first));
        var wrong=service.read(shop,List.of(one),Map.of(),Map.of(one.key(),"different paper"));assertEquals(1,wrong.documents().size());
        var consent=RegistrationReviewedValues.permitRevision(first.plans().getFirst().documents());var checked=service.read(shop,List.of(one),Map.of(),Map.of(one.key(),consent));assertTrue(checked.documents().isEmpty());assertEquals(0,api.writes);
    }
    @Test void inactivePaperProducesOneActionableDocumentRowPerCardRatherThanDuplicateWarningAndBlock()throws Exception{
        api.permitStatus=2;
        var review=service.read(shop,List.of(snapshot("1"),snapshot("2")),Map.of());
        assertEquals(2,review.blocked().size());assertTrue(review.blocked().stream().allMatch(b->b.field().equals("documents")));
        assertTrue(review.documents().isEmpty());assertTrue(review.plans().isEmpty());assertEquals(0,api.writes);
    }
    @Test void conditionalFieldsAndAmbiguousTypeAreVisibleRatherThanGuessed(){
        var child=new Attribute(1000,"Conditional numeric value","number",false,false,true,false,List.of());
        var parent=new Attribute(999,"Choice","text",true,false,true,false,List.of("A","B"),List.of(),Map.of("A",List.of(child)));
        var size=new Attribute(35,"Size","text",false,false,true,false,List.of(),List.of("Россия","Европа"));
        var first=RegistrationDynamicPreflight.inspect(new RegistrationDynamicPreflight.Plan(snapshot("1"),new Draft("6204",1,"Name","Brand",Map.of()),List.of(parent,size)));
        assertEquals(Set.of(999L,35L),first.stream().map(m->m.attribute().id()).collect(java.util.stream.Collectors.toSet()));assertTrue(first.stream().filter(m->m.attribute().id()==999).findFirst().orElseThrow().suggestion().isBlank());
        var changed=RegistrationDynamicPreflight.inspect(new RegistrationDynamicPreflight.Plan(snapshot("1"),new Draft("6204",1,"Name","Brand",Map.of(999L,"A",35L,"52")),List.of(parent,size)));
        assertTrue(changed.stream().anyMatch(m->m.attribute().id()==1000));assertTrue(changed.stream().anyMatch(m->m.attribute().id()==35&&m.reason().equals("type")));
    }
    final class FixtureApi extends ZnackApiClient{
        int categories,attributes,permits,quota,writes,countries,fullModels;int permitStatus=1;boolean ambiguous,requiredRegulation,announcedCountry;String scope="6204";
        boolean exhausted;
        public JsonElement generatedGtins(String base,String token){quota++;return json(exhausted?"{\"result\":{\"drafts\":[],\"monthly-limit\":{\"limit\":0,\"usage\":0}}}":"{\"result\":{\"drafts\":[],\"monthly-limit\":{\"limit\":10000,\"usage\":0}}}");}
        public JsonElement nationalCatalogCategories(String base,String token,String tnved){categories++;return json(ambiguous?"{\"result\":[{\"cat_id\":1,\"cat_name\":\"Other A\"},{\"cat_id\":2,\"cat_name\":\"Other B\"}]}":"{\"result\":[{\"cat_id\":1,\"cat_name\":\"Fixture\"}]}");}
        public JsonElement nationalCatalogAttributes(String base,String token,long category){attributes++;return attributeResponse();}
        public JsonElement nationalCatalogAllAttributes(String base,String token,long category){fullModels++;var response=attributeResponse();if(announcedCountry){var field=new JsonObject();field.addProperty("attr_id",2630);field.addProperty("attr_name","Страна производства");field.addProperty("attr_field_type","text");field.addProperty("attr_preset_only",true);response.getAsJsonArray("result").add(field);}return response;}
        public JsonElement nationalCatalogCountries(String base,String token){countries++;return json("{\"apiversion\":3,\"result\":{\"_etag\":\"countries-fixture\",\"_list\":[{\"country_iso\":\"RU\",\"country_name\":\"Россия\"},{\"country_iso\":\"CN\",\"country_name\":\"Китай\"}]}}");}
        private JsonObject attributeResponse(){var response=json("{\"result\":[{\"attr_id\":999,\"attr_name\":\"Catalog mandatory detail\",\"attr_field_type\":\"text\",\"attr_preset_only\":true,\"attr_preset\":[\"A\",\"B\"]}]}").getAsJsonObject();if(requiredRegulation){var field=new JsonObject();field.addProperty("attr_id",13836);field.addProperty("attr_name","Технический регламент");field.addProperty("attr_field_type","text");field.addProperty("attr_preset_only",true);var presets=new JsonArray();presets.add(REGULATION);field.add("attr_preset",presets);response.getAsJsonArray("result").add(field);}return response;}
        public JsonElement registrationPermitInfo(String base,String token,JsonArray requested){permits++;var docs=requested.deepCopy();for(var value:docs){value.getAsJsonObject().addProperty("status_group",permitStatus);value.getAsJsonObject().addProperty("product_tnved",scope);if(requiredRegulation)value.getAsJsonObject().addProperty("product_tech_regulations","ТР ТС 017/2011 О безопасности продукции легкой промышленности");}var result=new JsonObject();result.add("documents",docs);var response=new JsonObject();response.add("result",result);return response;}
        public JsonElement generateGtins(String base,String token,int quantity){writes++;throw new AssertionError("Preflight allocated a GTIN");}
        public JsonElement submitNationalCatalogFeed(String base,String token,JsonElement body){writes++;throw new AssertionError("Preflight sent a card");}
        JsonElement json(String value){return JsonParser.parseString(value);}
    }
}
