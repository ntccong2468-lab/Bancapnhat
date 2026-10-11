package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.features.listingcopy.ListingCopyMappingTest.*;

class OzonListingAdapterTest {
    @TempDir Path root;
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key,marketplace,client_id) VALUES(2,'Ozon','fixture','OZON','22')");}}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void importTaskAndFailureAreRealNotHttpSuccess()throws Exception{
        var writes=new AtomicInteger();var adapter=new OzonListingAdapter(s->(p,b,m)->{if(m){writes.incrementAndGet();return JsonParser.parseString("{\"result\":{\"task_id\":12345}}");}if(p.equals("v1/product/import/info"))return JsonParser.parseString("{\"result\":{\"items\":[{\"offer_id\":\"NEW-M\",\"status\":\"failed\",\"errors\":[{\"code\":\"INVALID\"}]},{\"offer_id\":\"NEW-L\",\"status\":\"failed\",\"errors\":[]}]}}");return fixture("99:88").call(p,b,m);},i->{throw new java.io.IOException();});
        var shop=target(Marketplace.OZON);var p=adapter.preview(shop,source(Marketplace.WILDBERRIES),options(Marketplace.OZON));var r=adapter.submit(shop,p);assertEquals("12345",r.taskId());assertEquals(CopyState.FAILED,adapter.reconcile(shop,r).state());assertEquals(1,writes.get());
    }
    @Test void foreignOwnerCannotReconcile()throws Exception{
        var calls=new AtomicInteger();var adapter=new OzonListingAdapter(s->(p,b,m)->{calls.incrementAndGet();return new JsonObject();},i->{throw new java.io.IOException();});var r=new RemoteSubmission(3,Marketplace.OZON,"foreign","hash","1",List.of("a"),"{}","[]","[]","CARD",Map.of());assertThrows(SecurityException.class,()->adapter.reconcile(target(Marketplace.OZON),r));assertEquals(0,calls.get());
    }
}
