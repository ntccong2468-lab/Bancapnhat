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
    @Test void explicitRefreshEnrichesEveryCardRegardlessOfVisibleFilterAndPageLimit()throws Exception{
        var reads=new AtomicInteger();var service=new ProductRefreshService(s->{try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,title,synced_at) VALUES(1,?,'Title','now')")){for(int i=1;i<=123;i++){p.setInt(1,i);p.addBatch();}p.executeBatch();}catch(Exception e){throw new java.io.IOException(e);}return 123;},(s,k)->{reads.incrementAndGet();return snapshot(k);},new ProductRepository());
        var report=service.refresh(shop,new ProductQuery(1,Marketplace.WILDBERRIES,"not present",List.of("999"),DocumentState.MISSING,true,"ignored display cursor",20));assertEquals(123,report.snapshots());assertEquals(0,report.failed());assertEquals(123,reads.get());
        var page=new ProductRepository().loadPage(new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),DocumentState.EXPIRED,false,null,100));assertEquals(123,page.total());assertEquals("Full details",new ProductRepository().localSnapshot(page.rows().getFirst().key()).orElseThrow().description());
    }
    @Test void staleOwnerIsRejectedBeforeCatalogOrSnapshotRequests(){var calls=new AtomicInteger();var service=new ProductRefreshService(s->{calls.incrementAndGet();return 0;},(s,k)->{calls.incrementAndGet();return snapshot(k);},new ProductRepository());assertThrows(SecurityException.class,()->service.refresh(new Shop(1,"Owner","rotated"),new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,false,null,100)));assertEquals(0,calls.get());}
    @Test void manyVariantsDoNotLimitEnrichmentToTwentyCardsAndAllRequestedFieldsSurvive()throws Exception {
        try(var c=Database.getConnection();var cards=c.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,title,synced_at) VALUES(1,?,'Original','now')");var sizes=c.prepareStatement("INSERT INTO wb_product_sizes(shop_id,nm_id,chrt_id,tech_size) VALUES(1,?,?,?)")){
            for(int i=1;i<=125;i++){cards.setInt(1,i);cards.addBatch();for(int size=0;size<5;size++){sizes.setInt(1,i);sizes.setInt(2,i*10+size);sizes.setString(3,"S"+size);sizes.addBatch();}}cards.executeBatch();sizes.executeBatch();
        }
        var progress=new ArrayList<ProductRefreshService.Progress>();var repository=new ProductRepository();
        var service=new ProductRefreshService(s->125,(s,k)->{
            var base=snapshot(k);return new ProductSnapshot(k,base.revision(),base.title(),base.description(),"Brand",base.vendorArticle(),base.categoryId(),List.of(),List.of(
                new ProductAttribute("1","ТНВЭД",List.of("6112110000"),null),new ProductAttribute("2","Состав",List.of("хлопок 80%, полиэстер 20%"),null),new ProductAttribute("3","Пол",List.of("Мужской"),null)),
                List.of(new ProductVariant(k.variantId(),"article","Brown","52","",false)),null,base.documents(),base.checkedAt(),base.metadata());
        },repository);
        var report=service.refresh(shop,new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,false,null,100),progress::add);
        assertEquals(125,report.snapshots());assertEquals(126,progress.size());assertEquals(125,progress.getLast().completed());
        var page=repository.loadProductPage(new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,false,null,1000));assertEquals(125,page.total());
        for(var row:page.rows()){assertEquals("Brand",row.brand());assertEquals("6112110000",row.tnved());assertEquals("Мужской",row.gender());assertTrue(row.material().contains("80%"));assertEquals(DocumentState.EXPIRED,row.documentState());}
    }
}
