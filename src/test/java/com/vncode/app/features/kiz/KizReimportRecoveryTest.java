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
    @Test void wbRecoversTwoHundredCodes()throws Exception{sameFileWithTwoHundredUnclassifiedImportsRecoversCategoryWithoutRecreatingCodes(Marketplace.WILDBERRIES);}
    @Test void ozonRecoversTwoHundredCodes()throws Exception{sameFileWithTwoHundredUnclassifiedImportsRecoversCategoryWithoutRecreatingCodes(Marketplace.OZON);}
    void sameFileWithTwoHundredUnclassifiedImportsRecoversCategoryWithoutRecreatingCodes(Marketplace market)throws Exception {
        var shop=shop(market);var file=file(200);var original=warehouse.commit(shop,file);
        var category=shipments.refreshCategories(shop).getFirst();assertEquals(0,category.ready());
        var review=warehouse.review(shop,file,category.id());assertEquals(200,review.unassigned());assertEquals(0,review.fresh());
        var recovered=warehouse.commitToCategory(shop,file,category.id());assertEquals(original.batch(),recovered.batch());assertEquals(0,recovered.inserted());assertEquals(200,recovered.classified());
        assertEquals(200,shipments.refreshCategories(shop).getFirst().ready());
        var repeat=warehouse.commitToCategory(shop,file,category.id());assertEquals(0,repeat.classified());assertEquals(200,repeat.review().sameCategory());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*),count(DISTINCT raw_code) FROM kiz_codes")){assertTrue(r.next());assertEquals(200,r.getInt(1));assertEquals(200,r.getInt(2));}
        var assigned=new KizCategoryAllocator().reservePartial(shop,List.of(new KizCategoryAllocator.Demand("order","101",1)));assertEquals(1,assigned.get("order").size(),"No Znack settings, matching marketplace GTIN or remote connection required");
    }
    @Test void onlyExplicitMoveReclassifiesFreeImportsAndNeverReservedConsumedOrArchivedCodes()throws Exception {
        var shop=shop(Marketplace.WILDBERRIES);var file=file(5);var categories=new KizStockCategoryRepository();var old=categories.create(shop,"Previous");var target=shipments.refreshCategories(shop).stream().filter(c->!c.id().equals(old.id())).findFirst().orElseThrow();
        warehouse.commitToCategory(shop,file,old.id());
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("UPDATE kiz_codes SET status='RESERVED' WHERE id=(SELECT min(id) FROM kiz_codes)");
            s.execute("UPDATE kiz_codes SET status='CONSUMED' WHERE id=(SELECT min(id)+1 FROM kiz_codes)");
            s.execute("UPDATE kiz_codes SET status='ARCHIVED' WHERE id=(SELECT min(id)+2 FROM kiz_codes)");
        }
        var review=warehouse.review(shop,file,target.id());assertEquals(2,review.otherCategory());assertEquals(1,review.reserved());assertEquals(1,review.consumed());assertEquals(1,review.archived());
        assertEquals(0,warehouse.commitToCategory(shop,file,target.id()).moved());
        var result=warehouse.commitToCategory(shop,file,target.id(),true);assertEquals(2,result.moved());
        var rows=shipments.refreshCategories(shop);assertEquals(2,rows.stream().filter(c->c.id().equals(target.id())).findFirst().orElseThrow().ready());
        var previous=rows.stream().filter(c->c.id().equals(old.id())).findFirst().orElseThrow();assertEquals(1,previous.held());assertEquals(1,previous.consumed());
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT count(*) FROM kiz_stock_classifications c JOIN kiz_codes k ON k.id=c.code_id WHERE k.status<>'AVAILABLE' AND c.category_id=?")){p.setString(1,old.id());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(3,r.getInt(1));}}
    }
    @Test void changesBetweenReviewAndCommitCannotResurrectAReservedCode()throws Exception {
        var shop=shop(Marketplace.WILDBERRIES);var file=file(2);warehouse.commit(shop,file);String category=shipments.refreshCategories(shop).getFirst().id();assertEquals(2,warehouse.review(shop,file,category).unassigned());
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE kiz_codes SET status='RESERVED' WHERE id=(SELECT min(id) FROM kiz_codes)");}
        var result=warehouse.commitToCategory(shop,file,category,true);assertEquals(1,result.classified());assertEquals(1,result.review().reserved());assertEquals(1,shipments.refreshCategories(shop).getFirst().ready());
    }
    @Test void anotherShopAndRotatedCredentialsCannotClaimExistingPaidCodes()throws Exception {
        var first=shop(Marketplace.WILDBERRIES);var file=file(2);warehouse.commit(first,file);
        var second=shop(Marketplace.OZON);String target=shipments.refreshCategories(second).getFirst().id();assertEquals(2,warehouse.review(second,file,target).otherStock());
        var result=warehouse.commitToCategory(second,file,target,true);assertEquals(0,result.inserted());assertEquals(0,result.classified());assertEquals(0,result.moved());
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated' WHERE id=1");}
        var rotated=new Shop(1,"Fixture","rotated");String rotatedTarget=shipments.refreshCategories(rotated).getFirst().id();assertEquals(2,warehouse.review(rotated,file,rotatedTarget).otherStock());assertEquals(0,warehouse.commitToCategory(rotated,file,rotatedTarget,true).classified());
    }
    @Test void differentCryptoForTheSameCisIsReportedAndOriginalCodeIsPreserved()throws Exception {
        var shop=shop(Marketplace.WILDBERRIES);var file=file(1);warehouse.commit(shop,file);String target=shipments.refreshCategories(shop).getFirst().id();
        Files.writeString(root.resolve("conflict.txt"),KizWarehouseTest.mark(1).replace("91ABCD","91EFGH"));var conflict=KizImportReader.read(root.resolve("conflict.txt"));assertTrue(conflict.valid());
        assertEquals(1,warehouse.review(shop,conflict,target).conflicts());var result=warehouse.commitToCategory(shop,conflict,target,true);assertEquals(0,result.classified());assertEquals(0,result.inserted());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT raw_code FROM kiz_codes")){assertTrue(r.next());assertEquals(KizWarehouseTest.mark(1),r.getString(1));}
    }
}
