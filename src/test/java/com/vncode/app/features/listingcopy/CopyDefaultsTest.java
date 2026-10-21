package com.vncode.app.features.listingcopy;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonListingAdapter;
import com.vncode.app.integration.wb.WbListingAdapter;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CopyDefaultsTest {
    @TempDir Path root;
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'WB','WILDBERRIES',NULL,'fixture'),(2,'Ozon','OZON','22','fixture')");}}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    ProductSnapshot source(Marketplace market,String category,Map<String,String> metadata){
        var s=ListingCopyMappingTest.source(market);var values=new HashMap<>(s.metadata());values.putAll(metadata);
        return new ProductSnapshot(s.key(),s.revision(),s.title(),s.description(),s.brand(),s.vendorArticle(),category,s.media(),s.attributes(),s.variants().stream().map(v->new ProductVariant(v.id(),v.sellerArticle(),v.color(),v.size(),v.gtin(),false,v.russianSize())).toList(),s.dimensions(),s.documents(),s.checkedAt(),values);
    }
    ListingTargetAdapter adapter(Marketplace market){return market==Marketplace.OZON?new OzonListingAdapter(s->ListingCopyMappingTest.fixture("99:88"),i->{throw new java.io.IOException();}):new WbListingAdapter(s->ListingCopyMappingTest.fixture("99"),i->{throw new java.io.IOException();});}
    @Test void allFourDirectionsInheritSourceValuesWithoutReenteringAttributesPriceOrSizes()throws Exception{
        for(var from:Marketplace.values())for(var to:Marketplace.values()){
            var source=source(from,to==Marketplace.OZON?"99:88":"99",Map.of("source.price","1450","source.currency","RUB"));
            var options=CopyDefaults.initial(source,ListingCopyMappingTest.target(to),Set.of("v1","v2"),from==to?"":to==Marketplace.OZON?"99:88":"99");
            options=new CopyOptions(options.fields().stream().filter(f->!f.equals("images")).collect(java.util.stream.Collectors.toSet()),options.variantIds(),options.targetCategoryId(),options.attributeMapping(),options.attributeValues(),options.newArticles(),null,"RUB",options.documents(),options.kizMarked());
            var preview=adapter(to).preview(ListingCopyMappingTest.target(to),source,options);
            assertTrue(preview.publishable(),from+" -> "+to+" "+preview.issues());
            assertTrue(preview.requestJson().contains("Cotton"));assertTrue(preview.requestJson().contains("1450"));assertTrue(preview.requestJson().contains(source.title()));
            assertEquals("777",preview.options().attributeMapping().get("444"));
            assertFalse(source.variants().getFirst().gtinVerified(),"Copy must not manufacture a Catalog purchase proof");
        }
    }
    @Test void attributeIdsAreNotReusedAcrossMarketplacesAndAmbiguousNamesRequireAChoice(){
        var source=source(Marketplace.WILDBERRIES,"12",Map.of());
        var dictionary=List.of(new TargetAttribute("444","Unrelated","string",false,"",1,"","",false));
        assertFalse(CopyDefaults.mappings(source,Marketplace.OZON,"99:88",dictionary).containsKey("444"));
        dictionary=List.of(new TargetAttribute("77","Material","string",false,"",1,"","",false),new TargetAttribute("88","Материал","string",false,"",1,"","",false));
        assertFalse(CopyDefaults.mappings(source,Marketplace.OZON,"99:88",dictionary).containsKey("444"));
    }
    @Test void explicitOverridesRemainAndDifferentVariantPricesAreNotFlattened()throws Exception{
        var source=source(Marketplace.WILDBERRIES,"99",Map.of("source.price.v1","100","source.price.v2","200","source.currency","RUB"));
        var old=ListingCopyMappingTest.options(Marketplace.WILDBERRIES);var options=new CopyOptions(old.fields(),old.variantIds(),"99",Map.of(),Map.of("777",List.of("Wool")),Map.of("*","MY-ARTICLE"),null,"RUB",List.of(),true,Map.of(),Map.of("title","Changed title"));
        var preview=adapter(Marketplace.WILDBERRIES).preview(ListingCopyMappingTest.target(Marketplace.WILDBERRIES),source,options);assertTrue(preview.publishable(),preview.issues().toString());
        var listing=JsonParser.parseString(preview.requestJson()).getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("variants").get(0).getAsJsonObject();
        assertEquals("Changed title",listing.get("title").getAsString());assertEquals("MY-ARTICLE",listing.get("vendorCode").getAsString());assertTrue(listing.toString().contains("Wool"));
        assertEquals(100,listing.getAsJsonArray("sizes").get(0).getAsJsonObject().get("price").getAsInt());assertEquals(200,listing.getAsJsonArray("sizes").get(1).getAsJsonObject().get("price").getAsInt());
    }
    @Test void manufacturerAndRussianSizesMapToVariantFieldsInBothDirections(){
        var original=source(Marketplace.OZON,"12:13",Map.of());
        var source=new ProductSnapshot(original.key(),original.revision(),original.title(),original.description(),original.brand(),original.vendorArticle(),original.categoryId(),original.media(),List.of(new ProductAttribute("1001","Размер производителя",List.of("M"),""),new ProductAttribute("1002","Российский размер",List.of("46"),"")),List.of(original.variants().getFirst()),original.dimensions(),original.documents(),original.checkedAt(),original.metadata());
        var maps=CopyDefaults.mappings(source,Marketplace.WILDBERRIES,"99",List.of());assertEquals("@size",maps.get("1001"));assertEquals("@russianSize",maps.get("1002"));
        var dictionary=List.of(new TargetAttribute("1001","Размер производителя","string",true,"",1,"","",false),new TargetAttribute("1002","Российский размер","string",true,"",1,"","",false));
        var wb=source(Marketplace.WILDBERRIES,"99",Map.of());var options=CopyDefaults.complete(wb,ListingCopyMappingTest.target(Marketplace.OZON),CopyDefaults.initial(wb,ListingCopyMappingTest.target(Marketplace.OZON),Set.of("v1","v2"),"99:88"),dictionary);
        assertEquals(List.of("46"),options.variantValues().get("v1").get("1002"));assertEquals(List.of("48"),options.variantValues().get("v2").get("1002"));
    }
    @Test void foreignCurrencyIsNotSilentlyReinterpretedAsWbRubles()throws Exception{
        var source=source(Marketplace.OZON,"12:13",Map.of("source.price","100","source.currency","CNY"));var old=ListingCopyMappingTest.options(Marketplace.WILDBERRIES);
        var options=new CopyOptions(old.fields(),old.variantIds(),"99",old.attributeMapping(),old.attributeValues(),old.newArticles(),null,"RUB",List.of(),false,old.variantValues());
        var preview=adapter(Marketplace.WILDBERRIES).preview(ListingCopyMappingTest.target(Marketplace.WILDBERRIES),source,options);assertFalse(preview.publishable());assertTrue(preview.issues().stream().anyMatch(i->i.code().equals("WB_PRICE_CURRENCY_UNVERIFIED")));
    }
    @Test void ozonKeepsDifferentSourceSizePricesAndOnlyOverridesTheEditedVariant()throws Exception{
        var source=source(Marketplace.WILDBERRIES,"12",Map.of("source.price.v1","100","source.price.v2","200","source.currency","RUB"));
        var old=CopyDefaults.initial(source,ListingCopyMappingTest.target(Marketplace.OZON),Set.of("v1","v2"),"99:88");
        var fields=new HashSet<>(old.fields());fields.remove("images");
        var options=new CopyOptions(fields,old.variantIds(),old.targetCategoryId(),Map.of(),Map.of(),Map.of(),null,"RUB",List.of(),false,Map.of(),Map.of(),Map.of());
        var preview=adapter(Marketplace.OZON).preview(ListingCopyMappingTest.target(Marketplace.OZON),source,options);assertTrue(preview.publishable(),preview.issues().toString());
        var items=JsonParser.parseString(preview.requestJson()).getAsJsonObject().getAsJsonArray("items");assertEquals("100",items.get(0).getAsJsonObject().get("price").getAsString());assertEquals("200",items.get(1).getAsJsonObject().get("price").getAsString());assertNull(preview.options().targetPrice());
        var edited=new CopyOptions(fields,old.variantIds(),old.targetCategoryId(),Map.of(),Map.of(),Map.of(),null,"RUB",List.of(),false,Map.of(),Map.of(),Map.of("v1",new BigDecimal("150")));
        var next=adapter(Marketplace.OZON).preview(ListingCopyMappingTest.target(Marketplace.OZON),source,edited);assertTrue(next.publishable());items=JsonParser.parseString(next.requestJson()).getAsJsonObject().getAsJsonArray("items");assertEquals("150",items.get(0).getAsJsonObject().get("price").getAsString());assertEquals("200",items.get(1).getAsJsonObject().get("price").getAsString());
    }
    @Test void inheritedPricesCannotChangeCurrencyWithoutExplicitNewPrices()throws Exception{
        var source=source(Marketplace.WILDBERRIES,"12",Map.of("source.price.v1","100","source.price.v2","200","source.currency","CNY"));var old=ListingCopyMappingTest.options(Marketplace.OZON);
        var options=new CopyOptions(old.fields(),old.variantIds(),old.targetCategoryId(),old.attributeMapping(),Map.of(),old.newArticles(),null,"RUB",List.of(),false,old.variantValues());
        var preview=adapter(Marketplace.OZON).preview(ListingCopyMappingTest.target(Marketplace.OZON),source,options);assertFalse(preview.publishable());assertTrue(preview.issues().stream().anyMatch(i->i.code().equals("SOURCE_PRICE_CURRENCY_MISMATCH")));
    }
    @Test void categoryNameMatchMustBeUniqueAndDoesNotReuseForeignIds(){
        var source=source(Marketplace.WILDBERRIES,"12",Map.of("source.category.name","Trousers"));
        assertEquals("99:88",CopyDefaults.category(source,Marketplace.OZON,List.of(new TargetCategory("99:88","Clothing / Trousers"))).id());
        assertNull(CopyDefaults.category(source,Marketplace.OZON,List.of(new TargetCategory("99:88","Clothing / Trousers"),new TargetCategory("11:22","Other / Trousers"))));
        assertNull(CopyDefaults.category(source,Marketplace.OZON,List.of(new TargetCategory("12","Unrelated"))));
    }
    @Test void legacyDraftsWithoutVariantPricesRemainReadableAndExplicitPricesSurviveScoping(){
        var old=ListingCopyMappingTest.options(Marketplace.OZON);var json=ProductJson.GSON.toJsonTree(old).getAsJsonObject();json.remove("variantPrices");
        assertTrue(ProductJson.GSON.fromJson(json,CopyOptions.class).variantPrices().isEmpty());
        var next=new CopyOptions(old.fields(),old.variantIds(),old.targetCategoryId(),old.attributeMapping(),old.attributeValues(),old.newArticles(),null,"RUB",List.of(),false,old.variantValues(),old.textOverrides(),Map.of("v1",new BigDecimal("123")));
        assertEquals(new BigDecimal("123"),ListingCopyService.options(next,Set.of("v1")).variantPrices().get("v1"));
    }
    @Test void unsupportedSourceContentIsReportedInsteadOfSilentlyDiscarded()throws Exception{
        var source=source(Marketplace.WILDBERRIES,"12",Map.of("source.payload","{\"video\":\"https://example.invalid/product.mp4\",\"rich_content\":{\"blocks\":[1]}}"));
        var preview=adapter(Marketplace.OZON).preview(ListingCopyMappingTest.target(Marketplace.OZON),source,ListingCopyMappingTest.options(Marketplace.OZON));
        assertTrue(preview.issues().stream().anyMatch(i->i.code().equals("SOURCE_FIELD_UNSUPPORTED")&&i.field().equals("video")));assertTrue(preview.issues().stream().anyMatch(i->i.code().equals("SOURCE_FIELD_UNSUPPORTED")&&i.field().equals("rich_content")));assertFalse(preview.requestJson().contains("product.mp4"));
    }
    @Test void explicitClearingOfANamedFieldDoesNotRestoreTheSourceAttribute(){
        var source=source(Marketplace.OZON,"12:13",Map.of());var old=ListingCopyMappingTest.options(Marketplace.WILDBERRIES);
        var options=new CopyOptions(old.fields(),old.variantIds(),old.targetCategoryId(),Map.of("444","@brand"),Map.of(),old.newArticles(),null,"RUB",List.of(),false,Map.of(),Map.of("brand",""));
        assertEquals("",AbstractListingAdapter.namedValue(source,options,"brand","Original brand"));
    }
    @Test void allOriginalOzonBarcodesRemainAvailableAndUnsupportedTransferIsExplicit()throws Exception{
        var source=source(Marketplace.OZON,"12:13",Map.of("source.payload","{\"attributes\":{\"barcode\":\"000123\",\"barcodes\":[\"000123\",\"000456\"]}}"));
        assertEquals(List.of("000123","000456"),CopyDefaults.barcodes(source,source.variants().getFirst()));
        var old=ListingCopyMappingTest.options(Marketplace.OZON);var options=ListingCopyService.options(old,Set.of("v1"));
        var preview=adapter(Marketplace.OZON).preview(ListingCopyMappingTest.target(Marketplace.OZON),source,options);assertFalse(preview.publishable());assertTrue(preview.issues().stream().anyMatch(i->i.code().equals("OWN_BARCODE_SET_UNSUPPORTED")));
    }
    @Test void missingRawPayloadKeepsEveryVariantBarcodeWithoutPretendingItIsARegisteredGtin(){
        for(var market:Marketplace.values()){
            var original=source(market,"12",Map.of("source.payload","{}"));var variant=new ProductVariant("v1","A","Black","M","",false,"46",List.of("000123","000456"),ProductVariant.GtinEvidence.UNVERIFIED);
            assertEquals(List.of("000123","000456"),CopyDefaults.barcodes(original,variant));assertEquals("",variant.gtin());assertFalse(variant.gtinVerified());
        }
    }
}
