package com.vncode.app.features.products;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.WbProductSnapshotReader;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.listing.ListingRejectedException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Local transport and disposable SQLite: verifies the real list/detail reader path, not seller API. */
class ProductRefreshBatchRegressionTest {
    @TempDir Path root;
    private final Shop shop=new Shop(1,"Fixture","fixture");
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Fixture','WILDBERRIES','fixture')");}
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}

    @Test void complete327ProductDetailsUseFourListRequestsInsteadOf327Searches()throws Exception{
        insert(327);var requests=new AtomicInteger();var searches=new AtomicInteger();
        var reader=new WbProductSnapshotReader(s->(path,body,mutation)->{
            assertFalse(mutation);
            if(path.startsWith("api/v2/list/goods/"))return new JsonObject();
            requests.incrementAndGet();
            var settings=body.getAsJsonObject().getAsJsonObject("settings");
            var filter=settings.getAsJsonObject("filter");
            var rows=new JsonArray();int last=0;
            if(filter.has("textSearch")){searches.incrementAndGet();last=filter.get("textSearch").getAsInt();rows.add(card(last));}
            else{
                var cursor=settings.getAsJsonObject("cursor");int start=cursor.has("nmID")?cursor.get("nmID").getAsInt():0;
                for(int i=start+1;i<=Math.min(start+100,327);i++){rows.add(card(i));last=i;}
            }
            var response=new JsonObject();response.add("cards",rows);var cursor=new JsonObject();cursor.addProperty("total",rows.size());cursor.addProperty("nmID",last);cursor.addProperty("updatedAt","2026-01-01T00:00:00Z");response.add("cursor",cursor);return response;
        });
        var service=new ProductRefreshService(s->327,reader,new ProductRepository());
        var report=service.refresh(shop,query());
        assertEquals(327,report.snapshots());assertEquals(0,report.failed());
        assertEquals(0,searches.get(),"Explicit catalog refresh must reuse the fresh full listing rather than search every card again");
        assertEquals(4,requests.get());
        assertEquals(327,new ProductRepository().loadProductPage(query()).total());
    }

    @Test void interruptionIsNotCountedAsAnOrdinaryUnreadProduct()throws Exception{
        insert(2);var requests=new AtomicInteger();
        var service=new ProductRefreshService(s->2,(s,key)->{requests.incrementAndGet();throw new java.io.InterruptedIOException("Synthetic cancelled read");},new ProductRepository());
        assertThrows(java.io.InterruptedIOException.class,()->service.refresh(shop,query()));
        assertEquals(1,requests.get(),"Cancellation must stop the refresh rather than continue through every product");
    }

    @Test void aMalformedCardDoesNotDiscardOtherFreshCardsAndRetainsItsSpecificReason()throws Exception{
        insert(3);var requests=new AtomicInteger();
        var reader=new WbProductSnapshotReader(s->(path,body,mutation)->{
            assertFalse(mutation);requests.incrementAndGet();var response=new JsonObject();var rows=new JsonArray();
            for(int i=1;i<=3;i++){var raw=card(i);if(i==2)raw.remove("dimensions");rows.add(raw);}
            response.add("cards",rows);var cursor=new JsonObject();cursor.addProperty("total",3);response.add("cursor",cursor);return response;
        });
        var report=new ProductRefreshService(s->3,reader,new ProductRepository()).refresh(shop,query());
        assertEquals(1,requests.get());assertEquals(2,report.snapshots());assertEquals(1,report.failed());
        var failure=report.failures().getFirst();assertEquals("2",failure.key().cardId());
        assertEquals("WB_CARD_INCOMPLETE",failure.code());assertEquals(List.of("dimensions"),failure.missingFields());
        assertEquals(3,new ProductRepository().loadProductPage(query()).total(),"The listing survives a detail-only failure");
    }

    @Test void aSocketTimeoutIsReportedWithoutLeakingTheMessageOrDiscardingOtherProducts()throws Exception{
        insert(2);var reads=new AtomicInteger();
        var service=new ProductRefreshService(s->2,(s,key)->{
            if(reads.incrementAndGet()==1)throw new java.net.SocketTimeoutException("token=PRIVATE_SYNTHETIC");
            return snapshot(key);
        },new ProductRepository());
        var report=service.refresh(shop,query());assertEquals(1,report.snapshots());assertEquals(1,report.failed());
        assertEquals("PRODUCT_DETAILS_TIMEOUT",report.failures().getFirst().code());
        assertFalse(report.failures().toString().contains("PRIVATE_SYNTHETIC"));
    }

    @Test void accountPermissionFailureStopsWithoutMisreportingEveryProductAsMissing()throws Exception{
        insert(3);var reads=new AtomicInteger();
        var service=new ProductRefreshService(s->3,(s,key)->{reads.incrementAndGet();throw new ListingRejectedException(Marketplace.WILDBERRIES,403,"Synthetic authorization failure");},new ProductRepository());
        assertThrows(ListingRejectedException.class,()->service.refresh(shop,query()));assertEquals(1,reads.get());
        assertEquals(3,new ProductRepository().loadProductPage(query()).total());
    }

    private ProductSnapshot snapshot(ProductKey key){return new ProductSnapshot(key,"live:fixture","Fixture","","Fixture","fixture","1",List.of(),List.of(),List.of(),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));}

    private ProductQuery query(){return new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,false,null,1000);}
    private void insert(int count)throws Exception{
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,title,synced_at) VALUES(1,?,'Fixture','now')")){
            for(int i=1;i<=count;i++){p.setInt(1,i);p.addBatch();}p.executeBatch();
        }
    }
    private static JsonObject card(int id){
        return JsonParser.parseString("{\"nmID\":"+id+",\"subjectID\":1,\"title\":\"Fixture\",\"vendorCode\":\"fixture\",\"brand\":\"Fixture\",\"characteristics\":[],\"dimensions\":{},\"sizes\":[{\"chrtID\":"+(id*10)+",\"techSize\":\"L\",\"skus\":[]}],\"documents\":{\"items\":[]}}").getAsJsonObject();
    }
}
