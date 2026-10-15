package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackGtinInventoryService;
import com.vncode.app.models.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class KizCategoryAllocatorTest {
    @TempDir Path root;Shop shop;KizWarehouseRepository warehouse=new KizWarehouseRepository();KizStockCategoryRepository categories=new KizStockCategoryRepository();
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,title,synced_at) VALUES(1,101,'Synthetic trousers','fixture'),(1,102,'Synthetic shirt','fixture')");}
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    ProductKey product(int id){return new ProductKey(shop.getId(),shop.getMarketplace(),Integer.toString(id),"");}
    String stock(int from,int count,String category,boolean ready)throws Exception{
        var file=root.resolve("synthetic-"+from+".txt");var lines=new ArrayList<String>();for(int n=from;n<from+count;n++)lines.add(KizWarehouseTest.mark(n));Files.write(file,lines);
        var scope=RegisteredGtinProof.current(shop);var batch=warehouse.commit(shop,scope,KizImportReader.read(file));categories.classifyBatch(shop,category,batch.batch());
        if(ready){var pending=warehouse.pending(shop,scope.source(),1000,0);var verdicts=new HashMap<String,KizWarehouseRepository.Verdict>();pending.forEach(p->verdicts.put(p.cis(),new KizWarehouseRepository.Verdict(true,"KIZ_READY")));warehouse.verified(shop,scope.source(),pending,verdicts);}
        return batch.batch();
    }
    @Test void importedCategoryWorksWithoutProductGtinMappingAndPreservesTheCodesActualGtin()throws Exception{
        var category=categories.create(shop,"Quần");categories.assignProducts(shop,category.id(),List.of(product(101)));stock(1,2,category.id(),true);
        var result=new KizCategoryAllocator().reservePartial(shop,List.of(new KizCategoryAllocator.Demand("order-product","101",2)));
        assertEquals(2,result.get("order-product").size());assertEquals(KizWarehouseTest.GTIN,KizMark.parse(result.get("order-product").getFirst().getCode()).gtin());assertNotEquals(GtinWriteTestFixture.GTIN,KizWarehouseTest.GTIN);
        var codes=result.get("order-product");assertEquals(2,new ZnackGtinInventoryService().release(shop.getId(),codes));assertEquals(2,categories.list(shop).getFirst().ready());
    }
    @Test void categoriesDoNotCrossAndPartialShortageDoesNotUseUnverifiedCodes()throws Exception{
        var trousers=categories.create(shop,"Quần");var shirts=categories.create(shop,"Áo");categories.assignProducts(shop,trousers.id(),List.of(product(101)));categories.assignProducts(shop,shirts.id(),List.of(product(102)));
        stock(1,1,trousers.id(),true);stock(2,2,shirts.id(),false);
        var result=new KizCategoryAllocator().reservePartial(shop,List.of(new KizCategoryAllocator.Demand("a","101",3),new KizCategoryAllocator.Demand("b","102",2)));
        assertEquals(1,result.get("a").size());assertFalse(result.containsKey("b"));assertEquals(KizWarehouseTest.mark(1),result.get("a").getFirst().getCode());
    }
    @Test void exactGtinFallbackCannotTakeStockClassifiedForAnotherProduct()throws Exception{
        var category=categories.create(shop,"Quần");categories.assignProducts(shop,category.id(),List.of(product(101)));stock(1,2,category.id(),true);
        var inventory=new ZnackGtinInventoryService();assertEquals(0,inventory.availableCount(shop.getId(),KizWarehouseTest.GTIN));
        assertEquals(0,inventory.availableCounts(shop.getId(),List.of(KizWarehouseTest.GTIN)).get(KizWarehouseTest.GTIN));
        assertThrows(IllegalStateException.class,()->inventory.reserveAvailable(shop.getId(),KizWarehouseTest.GTIN,1));
        assertEquals(2,categories.list(shop).getFirst().ready());
    }
    @Test void reservedCodesCannotBeMovedToAnotherCategoryAndForeignProductKeysAreRejected()throws Exception{
        var one=categories.create(shop,"Quần");var two=categories.create(shop,"Áo");categories.assignProducts(shop,one.id(),List.of(product(101)));String batch=stock(1,1,one.id(),true);
        var reserved=new KizCategoryAllocator().reservePartial(shop,List.of(new KizCategoryAllocator.Demand("a","101",1)));
        assertEquals(0,categories.classifyBatch(shop,two.id(),batch));
        assertThrows(SecurityException.class,()->categories.assignProducts(shop,one.id(),List.of(new ProductKey(999,Marketplace.WILDBERRIES,"101",""))));
        try(var c=Database.getConnection()){
            var mark=reserved.get("a").getFirst();assertTrue(KizCategoryAllocator.hasProof(c,1,com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop),mark.getId(),mark.getReservationToken(),"a","101"));
            assertFalse(KizCategoryAllocator.hasProof(c,1,com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop),mark.getId(),mark.getReservationToken(),"a","102"));
            try(var statement=c.createStatement()){statement.execute("UPDATE znack_settings SET owner_inn='7711111111' WHERE shop_id=1");}
            assertFalse(KizCategoryAllocator.hasProof(c,1,com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop),mark.getId(),mark.getReservationToken(),"a","101"),"Changing the company must not authorize the previous company's imported reservation");
        }
    }
    @Test void concurrentThousandItemRequestsNeverClaimTheSameCode()throws Exception{
        var group=categories.create(shop,"Quần");categories.assignProducts(shop,group.id(),List.of(product(101)));stock(1,1000,group.id(),true);
        try(var workers=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);Callable<List<Kiz>> reserve=()->{gate.await();return new KizCategoryAllocator().reservePartial(shop,List.of(new KizCategoryAllocator.Demand("batch","101",1000))).getOrDefault("batch",List.of());};
            var a=workers.submit(reserve);var b=workers.submit(reserve);gate.countDown();var all=new ArrayList<Kiz>(a.get(30,TimeUnit.SECONDS));all.addAll(b.get(30,TimeUnit.SECONDS));
            assertEquals(1000,all.size());assertEquals(1000,all.stream().map(Kiz::getId).distinct().count());
        }
    }
}
