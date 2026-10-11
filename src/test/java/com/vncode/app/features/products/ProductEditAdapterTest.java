package com.vncode.app.features.products;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonListingAdapter;
import com.vncode.app.integration.wb.WbListingAdapter;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductEditAdapterTest {
    @TempDir Path root;
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace,client_id) VALUES(1,'WB','fixture','WILDBERRIES',NULL),(2,'Ozon','fixture','OZON','22')");}}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    ProductSnapshot fresh(Shop s,String raw){return new ProductSnapshot(new ProductKey(s.getId(),s.getMarketplace(),"123","1"),"live:original","Keep","Keep description","Old","own",s.getMarketplace()==Marketplace.OZON?"9:8":"9",List.of(),List.of(),List.of(),new PackageDimensions(null,null,null,null),List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(s),"source.payload",raw));}
    @Test void wbBrandEditPreservesSizeIdsBarcodesMarkingAndOtherCharacteristics()throws Exception{
        var s=new Shop(1,"WB","fixture");var f=fresh(s,"{\"nmID\":123,\"vendorCode\":\"own\",\"kizMarked\":true,\"brand\":\"Old\",\"title\":\"Keep\",\"description\":\"Keep description\",\"sizes\":[{\"chrtID\":456,\"techSize\":\"M\",\"wbSize\":\"48\",\"skus\":[\"4612345678900\"]}],\"characteristics\":[{\"id\":23,\"value\":[\"Cotton\"]}],\"dimensions\":{\"width\":10,\"isValid\":true}}");
        var a=new WbListingAdapter(owner->(p,b,m)->JsonParser.parseString("{\"data\":[]}"),i->{throw new java.io.IOException();});var r=JsonParser.parseString(a.previewUpdate(s,f,Map.of("brand",List.of("New")),null).preview().requestJson()).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals("New",r.get("brand").getAsString());assertEquals("Keep description",r.get("description").getAsString());assertEquals(456,r.getAsJsonArray("sizes").get(0).getAsJsonObject().get("chrtID").getAsInt());assertTrue(r.get("kizMarked").getAsBoolean());assertEquals("Cotton",r.getAsJsonArray("characteristics").get(0).getAsJsonObject().getAsJsonArray("value").get(0).getAsString());assertFalse(r.getAsJsonObject("dimensions").has("isValid"));
    }
    @Test void ozonOwnComplexAttributesUseImportShapeWithoutLosingValues()throws Exception{
        var s=new Shop(2,"Ozon",Marketplace.OZON,"22","fixture");var f=fresh(s,"{\"info\":{\"price\":\"123\",\"currency_code\":\"RUB\"},\"attributes\":{\"offer_id\":\"own\",\"name\":\"Keep\",\"description_category_id\":9,\"type_id\":8,\"attributes\":[{\"id\":5,\"values\":[{\"value\":\"Untouched\"}]}],\"complex_attributes\":[{\"id\":6,\"complex_id\":7,\"values\":[{\"value\":\"One\"}]},{\"id\":8,\"complex_id\":7,\"values\":[{\"value\":\"Two\"}]}]}}");
        var a=new OzonListingAdapter(owner->(p,b,m)->JsonParser.parseString("{\"result\":[{\"id\":85,\"name\":\"Бренд\",\"type\":\"string\",\"max_value_count\":1}]}"),i->{throw new java.io.IOException();});var p=a.previewUpdate(s,f,Map.of("brand",List.of("New")),null).preview();assertTrue(p.publishable());var r=JsonParser.parseString(p.requestJson()).getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject();
        var group=r.getAsJsonArray("complex_attributes").get(0).getAsJsonObject();assertEquals(2,group.getAsJsonArray("attributes").size());assertEquals("One",group.getAsJsonArray("attributes").get(0).getAsJsonObject().getAsJsonArray("values").get(0).getAsJsonObject().get("value").getAsString());assertEquals("123",r.get("price").getAsString());assertEquals(2,r.getAsJsonArray("attributes").size());
    }
}
