package com.vncode.app.features.listingcopy;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.models.Shop;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.wb.WbListingAdapter;
import com.vncode.app.integration.ozon.OzonListingAdapter;
import com.vncode.app.integration.marketplace.Marketplace;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class ListingCopyMappingTest {
    @TempDir Path root;
    public static Shop target(Marketplace m){return new Shop(m==Marketplace.OZON?2:1,"Target",m,m==Marketplace.OZON?"22":null,"fixture");}
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'WB','WILDBERRIES',NULL,'fixture'),(2,'Ozon','OZON','22','fixture')");}}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    public static ProductSnapshot source(Marketplace m){return new ProductSnapshot(new ProductKey(9,m,"98765","v1"),"live:source","VN Product","Text","Brand","OLD","12",List.of(),List.of(new ProductAttribute("444","Material",List.of("Cotton"),null)),List.of(new ProductVariant("v1","OLD","Black","M","04610689132457",true,"46"),new ProductVariant("v2","OLD","Black","L","04610689130422",true,"48")),new PackageDimensions(new BigDecimal("12"),new BigDecimal("13"),new BigDecimal("14"),new BigDecimal("0.123")),List.of(),Instant.now(),Map.of("source.payload","{\"nmID\":98765,\"chrtID\":98766,\"sku\":98767,\"rawKiz\":\"FORBIDDEN\"}"));}
    public static ListingTransport fixture(String category){return (path,body,mutation)->JsonParser.parseString(
        path.startsWith("content/v2/object/all")?"{\"data\":[{\"subjectID\":99,\"subjectName\":\"Clothing\"}],\"error\":false}":
        path.startsWith("content/v2/object/charcs")?"{\"data\":[{\"charcID\":777,\"name\":\"Material\",\"required\":true,\"charcType\":1,\"maxCount\":2}],\"error\":false}":
        path.equals("v1/description-category/tree")?"{\"result\":[{\"description_category_id\":99,\"category_name\":\"Clothing\",\"children\":[{\"type_id\":88,\"type_name\":\"Trousers\",\"children\":[]}]}]}":
        path.equals("v1/description-category/attribute")?"{\"result\":[{\"id\":777,\"name\":\"Material\",\"is_required\":true,\"type\":\"string\",\"dictionary_id\":5,\"max_value_count\":2},{\"id\":888,\"name\":\"Size\",\"type\":\"string\"},{\"id\":999,\"name\":\"Brand\",\"type\":\"string\"},{\"id\":4191,\"name\":\"Description\",\"type\":\"string\"}]}":
        path.equals("v1/description-category/attribute/values")?"{\"result\":[{\"id\":66,\"value\":\"Cotton\"}],\"has_next\":false}":
        path.equals("v3/product/info/list")?"{\"items\":[]}":"{\"cards\":[]}");}
    public static CopyOptions options(Marketplace m){return new CopyOptions(Set.of("title","description","brand","attributes","gtin","dimensions","sizes"),Set.of("v1","v2"),m==Marketplace.OZON?"99:88":"99",Map.of("444","777","@brand","999","@description","4191"),Map.of(),Map.of("*","NEW","v1","NEW-M","v2","NEW-L"),new BigDecimal("100"),"RUB",List.of(),false,Map.of("v1",Map.of("888",List.of("M")),"v2",Map.of("888",List.of("L"))));}
    ListingTargetAdapter adapter(Marketplace m){return m==Marketplace.OZON?new OzonListingAdapter(s->fixture("99:88"),image->{throw new java.io.IOException("Unexpected media fixture");}):new WbListingAdapter(s->fixture("99"),image->{throw new java.io.IOException("Unexpected media fixture");});}
    @Test void allFourDirectionsUseDestinationDictionaries()throws Exception{
        for(var from:Marketplace.values())for(var to:Marketplace.values()){
            var preview=adapter(to).preview(target(to),source(from),options(to));assertTrue(preview.publishable(),preview.issues().toString());
            assertTrue(preview.requestJson().contains("777"));assertFalse(preview.requestJson().contains("444"));assertTrue(preview.requestJson().contains(to==Marketplace.OZON?"dictionary_value_id":"subjectID"));
        }
    }
    @Test void leadingZeroGtinAndVariantsStayDistinct()throws Exception{
        var wb=adapter(Marketplace.WILDBERRIES).preview(target(Marketplace.WILDBERRIES),source(Marketplace.OZON),options(Marketplace.WILDBERRIES));
        var variants=JsonParser.parseString(wb.requestJson()).getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("variants");var sizes=variants.get(0).getAsJsonObject().getAsJsonArray("sizes");
        assertEquals(2,sizes.size());assertEquals("04610689132457",sizes.get(0).getAsJsonObject().getAsJsonArray("skus").get(0).getAsString());assertEquals("46",sizes.get(0).getAsJsonObject().get("wbSize").getAsString());
        var oz=adapter(Marketplace.OZON).preview(target(Marketplace.OZON),source(Marketplace.WILDBERRIES),options(Marketplace.OZON));assertEquals(2,JsonParser.parseString(oz.requestJson()).getAsJsonObject().getAsJsonArray("items").size());
    }
    @Test void mandatoryPriceOrDocumentBlocksPublish()throws Exception{
        var o=options(Marketplace.OZON);var noPrice=new CopyOptions(o.fields(),o.variantIds(),o.targetCategoryId(),o.attributeMapping(),o.attributeValues(),o.newArticles(),null,o.currency(),o.documents(),false);
        assertFalse(adapter(Marketplace.OZON).preview(target(Marketplace.OZON),source(Marketplace.WILDBERRIES),noPrice).publishable());
        var doc=new ProductDocument("source-doc","2","D",null,null,"Owner","Scope",null,Marketplace.WILDBERRIES,DocumentState.UNVERIFIED,"",null);
        var withDoc=new CopyOptions(o.fields(),o.variantIds(),o.targetCategoryId(),o.attributeMapping(),o.attributeValues(),o.newArticles(),o.targetPrice(),"RUB",List.of(doc),false);
        assertFalse(adapter(Marketplace.OZON).preview(target(Marketplace.OZON),source(Marketplace.WILDBERRIES),withDoc).publishable());
    }
    @Test void sourceIdsAndRawKizNeverReachDestination()throws Exception{
        for(var m:Marketplace.values()){var p=adapter(m).preview(target(m),source(Marketplace.WILDBERRIES),options(m));for(String forbidden:List.of("98765","98766","98767","FORBIDDEN","chrtID","rawKiz"))assertFalse(p.requestJson().contains(forbidden));assertFalse(p.toString().contains("source.payload"));}
    }
    @Test void packageUnitsConvertExactly()throws Exception{
        var p=adapter(Marketplace.OZON).preview(target(Marketplace.OZON),source(Marketplace.WILDBERRIES),options(Marketplace.OZON));var item=JsonParser.parseString(p.requestJson()).getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject();assertEquals(120,item.get("depth").getAsInt());assertEquals(123,item.get("weight").getAsInt());
        var s=source(Marketplace.WILDBERRIES);var bad=new ProductSnapshot(s.key(),s.revision(),s.title(),s.description(),s.brand(),s.vendorArticle(),s.categoryId(),s.media(),s.attributes(),s.variants(),new PackageDimensions(new BigDecimal("0.001"),BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE),s.documents(),s.checkedAt(),s.metadata());assertFalse(adapter(Marketplace.OZON).preview(target(Marketplace.OZON),bad,options(Marketplace.OZON)).publishable());
    }
    @Test void complexAttributeGroupsKeepDestinationIdsAndSeparateValues()throws Exception{
        var s=source(Marketplace.OZON);var full=new ProductSnapshot(s.key(),s.revision(),s.title(),s.description(),s.brand(),s.vendorArticle(),s.categoryId(),s.media(),List.of(new ProductAttribute("900","Group",List.of("Cotton"),null,"50",0),new ProductAttribute("900","Group",List.of("Wool"),null,"50",1)),s.variants(),s.dimensions(),s.documents(),s.checkedAt(),s.metadata());
        var old=options(Marketplace.OZON);var opts=new CopyOptions(old.fields(),old.variantIds(),old.targetCategoryId(),Map.of("50:900","777","@brand","999","@description","4191"),old.attributeValues(),old.newArticles(),old.targetPrice(),old.currency(),old.documents(),false,old.variantValues());
        var adapter=new OzonListingAdapter(shop->(p,b,m)->{if(p.equals("v1/description-category/attribute"))return JsonParser.parseString(fixture("99:88").call(p,b,m).toString().replace("\"id\":777,","\"id\":777,\"attribute_complex_id\":90,"));if(p.equals("v1/description-category/attribute/values"))return JsonParser.parseString("{\"result\":[{\"id\":66,\"value\":\"Cotton\"},{\"id\":67,\"value\":\"Wool\"}],\"has_next\":false}");return fixture("99:88").call(p,b,m);},image->{throw new java.io.IOException();});
        var preview=adapter.preview(target(Marketplace.OZON),full,opts);assertTrue(preview.publishable(),preview.issues().toString());var item=JsonParser.parseString(preview.requestJson()).getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject();assertEquals(2,item.getAsJsonArray("complex_attributes").size());String complex=item.getAsJsonArray("complex_attributes").toString();assertTrue(complex.contains("90"));assertFalse(complex.contains("900"));assertFalse(complex.contains("50"));assertTrue(complex.contains("Wool"));
    }
}
