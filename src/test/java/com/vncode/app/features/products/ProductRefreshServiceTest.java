package com.vncode.app.features.products;
import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductRefreshServiceTest {
    @TempDir Path root;Shop shop=new Shop(1,"Owner","fixture");
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Owner','WILDBERRIES','fixture')");}}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    ProductSnapshot snapshot(ProductKey key){var d=new ProductDocument("doc","2","DOC",LocalDate.of(2020,1,1),LocalDate.of(2021,1,1),"","",null,key.marketplace(),DocumentState.EXPIRED,"https://content-api.wildberries.ru/content/v2/get/cards/list",Instant.now());return new ProductSnapshot(key,"live:"+key.cardId(),"Title","Full details","VN","article","9",List.of(),List.of(),List.of(),null,List.of(d),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));}
    @Test void explicitRefreshWritesDocumentsForAtMostOnePageWithoutFixtureCacheSeeding()throws Exception{
        var reads=new AtomicInteger();var service=new ProductRefreshService(s->{try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,title,synced_at) VALUES(1,?,'Title','now')")){for(int i=1;i<=123;i++){p.setInt(1,i);p.addBatch();}p.executeBatch();}catch(Exception e){throw new java.io.IOException(e);}return 123;},(s,k)->{reads.incrementAndGet();return snapshot(k);},new ProductRepository());
        var report=service.refresh(shop,new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,false,null,100));assertEquals(100,report.snapshots());assertEquals(0,report.failed());assertEquals(100,reads.get());
        var page=new ProductRepository().loadPage(new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),DocumentState.EXPIRED,false,null,100));assertEquals(100,page.total());assertEquals("Full details",new ProductRepository().localSnapshot(page.rows().getFirst().key()).orElseThrow().description());
    }
    @Test void staleOwnerIsRejectedBeforeCatalogOrSnapshotRequests(){var calls=new AtomicInteger();var service=new ProductRefreshService(s->{calls.incrementAndGet();return 0;},(s,k)->{calls.incrementAndGet();return snapshot(k);},new ProductRepository());assertThrows(SecurityException.class,()->service.refresh(new Shop(1,"Owner","rotated"),new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,false,null,100)));assertEquals(0,calls.get());}
}
