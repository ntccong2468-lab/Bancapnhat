package com.vncode.app.integration.listing;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GtinSnapshotStoreTest {
    static JsonObject value(String text){var v=new JsonObject();v.addProperty("description",text);return v;}
    @Test void tenThousandSelectedCardsSurviveEvictionAndKeepLatestReadback(){
        try(var store=new GtinSnapshotStore(1024)){
            for(int i=0;i<10_000;i++)store.put("card:"+i,value("original-"+i));
            assertTrue(store.retainedBytes()<=1024);
            for(int i=0;i<10_000;i++)assertEquals("original-"+i,store.get("card:"+i).get("description").getAsString());
            store.put("card:0",value("fresh-get-with-gtin"));
            for(int i=1;i<100;i++)store.get("card:"+i);
            assertEquals("fresh-get-with-gtin",store.get("card:0").get("description").getAsString());
            store.remove("card:1");assertNull(store.get("card:1"));store.clear();assertNull(store.get("card:0"));assertEquals(0,store.retainedBytes());
        }
    }
    @Test void oneLargeDescriptionCanStayOnDiskWithoutExceedingMemoryLimit(){
        try(var store=new GtinSnapshotStore(50)){
            var card=value("Large card ".repeat(100));store.put("large",card);assertEquals(card,store.get("large"));assertEquals(0,store.retainedBytes());
        }
    }
}
