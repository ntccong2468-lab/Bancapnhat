package com.vncode.app.features.products;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.wb.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.features.listingcopy.CopyState;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WbProductReadStabilityTest {
    @TempDir Path root;Shop shop=new Shop(1,"Owner","fixture");ProductKey key=new ProductKey(1,Marketplace.WILDBERRIES,"123","1");
    AtomicInteger reads=new AtomicInteger(),writes=new AtomicInteger();String brand="Old";boolean duplicate,reversePhotos;
    @BeforeEach void setup()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Owner','WILDBERRIES','fixture')");}}
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    ListingTransport transport(){return (path,body,mutation)->{
        if(mutation){assertEquals("content/v2/cards/update",path);writes.incrementAndGet();brand=body.getAsJsonArray().get(0).getAsJsonObject().get("brand").getAsString();return JsonParser.parseString("{\"error\":false}");}
        if(path.startsWith("content/v2/object/charcs"))return JsonParser.parseString("{\"data\":[]}");
        var card=JsonParser.parseString("""
         {"nmID":123,"vendorCode":"own","subjectID":9,"title":"Keep","brand":"Old","description":"Keep",
          "characteristics":[{"id":23,"name":"Состав","value":["Cotton"]},{"id":13,"name":"Other","value":["Keep"]}],
          "sizes":[{"chrtID":1,"techSize":"M","skus":[]}],"dimensions":{},
          "photos":[{"big":"https://example.test/first.jpg"},{"big":"https://example.test/second.jpg"}]}
         """).getAsJsonObject();card.addProperty("brand",brand);var a=card.getAsJsonArray("characteristics");
        if(duplicate)a.add(a.get(0).deepCopy());
        if(reads.incrementAndGet()%2==0){var reversed=new JsonArray();for(int n=a.size()-1;n>=0;n--)reversed.add(a.get(n));card.add("characteristics",reversed);}
        if(reversePhotos){var photos=card.getAsJsonArray("photos");var reversed=new JsonArray();reversed.add(photos.get(1));reversed.add(photos.get(0));card.add("photos",reversed);}
        var cards=new JsonArray();cards.add(card);var result=new JsonObject();result.add("cards",cards);return result;
    };}
    @Test void reorderingAttributesDoesNotBlockConfirmedEditButActualValuesStillChangeRevision()throws Exception{
        var reader=new WbProductSnapshotReader(s->transport());var adapter=new WbListingAdapter(s->transport(),i->{throw new java.io.IOException();});
        var service=new ProductBulkEditService(new ProductEditRepository(),reader::readForSynchronization,s->adapter);
        var first=reader.readForSynchronization(shop,key);var second=reader.readForSynchronization(shop,key);assertEquals(first.revision(),second.revision());assertEquals(first.metadata().get("source.payload"),second.metadata().get("source.payload"));
        var preview=service.preview(shop,List.of(key),new ProductBulkEdit("brand",List.of("New"),null,Map.of()));service.apply(shop,preview.id(),true);
        assertEquals(1,writes.get());assertEquals("New",brand);assertEquals(CopyState.RECONCILED,service.reconcile(shop,preview.id()).items().getFirst().state());
        assertNotEquals(first.revision(),reader.readForSynchronization(shop,key).revision());
    }
    @Test void imageOrderIsMeaningfulAndAmbiguousAttributeIdsAreRejected()throws Exception{
        var reader=new WbProductSnapshotReader(s->transport());var first=reader.readForSynchronization(shop,key);reversePhotos=true;
        assertNotEquals(first.revision(),reader.readForSynchronization(shop,key).revision());duplicate=true;assertThrows(java.io.IOException.class,()->reader.readForSynchronization(shop,key));assertEquals(0,writes.get());
    }
}
