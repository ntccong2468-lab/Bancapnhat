package com.vncode.app.features.products;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonListingAdapter;
import com.vncode.app.integration.wb.WbListingAdapter;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductSuggestionsTest {
    @TempDir Path root;
    Shop wb=new Shop(1,"WB","fixture"),ozon=new Shop(2,"Ozon",Marketplace.OZON,"22","fixture");
    @BeforeEach void setup()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace,client_id) VALUES(1,'WB','fixture','WILDBERRIES',NULL),(2,'Ozon','fixture','OZON','22')");}}
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    @Test void wbBrandsFollowNativeCursorAndCacheOnlyWithinTheSameOwner()throws Exception{
        var calls=new AtomicInteger();
        var adapter=new WbListingAdapter(s->(path,b,m)->{assertFalse(m);calls.incrementAndGet();assertTrue(path.startsWith("api/content/v1/brands?subjectId=9"));return JsonParser.parseString(path.contains("next=40")?"{\"brands\":[{\"name\":\"Brand Two\"}],\"next\":0}":"{\"brands\":[{\"name\":\"Brand One\"}],\"next\":40}");},i->{throw new java.io.IOException();});
        var service=new ProductSuggestionService((s,k)->snapshot(s,k),s->adapter,k->Optional.of(snapshot(wb,k)));
        var key=new ProductKey(1,Marketplace.WILDBERRIES,"123","1");
        assertEquals(List.of("Brand One","Brand Two"),service.suggest(wb,List.of(key),"brand","Brand").values());
        assertEquals(List.of("Brand Two"),service.suggest(wb,List.of(key),"brand","Two").values());assertEquals(2,calls.get());
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated' WHERE id=1");}
        service.suggest(new Shop(1,"WB","rotated"),List.of(key),"brand","Brand");assertEquals(4,calls.get());
    }
    ProductSnapshot snapshot(Shop s,ProductKey key){return new ProductSnapshot(key,"live:own","Title","","Brand","own","9",List.of(),List.of(),List.of(),new PackageDimensions(null,null,null,null),List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(s)));}
    @Test void foreignSelectionIsRejectedBeforeAnyLookup()throws Exception{
        var service=new ProductSuggestionService((s,k)->{fail("Must not read foreign card");return null;},s->{fail("Must not call seller");return null;},k->{fail("Must not read foreign cache");return Optional.empty();});
        assertThrows(SecurityException.class,()->service.suggest(wb,List.of(new ProductKey(2,Marketplace.OZON,"123","x")),"brand",""));
    }
    @Test void ozonTnvedUsesTheRealCategoryDefinitionAndSearchContract()throws Exception{
        var calls=new AtomicInteger();
        var adapter=new OzonListingAdapter(s->(path,b,m)->{assertFalse(m);calls.incrementAndGet();var request=b.getAsJsonObject();assertEquals(9,request.get("description_category_id").getAsInt());assertEquals(8,request.get("type_id").getAsInt());
            if(path.equals("v1/description-category/attribute"))return JsonParser.parseString("{\"result\":[{\"id\":22232,\"name\":\"ТН ВЭД коды ЕАЭС\",\"dictionary_id\":124412395}]}");
            assertEquals("v1/description-category/attribute/values/search",path);assertEquals(22232,request.get("attribute_id").getAsInt());assertEquals("6103",request.get("value").getAsString());assertEquals(100,request.get("limit").getAsInt());
            return JsonParser.parseString("{\"result\":[{\"id\":77,\"value\":\"6103220000\"}]}");},i->{throw new java.io.IOException();});
        assertTrue(ProductFieldNames.matches("tnved","ТН ВЭД коды ЕАЭС"));assertEquals(List.of("6103220000"),adapter.suggestValues(ozon,"9:8","tnved","6103"));assertEquals(2,calls.get());
    }
    @Test void ozonLargeDictionaryResolvesExactSelectedValueAndRejectsNearMatches()throws Exception{
        var queries=new AtomicInteger();
        var adapter=new OzonListingAdapter(s->(path,b,m)->{
            assertFalse(m);if(path.equals("v1/description-category/attribute"))return JsonParser.parseString("{\"result\":[{\"id\":31,\"name\":\"Бренд в одежде и обуви\",\"dictionary_id\":28732849,\"type\":\"string\"}]}");
            assertEquals("v1/description-category/attribute/values/search",path,"Do not download a huge brand dictionary");queries.incrementAndGet();
            return JsonParser.parseString("{\"result\":[{\"id\":8,\"value\":\"Exact Brand\"},{\"id\":9,\"value\":\"Exact Brand Plus\"}]}");
        },i->{throw new java.io.IOException();});
        var snapshot=new ProductSnapshot(new ProductKey(2,Marketplace.OZON,"123","own"),"live:own","Title","","Old","own","9:8",List.of(),List.of(),List.of(),new PackageDimensions(null,null,null,null),List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(ozon),"source.payload","{\"info\":{\"price\":\"123\"},\"attributes\":{\"offer_id\":\"own\",\"attributes\":[]}}"));
        var exact=adapter.previewUpdate(ozon,snapshot,Map.of("brand",List.of("Exact Brand")),null).preview();assertTrue(exact.publishable());assertTrue(exact.requestJson().contains("\"dictionary_value_id\":8"));
        var missing=adapter.previewUpdate(ozon,snapshot,Map.of("brand",List.of("Exact")),null).preview();assertFalse(missing.publishable());assertTrue(missing.issues().stream().anyMatch(v->v.code().equals("DICTIONARY_VALUE_REQUIRED")));assertEquals(2,queries.get());
    }
}
