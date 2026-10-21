package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackGtinInventoryService;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WbShipmentKizRepositoryTest {
    @TempDir Path root;Shop shop;final WbShipmentKizRepository repository=new WbShipmentKizRepository();
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,subject_id,subject_name,title,synced_at) VALUES(1,101,10,'Брюки','Trousers','fixture'),(1,102,10,'Брюки','Trousers 2','fixture'),(1,103,20,'Рубашки','Shirt','fixture'),(1,104,30,'Брюки','Different native category','fixture')");
            s.execute("DELETE FROM znack_settings");
        }
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void nativeCategoriesIncludeEmptyStockAndKeepStableIdentityAcrossRenamesAndRefreshes()throws Exception {
        var first=repository.refreshCategories(shop);assertEquals(3,first.size());assertTrue(first.stream().allMatch(row->row.ready()==0));
        assertTrue(repository.assignmentCategories(shop).isEmpty(),"Empty discovered categories cannot make unmarked products require KIZ");
        assertEquals(2,first.stream().filter(row->row.name().endsWith("WB 10")).findFirst().orElseThrow().products());
        String original=first.stream().filter(row->row.name().endsWith("WB 10")).findFirst().orElseThrow().id();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE wb_product_cards SET subject_name='Переименовано' WHERE subject_id=10");}
        var second=repository.refreshCategories(shop);assertEquals(3,second.size());assertEquals(original,second.stream().filter(row->row.name().endsWith("WB 10")).findFirst().orElseThrow().id());
        assertEquals(4,new KizStockCategoryRepository().bindings(shop,List.of("101","102","103","104")).size());
        assertEquals(second,repository.refreshCategories(shop));
    }
    @Test void importsAreImmediatelyUsableAndRepeatedFilesCreateIndependentCategoryStock()throws Exception {
        var rows=repository.refreshCategories(shop);String trousers=rows.stream().filter(row->row.name().endsWith("WB 10")).findFirst().orElseThrow().id();String shirt=rows.stream().filter(row->row.name().endsWith("WB 20")).findFirst().orElseThrow().id();
        var file=root.resolve("seller.txt");Files.write(file,List.of(KizWarehouseTest.mark(1),KizWarehouseTest.mark(2)));var preview=KizImportReader.read(file);
        var imported=new KizWarehouseService().importFile(shop,preview,true,trousers);assertEquals(2,imported.inserted());
        assertEquals(Set.of(trousers),repository.assignmentCategories(shop));
        var repeat=new KizWarehouseService().importFile(shop,preview,true,shirt);assertEquals(2,repeat.inserted());assertEquals(0,repeat.duplicates());
        assertEquals(2,repository.refreshCategories(shop).stream().filter(row->row.id().equals(trousers)).findFirst().orElseThrow().ready());
        assertEquals(2,repository.refreshCategories(shop).stream().filter(row->row.id().equals(shirt)).findFirst().orElseThrow().ready());
        var reserved=new KizCategoryAllocator().reservePartial(shop,List.of(new KizCategoryAllocator.Demand("a","101",1))).get("a");
        var held=repository.refreshCategories(shop).stream().filter(row->row.id().equals(trousers)).findFirst().orElseThrow();assertEquals(1,held.ready());assertEquals(1,held.held());
        new ZnackGtinInventoryService().consume(shop.getId(),reserved);var used=repository.refreshCategories(shop).stream().filter(row->row.id().equals(trousers)).findFirst().orElseThrow();assertEquals(1,used.consumed());assertEquals(0,used.held());
    }
    @Test void foreignCategoryImportIsAtomicAndSourcesAreScopedToShipmentAndOwner()throws Exception {
        assertEquals(KizSourceSelection.SELLER_ONLY,repository.selection(shop,"S-1"));
        var both=new KizSourceSelection(true,true);repository.saveSelection(shop,"S-1",both);assertEquals(both,repository.selection(shop,"S-1"));assertEquals(KizSourceSelection.SELLER_ONLY,repository.selection(shop,"S-2"));
        var file=root.resolve("seller.txt");Files.writeString(file,KizWarehouseTest.mark(1));assertThrows(SecurityException.class,()->new KizWarehouseService().importFile(shop,KizImportReader.read(file),true,"foreign-category"));
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM kiz_codes")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated-fixture' WHERE id=1");}
        assertThrows(SecurityException.class,()->repository.selection(shop,"S-1"));
        var rotated=new Shop(shop.getId(),shop.getName(),"rotated-fixture");assertEquals(KizSourceSelection.SELLER_ONLY,repository.selection(rotated,"S-1"));assertTrue(repository.refreshCategories(rotated).stream().allMatch(row->row.ready()==0));
    }
}
