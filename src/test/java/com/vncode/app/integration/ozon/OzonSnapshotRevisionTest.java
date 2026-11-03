package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class OzonSnapshotRevisionTest {
    @TempDir Path root;
    final Shop shop=new Shop(1,"Ozon",Marketplace.OZON,"22","fixture");
    final ProductKey key=new ProductKey(1,Marketplace.OZON,"321","ARTICLE");
    JsonObject info,attrs;
    OzonProductSnapshotReader reader;
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Ozon','OZON','22','fixture')");}
        info=JsonParser.parseString("{\"id\":321,\"offer_id\":\"ARTICLE\",\"sku\":99,\"price\":\"100\",\"statuses\":{\"status\":\"processing\",\"status_updated_at\":\"2026-11-03T00:00:00Z\"}}").getAsJsonObject();
        attrs=JsonParser.parseString("{\"id\":321,\"offer_id\":\"ARTICLE\",\"name\":\"Fixture\",\"description_category_id\":12,\"type_id\":13,\"dimension_unit\":\"mm\",\"weight_unit\":\"g\",\"weight\":100,\"barcodes\":[\"4006381333931\"],\"images\":[\"https://ir.ozone.ru/first.jpg\",\"https://ir.ozone.ru/second.jpg\"],\"attributes_with_defaults\":[],\"attributes\":[{\"id\":85,\"values\":[{\"value\":\"Fixture brand\"}]},{\"id\":226,\"values\":[{\"value\":\"Мужской\"}]}],\"complex_attributes\":[{\"id\":900,\"complex_id\":50,\"values\":[{\"value\":\"First\"}]},{\"id\":900,\"complex_id\":50,\"values\":[{\"value\":\"Second\"}]}]}").getAsJsonObject();
        reader=new OzonProductSnapshotReader(owner->(path,body,mutation)->{
            assertFalse(mutation);var response=new JsonObject();
            if(path.equals("v3/product/info/list")){var rows=new JsonArray();rows.add(info.deepCopy());response.add("items",rows);}
            else if(path.equals("v4/product/info/attributes")){var rows=new JsonArray();rows.add(attrs.deepCopy());response.add("result",rows);}
            else if(path.equals("v1/description-category/attribute"))return JsonParser.parseString("{\"result\":[{\"id\":85,\"name\":\"Бренд\"},{\"id\":226,\"name\":\"Пол\"}]}");
            else return JsonParser.parseString("{\"result\":{\"certificates\":[],\"page_count\":1}}");
            return response;
        });
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    @Test void moderationAndSimpleAttributeOrderingDoNotInvalidateAnUnchangedProduct()throws Exception{
        var before=reader.readForSynchronization(shop,key);
        info.getAsJsonObject("statuses").addProperty("status","processed");info.getAsJsonObject("statuses").addProperty("status_updated_at","2026-11-03T00:01:00Z");
        var old=attrs.getAsJsonArray("attributes");var reordered=new JsonArray();reordered.add(old.get(1));reordered.add(old.get(0));attrs.add("attributes",reordered);
        attrs.getAsJsonArray("attributes_with_defaults").add(226);
        var after=reader.readForSynchronization(shop,key);
        assertEquals(before.revision(),after.revision());assertEquals(before.attributes(),after.attributes());
        assertNotEquals(before.metadata().get("source.payload"),after.metadata().get("source.payload"),"Keep the actual raw response for diagnostics");
        assertEquals("processed",JsonParser.parseString(after.metadata().get("source.payload")).getAsJsonObject().getAsJsonObject("info").getAsJsonObject("statuses").get("status").getAsString());
    }
    @ParameterizedTest @ValueSource(strings={"attribute","category","sku","title","barcodes","images","price","complex"})
    void actualProductChangesStillInvalidateTheSnapshot(String field)throws Exception{
        var before=reader.readForSynchronization(shop,key);
        switch(field){
            case "attribute" -> attrs.getAsJsonArray("attributes").get(0).getAsJsonObject().getAsJsonArray("values").get(0).getAsJsonObject().addProperty("value","Different brand");
            case "category" -> attrs.addProperty("type_id",14);
            case "sku" -> info.addProperty("sku",100);
            case "title" -> attrs.addProperty("name","Different title");
            case "barcodes" -> attrs.getAsJsonArray("barcodes").add("4006381333948");
            case "images" -> attrs.getAsJsonArray("images").set(0,new JsonPrimitive("https://ir.ozone.ru/changed.jpg"));
            case "price" -> info.addProperty("price","101");
            case "complex" -> {var rows=attrs.getAsJsonArray("complex_attributes");var swapped=new JsonArray();swapped.add(rows.get(1));swapped.add(rows.get(0));attrs.add("complex_attributes",swapped);}
            default -> throw new AssertionError();
        }
        assertNotEquals(before.revision(),reader.readForSynchronization(shop,key).revision(),field);
    }
}
