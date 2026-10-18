package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KizReimportRecoveryTest {
    @TempDir Path root;
    private final KizWarehouseRepository warehouse=new KizWarehouseRepository();
    private final ShipmentKizRepository shipments=new ShipmentKizRepository();
    @BeforeEach void init() {System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    private Shop shop(Marketplace market)throws Exception {
        var shop=GtinWriteTestFixture.shop(market);
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("DELETE FROM znack_settings");
            if(market==Marketplace.WILDBERRIES)s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,subject_id,subject_name,title,synced_at) VALUES(1,101,9,'Костюмы спортивные','Synthetic','fixture')");
        }
        if(market==Marketplace.OZON)new OzonCatalogRepository().upsertPage(shop.getId(),List.of(new OzonProductDto("101","offer","sku","Synthetic","",false,"",List.of()).withCardAttributes("a","","","Костюмы спортивные","").withCategoryIdentity("10","20")),"");
        return shop;
    }
    private KizImportReader.Preview file(int count)throws Exception {
        var file=root.resolve("seller.txt");var codes=new ArrayList<String>();for(int i=1;i<=count;i++)codes.add(KizWarehouseTest.mark(i));Files.write(file,codes);return KizImportReader.read(file);
    }

    @Test void everyUploadCreatesNewOccurrencesIncludingAnIdenticalFileForBothMarketplaces()throws Exception {
        for(var market:Marketplace.values()) {
            var shop=shop(market);var preview=file(200);var category=shipments.refreshCategories(shop).getFirst();
            for(int upload=1;upload<=3;upload++){
                var result=warehouse.commitToCategory(shop,preview,category.id());assertEquals(200,result.inserted());assertEquals(0,result.duplicates());
                assertEquals(upload*200,shipments.stocks(shop).getFirst().ready());
            }
        }
    }
    @Test void repeatedRowsAndPreviouslyConsumedOrReservedCodesAreAcceptedWithoutChangingOldOrders()throws Exception {
        var shop=shop(Marketplace.WILDBERRIES);var category=shipments.refreshCategories(shop).getFirst();var original=file(2);
        warehouse.commitToCategory(shop,original,category.id());
        var allocator=new KizCategoryAllocator();var inventory=new com.vncode.app.integration.znack.ZnackGtinInventoryService();
        var consumed=allocator.reservePartial(shop,List.of(new KizCategoryAllocator.Demand("old","101",1))).get("old");assertEquals(1,inventory.consume(1,consumed));
        var reserved=allocator.reservePartial(shop,List.of(new KizCategoryAllocator.Demand("held","101",1))).get("held");
        Files.write(root.resolve("seller.txt"),List.of(KizWarehouseTest.mark(1),KizWarehouseTest.mark(1),KizWarehouseTest.mark(2),"broken"));
        var preview=KizImportReader.read(root.resolve("seller.txt"));assertEquals(3,preview.marks().size());assertEquals(1,preview.damaged());assertEquals(0,preview.duplicates());
        assertEquals(3,warehouse.commitToCategory(shop,preview,category.id()).inserted());assertEquals(3,shipments.stocks(shop).getFirst().ready());
        var assigned=allocator.reservePartial(shop,List.of(new KizCategoryAllocator.Demand("new","101",3))).get("new");
        assertEquals(3,assigned.size());assertEquals(3,assigned.stream().map(com.vncode.app.models.Kiz::getId).distinct().count());assertEquals(2,assigned.stream().map(com.vncode.app.models.Kiz::getCode).distinct().count());
        assertEquals(3,inventory.consume(1,assigned));assertEquals(1,inventory.release(1,reserved));
        Database.initDatabase();var remaining=shipments.stocks(shop).getFirst();assertEquals(1,remaining.ready());assertEquals(4,remaining.consumed());
        try(var c=Database.getConnection();var st=c.createStatement();var r=st.executeQuery("PRAGMA foreign_key_check")){assertFalse(r.next());}
    }
    @Test void eachSellerMayImportTheSameCodeButStaleShopCredentialsStillCannotWrite()throws Exception {
        var first=shop(Marketplace.WILDBERRIES);var preview=file(2);warehouse.commitToCategory(first,preview,shipments.refreshCategories(first).getFirst().id());
        var second=shop(Marketplace.OZON);String target=shipments.refreshCategories(second).getFirst().id();assertEquals(2,warehouse.commitToCategory(second,preview,target).inserted());
        try(var c=Database.getConnection();var st=c.createStatement()){st.execute("UPDATE shops SET api_key='rotated' WHERE id=1");}
        assertThrows(SecurityException.class,()->warehouse.commitToCategory(first,preview,target));
    }
    @Test void validCryptoVariantsAreNotComparedWithPreviouslyUploadedContent()throws Exception {
        var shop=shop(Marketplace.WILDBERRIES);String target=shipments.refreshCategories(shop).getFirst().id();warehouse.commitToCategory(shop,file(1),target);
        Files.writeString(root.resolve("changed.txt"),KizWarehouseTest.mark(1).replace("91ABCD","91EFGH"));var preview=KizImportReader.read(root.resolve("changed.txt"));
        assertEquals(1,warehouse.commitToCategory(shop,preview,target).inserted());assertEquals(2,shipments.stocks(shop).getFirst().ready());
    }
    @Test void deletingCategoriesPersistsAcrossRefreshAndKeepsExistingReservationProofForBothMarkets()throws Exception {
        for(var market:Marketplace.values()){
            var shop=shop(market);String category=shipments.refreshCategories(shop).getFirst().id();warehouse.commitToCategory(shop,file(2),category);
            var held=new KizCategoryAllocator().reservePartial(shop,List.of(new KizCategoryAllocator.Demand("held","101",1))).get("held");assertEquals(1,held.size());
            var result=new KizStockCategoryRepository().delete(shop,category);assertEquals(1,result.archived());assertEquals(1,result.held());
            assertTrue(shipments.refreshCategories(shop).isEmpty());assertTrue(shipments.stocks(shop).isEmpty());assertTrue(shipments.assignmentCategories(shop).isEmpty());assertTrue(new KizStockCategoryRepository().bindings(shop,List.of("101")).isEmpty());
            try(var c=Database.getConnection()){var mark=held.getFirst();assertTrue(KizCategoryAllocator.hasProof(c,shop.getId(),com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop),mark.getId(),mark.getReservationToken(),"held","101"));}
            assertEquals(1,new com.vncode.app.integration.znack.ZnackGtinInventoryService().consume(shop.getId(),held));
            assertTrue(new KizCategoryAllocator().reservePartial(shop,List.of(new KizCategoryAllocator.Demand("new","101",1))).isEmpty());
            assertThrows(SecurityException.class,()->warehouse.commitToCategory(shop,file(1),category));
        }
        Database.initDatabase();
    }
    @Test void releasedReservationsFromADeletedCategoryCannotReappearAsAvailableStock()throws Exception {
        for(var market:Marketplace.values()){
            var shop=shop(market);String category=shipments.refreshCategories(shop).getFirst().id();
            warehouse.commitToCategory(shop,file(3),category);
            var held=new KizCategoryAllocator().reservePartial(shop,List.of(new KizCategoryAllocator.Demand("held","101",2))).get("held");
            new KizStockCategoryRepository().delete(shop,category);
            var inventory=new com.vncode.app.integration.znack.ZnackGtinInventoryService();
            assertEquals(1,inventory.release(shop.getId(),List.of(held.getFirst())));
            assertEquals(1,inventory.releaseRecoverableReservations());
            try(var c=Database.getConnection();var p=c.prepareStatement("SELECT status,reservation_token FROM kiz_codes WHERE shop_id=?")){
                p.setInt(1,shop.getId());try(var r=p.executeQuery()){int count=0;while(r.next()){
                    count++;assertEquals("ARCHIVED",r.getString(1));assertNull(r.getString(2));
                }assertEquals(3,count);}
            }
            assertTrue(shipments.refreshCategories(shop).isEmpty());
        }
    }
    @Test void startupRepairsPreviouslyRevivedHiddenStockWithoutTouchingConsumedHistory()throws Exception{
        var shop=shop(Marketplace.WILDBERRIES);String category=shipments.refreshCategories(shop).getFirst().id();warehouse.commitToCategory(shop,file(2),category);
        var inventory=new com.vncode.app.integration.znack.ZnackGtinInventoryService();
        var consumed=new KizCategoryAllocator().reservePartial(shop,List.of(new KizCategoryAllocator.Demand("old-order","101",1))).get("old-order");inventory.consume(shop.getId(),consumed);
        new KizStockCategoryRepository().delete(shop,category);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE kiz_codes SET status='AVAILABLE' WHERE status='ARCHIVED'");}
        Database.initDatabase();Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT status,count(*) FROM kiz_codes GROUP BY status")){
            var states=new HashMap<String,Integer>();while(r.next())states.put(r.getString(1),r.getInt(2));assertEquals(Map.of("ARCHIVED",1,"CONSUMED",1),states);
        }
    }
}
