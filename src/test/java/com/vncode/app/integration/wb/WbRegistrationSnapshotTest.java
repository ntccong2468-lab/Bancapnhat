package com.vncode.app.integration.wb;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WbRegistrationSnapshotTest {
    @TempDir Path directory;Shop shop=new Shop(1,"Fixture","synthetic");
    final List<String> seen=new ArrayList<>();
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Fixture','synthetic')");}}
    @AfterEach void clean(){System.clearProperty("vncode.appdata.dir");}
    private WbProductSyncService service(List<WbProductCardsResponse> responses){return new WbProductSyncService(new WbApiClient(){int n;
        @Override public WbProductCardsResponse getProductCards(String key,String locale,String updated,Long nm,int limit){seen.add(updated==null?"FIRST":updated);return responses.get(n++);}
    },new WbProductRepository(),new WbSyncStateRepository(),new WbSyncRunRepository());}
    private WbProductCardsResponse page(String json){return new Gson().fromJson(json,WbProductCardsResponse.class);}
    @Test void completeRegistrationReadIgnoresAnOldIncrementalCursor()throws Exception{
        new WbSyncStateRepository().updateProductsCursor(1,"old",999L,"old");var emitted=new ArrayList<WbProductCard>();
        int count=service(List.of(page("{\"cards\":[{\"nmID\":101,\"vendorCode\":\"A\",\"sizes\":[{\"chrtID\":201}]}],\"cursor\":{\"total\":1,\"updatedAt\":\"now\",\"nmID\":101}}"))).syncFromStart(shop,emitted::addAll);
        assertEquals(List.of("FIRST"),seen);assertEquals(1,count);assertEquals(101L,emitted.getFirst().getNmID());
    }
    @Test void absentCardsAndMissingCursorCannotClaimACompletedSnapshot(){
        for(String json:List.of("{}","{\"cards\":[{\"nmID\":101,\"sizes\":[]}]}"))assertThrows(java.io.IOException.class,()->service(List.of(page(json))).syncFromStart(shop,p->{}));
    }
    @Test void emptySuccessfulArrayCompletesWithoutInventingProducts()throws Exception{assertEquals(0,service(List.of(page("{\"cards\":[],\"cursor\":{\"total\":0}}"))).syncFromStart(shop,p->fail("No page must be emitted")));}
    @Test void repeatedCursorEndsWithAnErrorInsteadOfLoopingForever(){
        var response=page("{\"cards\":[{\"nmID\":101,\"sizes\":[]}],\"cursor\":{\"total\":100,\"updatedAt\":\"same\",\"nmID\":101}}");
        assertEquals("WB_REPEATED_PRODUCT_CURSOR",assertThrows(java.io.IOException.class,()->service(List.of(response,response)).syncFromStart(shop,p->{})).getMessage());assertEquals(2,seen.size());
    }
}
