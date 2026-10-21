package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.sync.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import com.vncode.app.testsupport.RegistrationSourceFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogErrorRepairServiceTest {
    @TempDir Path root;Shop shop;ProductKey key;ZnackRegistrationScope scope;ProductSnapshot snapshot;
    String gtin=GtinWriteTestFixture.GTIN;JsonObject raw;FixtureApi api;ZnackCardRegistrationWorkflow.CatalogSession session;CatalogErrorRepairService service;
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);key=new ProductKey(1,Marketplace.WILDBERRIES,"101","201");
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,brand,subject_name,synced_at) VALUES(1,101,'SUIT','Костюм','Fixture','Костюмы спортивные','test')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,nm_id,chrt_id,tech_size,wb_size) VALUES(1,101,201,'52','52')");
            s.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,201,'00000000000017')");
        }
        RegistrationSourceFixture.ready(shop);scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture")).getSettings());
        snapshot=new ProductSnapshot(key,"live:fixture","Костюм размер 52","","Fixture","SUIT","1",List.of(),List.of(new ProductAttribute("1","ТН ВЭД",List.of("6112110000"),""),new ProductAttribute("2","Состав",List.of("80% хлопок, 20% полиэстер"),""),new ProductAttribute("3","Пол",List.of("Мужской"),"")),List.of(new ProductVariant("201","SUIT","ЧЕРНЫЙ","52","",false,"52")),null,List.of(new ProductDocument("doc","CONFORMITY_DECLARATION","FIXTURE-PERMIT",LocalDate.of(2026,1,1),LocalDate.of(2029,1,1),"","",null,Marketplace.WILDBERRIES,DocumentState.UNVERIFIED,"",null)),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop),"source.category.name","Костюмы спортивные"));
        raw=JsonParser.parseString("{\"gtin\":\"00000000000017\",\"good_id\":91,\"good_status\":\"draft\",\"good_detailed_status\":[\"errors\"],\"good_name\":\"Костюм размер 1\",\"good_attrs\":[{\"attr_id\":13933,\"attr_value\":\"6112110000\"},{\"attr_id\":35,\"attr_value\":\"1\",\"attr_value_type\":\"РОССИЯ\"},{\"attr_id\":23557,\"attr_value\":\"OLD:::2026-01-01\"}]}").getAsJsonObject();
        api=new FixtureApi();var catalog=new ZnackNationalCatalogService(api,new ZnackAuthService(api,null){public String trueApiToken(ZnackModels.Settings s){return "fixture";}},null,ZnackModels.Settings.empty());session=new ZnackCardRegistrationWorkflow.CatalogSession("fixture",catalog,scope);
        service=new CatalogErrorRepairService(s->session,(s,k)->snapshot,(s,k,cs,progress)->{
            var repo=new ZnackCardRegistrationRepository();var workflow=RegistrationWorkflowTestFixtures.workflow(repo,x->cs,Runnable::run,true,(a,b,c,d)->false);
            assertTrue(workflow.resume(s,repo.find(s.getId(),Long.parseLong(k.variantId())),false,(status,detail)->progress.accept(status)));
        });
    }
    @AfterEach void clean(){System.clearProperty("vncode.appdata.dir");}
    ProductRow row(String variant,String size){return new ProductRow(new ProductKey(1,Marketplace.WILDBERRIES,"101",variant),"Костюм","SUIT","101","Костюмы спортивные","Fixture","ЧЕРНЫЙ",size,List.of(gtin),"","","хлопок","6112110000",DocumentState.UNVERIFIED,"Мужской",true,size);}
    CatalogErrorRepairService.ErrorCard card(){return new CatalogErrorRepairService.ErrorCard(gtin,"SUIT","ЧЕРНЫЙ","1","CATALOG_CARD_ERRORS",List.of(row("201","52")));}
    @Test void preparesRealVariantAndScopedPermitWithoutGeneratingOrSubmitting()throws Exception{
        var proposal=service.prepare(shop,card(),key);assertEquals("52",proposal.draft().attributes().get(35L));assertEquals("РОССИЯ",proposal.draft().attributeTypes().get(35L));assertEquals("FIXTURE-PERMIT:::2026-01-01",proposal.draft().attributes().get(23557L));assertEquals("1",proposal.before().get(35L));assertEquals(0,api.writes.get());assertEquals(0,api.allocations.get());assertEquals(Status.NOT_CREATED,new ZnackCardRegistrationRepository().find(1,201).status());
    }
    @Test void applyUsesSameGtinAndCheckpointsActualFeedThenReportsModeration()throws Exception{
        var p=service.prepare(shop,card(),key);var progress=new ArrayList<CatalogErrorRepairService.Progress>();String run=service.apply(shop,List.of(p),List.of(CatalogErrorRepairService.validate(p,p.draft())),progress::add);
        assertEquals(1,api.writes.get());assertEquals(0,api.allocations.get());assertEquals(gtin,api.sent.get("gtin").getAsString());assertEquals(91L,api.sent.get("good_id").getAsLong());assertTrue(api.sent.toString().contains("FIXTURE-PERMIT"));assertFalse(api.sent.toString().contains("OLD:::"));
        var row=new ZnackCardRegistrationRepository().find(1,201);assertEquals(gtin,row.gtin());assertEquals("fixture-feed",row.feedId());assertEquals(Status.PROCESSING,row.status());assertEquals("WAITING_REMOTE",new SyncRunRepository().find(run).orElseThrow().state());assertEquals(1,progress.getLast().completed());
        assertThrows(IllegalStateException.class,()->session.catalog().rejectedCard("fixture",gtin,91L));
        var second=service.apply(shop,List.of(p),List.of(p.draft()),ignored->{});assertEquals(1,api.writes.get());assertEquals("PARTIAL",new SyncRunRepository().find(second).orElseThrow().state());
    }
    @Test void russianOnlySchemaUsesObservedRussianSizeInsteadOfLabellingInternationalSizeAsRussian()throws Exception{
        snapshot=new ProductSnapshot(key,snapshot.revision(),snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),snapshot.attributes(),List.of(new ProductVariant("201","SUIT","ЧЕРНЫЙ","2XL","",false,"52")),null,snapshot.documents(),Instant.now(),snapshot.metadata());
        var proposal=service.prepare(shop,card(),key);assertEquals("52",proposal.draft().attributes().get(35L));assertEquals("РОССИЯ",proposal.draft().attributeTypes().get(35L));assertNotNull(CatalogErrorRepairService.validate(proposal,proposal.draft()));assertEquals(0,api.writes.get());
    }
    @Test void rejectsPublishedRevisionAndWrongCardIdentity()throws Exception{raw.addProperty("good_status","published");assertThrows(IllegalStateException.class,()->service.prepare(shop,card(),key));raw.addProperty("good_status","draft");assertThrows(IllegalStateException.class,()->session.catalog().rejectedCard("fixture",gtin,92L));assertEquals(0,api.writes.get());}
    @Test void scopeMismatchIsVisibleForReviewWithoutPretendingItIsACatalogRejection()throws Exception{api.permitScope="6203";var proposal=service.prepare(shop,card(),key);assertEquals(1,proposal.documents().size());assertTrue(proposal.issues().stream().anyMatch(RegistrationPermitValidator::advisory));assertNotNull(CatalogErrorRepairService.validate(proposal,proposal.draft()));assertEquals(0,api.writes.get());assertEquals(Status.NOT_CREATED,new ZnackCardRegistrationRepository().find(1,201).status());var run=service.apply(shop,List.of(proposal),List.of(proposal.draft()),ignored->{});assertEquals(1,api.writes.get());assertEquals("WAITING_REMOTE",new SyncRunRepository().find(run).orElseThrow().state());}
    @Test void changedTariffGroupRequiresNewReviewedRegistration()throws Exception{raw.getAsJsonArray("good_attrs").get(0).getAsJsonObject().addProperty("attr_value","6203310000");assertTrue(assertThrows(IllegalArgumentException.class,()->service.prepare(shop,card(),key)).getMessage().contains("GROUP_CHANGED"));}
    @Test void staleMarketplaceAndRemoteReviewNeverWrite()throws Exception{
        var p=service.prepare(shop,card(),key);snapshot=new ProductSnapshot(key,"live:changed","Changed","","Fixture","SUIT","1",snapshot.media(),snapshot.attributes(),snapshot.variants(),null,snapshot.documents(),Instant.now(),snapshot.metadata());
        var run=service.apply(shop,List.of(p),List.of(p.draft()),ignored->{});assertEquals("PARTIAL",new SyncRunRepository().find(run).orElseThrow().state());assertEquals(0,api.writes.get());
    }
    @Test void pendingSubmissionAndUnknownSignatureCannotBeOverwritten()throws Exception{
        var p=service.prepare(shop,card(),key);assertTrue(CatalogRepairCheckpoint.prepare(shop,key,"",gtin,91,"{}",p.draft(),scope));
        assertFalse(CatalogRepairCheckpoint.prepare(shop,key,"",gtin,91,"{}",p.draft(),scope));assertEquals(Status.READY_TO_SUBMIT,new ZnackCardRegistrationRepository().find(1,201).status());
    }
    @Test void noSizeOneInferenceAndNoDuplicateVariantWithinABatch()throws Exception{
        var data=CatalogProductDataParser.parse(raw);var card=new GtinSyncModels.RegisteredGtin(gtin,"SUIT","ЧЕРНЫЙ","1",true,false,"fixture",Instant.now(),data);
        var a=row("201","52");a=new ProductRow(a.key(),a.title(),a.vendorArticle(),a.marketArticle(),a.category(),a.brand(),a.color(),a.size(),List.of(),"","",a.material(),a.tnved(),a.documentState(),a.gender(),true,"52");
        var b=new ProductRow(new ProductKey(1,Marketplace.WILDBERRIES,"101","202"),a.title(),a.vendorArticle(),a.marketArticle(),a.category(),a.brand(),a.color(),"54",List.of(),"","",a.material(),a.tnved(),a.documentState(),a.gender(),true,"54");
        assertEquals(2,CatalogErrorRepairService.candidates(card,List.of(a,b)).size());var p=service.prepare(shop,card(),key);assertThrows(IllegalArgumentException.class,()->service.apply(shop,List.of(p,p),List.of(p.draft(),p.draft()),ignored->{}));assertEquals(0,api.writes.get());
    }
    @Test void reportKeepsUnmatchedCatalogErrorsWithoutCreatingNativeRegistrations()throws Exception{
        var data=CatalogProductDataParser.parse(raw);new GtinSyncRepository().saveCatalog(1,Marketplace.WILDBERRIES,List.of(new GtinSyncModels.RegisteredGtin(gtin,"MISSING","ЧЕРНЫЙ","1",true,false,RegisteredGtinProof.source(shop),Instant.now(),data)));
        var cards=service.report(shop,Set.of(),true);assertEquals(1,cards.size());assertEquals(gtin,cards.getFirst().gtin());assertEquals(0,api.writes.get());
    }
    @Test void ozonRepairRunsNativeFeedAndRetainsProductAndOfferIdentity()throws Exception{
        var ozon=GtinWriteTestFixture.shop(Marketplace.OZON);var nativeKey=new ProductKey(2,Marketplace.OZON,"501","offer-501");
        try(var c=Database.getConnection();var q=c.createStatement()){q.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(2,'501','offer-501','9001','Suit','test')");}
        RegistrationSourceFixture.ready(ozon);var nativeSnapshot=new ProductSnapshot(nativeKey,"live:fixture",snapshot.title(),"",snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),List.of(),snapshot.attributes(),List.of(new ProductVariant("offer-501","SUIT","ЧЕРНЫЙ","52","",false,"52")),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(ozon),"source.category.name","Костюмы спортивные","sku","9001"));
        var settings=new ZnackRepository(new ZnackModels.ShopContext(2,"Fixture"));settings.saveSettings(settings.getSettings().withDefaultGoodsDocument("CONFORMITY_DECLARATION","FIXTURE-PERMIT","01.01.2026"));
        var oscope=ZnackRegistrationScope.fromSettings(settings.getSettings());var cs=new ZnackCardRegistrationWorkflow.CatalogSession("fixture",session.catalog(),oscope);
        var service=new CatalogErrorRepairService(s1->cs,(s1,k)->nativeSnapshot,(s1,k,catalog,progress)->{var workflow=new OzonCardRegistrationWorkflow(new OzonCardRegistrationRepository(),s2->catalog,(s2,k2)->nativeSnapshot,Runnable::run,(a,b,c,d)->false);assertTrue(workflow.resume(s1,k,false,(status,detail)->progress.accept(status)));});
        var row=new ProductRow(nativeKey,"Suit","SUIT","9001","Костюмы спортивные","Fixture","ЧЕРНЫЙ","52",List.of(),"","","хлопок","6112110000",DocumentState.UNVERIFIED,"Мужской",true,"52");
        var card=new CatalogErrorRepairService.ErrorCard(gtin,"SUIT","ЧЕРНЫЙ","1","CATALOG_CARD_ERRORS",List.of(row));var proposal=service.prepare(ozon,card,nativeKey);String run=service.apply(ozon,List.of(proposal),List.of(proposal.draft()),ignored->{});
        var stored=new OzonCardRegistrationRepository().find(nativeKey);assertEquals("9001",stored.sku());assertEquals(gtin,stored.gtin());assertEquals("fixture-feed",stored.feedId());assertEquals(Status.PROCESSING,stored.status());assertEquals("WAITING_REMOTE",new SyncRunRepository().find(run).orElseThrow().state());assertEquals(1,api.writes.get());assertEquals(0,api.allocations.get());
        var revisions=new OzonCardRegistrationRepository().reviewedRevisions(nativeKey);assertEquals(2,revisions.size());assertTrue(revisions.stream().allMatch(value->value.matches("[0-9a-f]{64}")),"Repair approval must bind both current marketplace source and Catalog schema");
        var expected=ZnackNationalCatalogService.buildPayload(gtin,proposal.draft(),"");expected.addProperty("good_id",91L);assertEquals(expected,JsonParser.parseString(stored.payload()));assertEquals(expected,api.sent,"Review stamping must not rebuild or silently replace the approved repair attributes");
        assertFalse(CatalogRepairCheckpoint.prepare(ozon,nativeKey,"9001",gtin,91,"{}",proposal.draft(),oscope));
    }
    @Test void invalidManualPermitCannotCreateACheckpointOrPost()throws Exception{
        var p=service.prepare(shop,card(),key);var attrs=new LinkedHashMap<>(p.draft().attributes());attrs.put(23557L,"OLD:::2026-01-01");var d=p.draft();var entered=new Draft(d.tnved(),d.feedTnved(),d.categoryId(),d.goodName(),d.brand(),attrs,d.attributeTypes());
        String run=service.apply(shop,List.of(p),List.of(entered),ignored->{});assertEquals("PARTIAL",new SyncRunRepository().find(run).orElseThrow().state());assertEquals(0,api.writes.get());assertEquals(Status.NOT_CREATED,new ZnackCardRegistrationRepository().find(1,201).status());
    }
    final class FixtureApi extends ZnackApiClient {
        AtomicInteger allocations=new AtomicInteger(),writes=new AtomicInteger();String permitScope="6112";JsonObject sent;
        public JsonElement generatedGtins(String b,String t){allocations.incrementAndGet();throw new AssertionError("No allocation or quota lookup for existing GTIN");}
        public JsonElement productCards(String b,String t,String g){var result=new JsonArray();result.add(raw.deepCopy());var root=new JsonObject();root.add("result",result);return root;}
        public JsonElement registrationPermitInfo(String b,String t,JsonArray request){var docs=request.deepCopy();for(var value:docs){var doc=value.getAsJsonObject();doc.addProperty("status_group",doc.get("number").getAsString().equals("OLD")?3:1);doc.addProperty("product_tnved",doc.get("number").getAsString().equals("OLD")?"6203":permitScope);}var result=new JsonObject();result.add("documents",docs);var out=new JsonObject();out.add("result",result);return out;}
        public JsonElement nationalCatalogCategories(String b,String t,String n){return JsonParser.parseString("{\"result\":[{\"cat_id\":1,\"cat_name\":\"Костюмы спортивные\",\"category_active\":true}]}");}
        public JsonElement nationalCatalogAttributes(String b,String t,long id){return JsonParser.parseString("{\"result\":[{\"attr_id\":35,\"attr_name\":\"Размер\",\"attr_required\":true,\"attr_value_type\":[\"РОССИЯ\"]}]}");}
        public JsonElement nationalCatalogAllAttributes(String b,String t,long id){return JsonParser.parseString("""
            {"result":[
              {"attr_id":35,"attr_name":"Размер","attr_field_type":"text","attr_value_type":["РОССИЯ"]},
              {"attr_id":36,"attr_name":"Цвет","attr_field_type":"text"},
              {"attr_id":13914,"attr_name":"Артикул","attr_field_type":"text"},
              {"attr_id":2483,"attr_name":"Состав","attr_field_type":"text"},
              {"attr_id":14013,"attr_name":"Пол","attr_field_type":"text"},
              {"attr_id":2478,"attr_name":"Наименование товара","attr_field_type":"text"},
              {"attr_id":2504,"attr_name":"Товарный знак","attr_field_type":"text"}
            ]}
            """);}
        public JsonElement submitNationalCatalogFeed(String b,String t,JsonElement request){writes.incrementAndGet();sent=request.getAsJsonObject().deepCopy();raw.add("good_detailed_status",JsonParser.parseString("[\"moderation\"]"));return JsonParser.parseString("{\"result\":{\"feed_id\":\"fixture-feed\"}}");}
        public JsonElement nationalCatalogFeedStatus(String b,String t,String feed){return JsonParser.parseString("{\"result\":{\"status\":\"Received\"}}");}
    }
}
