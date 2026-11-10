package com.vncode.app.integration.wb;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Uses only the released1.6.7 public API, so the same test can demonstrate the original defect. */
class WbProductSnapshotPaginationTest {
    @TempDir Path directory;
    @AfterEach void clean(){System.clearProperty("vncode.appdata.dir");}
    @Test void emptyExactSearchIsRecoveredFromTheFreshUnfilteredActiveList()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var connection=Database.getConnection();var statement=connection.createStatement()){
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'WB','WILDBERRIES',NULL,'fixture')");
        }
        var shop=new Shop(1,"WB",Marketplace.WILDBERRIES,null,"fixture");var calls=new AtomicInteger();
        var reader=new WbProductSnapshotReader(owner->(path,body,mutation)->{
            assertFalse(mutation);assertEquals("content/v2/get/cards/list",path);assertTrue(calls.incrementAndGet()<=2);
            var filter=body.getAsJsonObject().getAsJsonObject("settings").getAsJsonObject("filter");
            if(filter.has("textSearch")){assertEquals("123",filter.get("textSearch").getAsString());return JsonParser.parseString("{\"cards\":[],\"cursor\":{\"total\":0}}");}
            return JsonParser.parseString("{\"cards\":[{\"nmID\":123,\"subjectID\":10,\"title\":\"Recovered\",\"vendorCode\":\"Article\",\"dimensions\":{},\"characteristics\":[],\"sizes\":[{\"chrtID\":101,\"techSize\":\"M\",\"skus\":[\"2040000000001\"]}]}],\"cursor\":{\"total\":1,\"updatedAt\":\"2026-01-02T00:00:00Z\",\"nmID\":123}}");
        });
        var key=new ProductKey(1,Marketplace.WILDBERRIES,"123","101");var snapshot=reader.readForSynchronization(shop,key);
        assertEquals(key,snapshot.key());assertEquals("Recovered",snapshot.title());assertEquals(2,calls.get());
    }
    @Test void exactNmIdOnSecondCursorPageIsReadInsteadOfReportedUnavailable()throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var connection=Database.getConnection();var statement=connection.createStatement()){
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'WB','WILDBERRIES',NULL,'fixture')");
        }
        var shop=new Shop(1,"WB",Marketplace.WILDBERRIES,null,"fixture");var calls=new AtomicInteger();
        var reader=new WbProductSnapshotReader(owner->(path,body,mutation)->{
            assertFalse(mutation);assertEquals("content/v2/get/cards/list",path);
            int call=calls.incrementAndGet();assertTrue(call<=2,"Do not scan again after finding the exact identity");
            if(call==1)return JsonParser.parseString("{\"cards\":[{\"nmID\":999}],\"cursor\":{\"total\":1,\"updatedAt\":\"2026-01-01T00:00:00Z\",\"nmID\":999}}");
            assertEquals(999,body.getAsJsonObject().getAsJsonObject("settings").getAsJsonObject("cursor").get("nmID").getAsInt());
            return JsonParser.parseString("{\"cards\":[{\"nmID\":123,\"subjectID\":10,\"title\":\"Full\",\"vendorCode\":\"Article\",\"dimensions\":{},\"characteristics\":[],\"sizes\":[{\"chrtID\":101,\"techSize\":\"M\",\"skus\":[\"2040000000001\"]}]}],\"cursor\":{\"total\":1,\"updatedAt\":\"2026-01-02T00:00:00Z\",\"nmID\":123}}");
        });
        var key=new ProductKey(1,Marketplace.WILDBERRIES,"123","101");var snapshot=reader.readForSynchronization(shop,key);
        assertEquals(key,snapshot.key());assertEquals("Article",snapshot.vendorArticle());assertEquals("M",snapshot.variants().getFirst().size());assertEquals(2,calls.get());
    }
}
