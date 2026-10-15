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
}
