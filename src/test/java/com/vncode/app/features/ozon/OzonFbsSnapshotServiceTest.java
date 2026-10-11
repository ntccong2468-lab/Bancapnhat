package com.vncode.app.features.ozon;

import com.vncode.app.config.Database;
import com.vncode.app.integration.ozon.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonFbsSnapshotServiceTest {
    @TempDir Path root;OzonFbsSnapshotService service;
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Ozon','OZON','22','fixture'),(2,'Other','OZON','33','fixture')");}
        var products=new ArrayList<OzonProductDto>();var postings=new ArrayList<OzonPostingDto>();for(int i=1;i<=1001;i++){String id=Integer.toString(i),sku="sku-"+i;products.add(new OzonProductDto(id,"offer-"+i,sku,"Item "+i,"","Article "+i,i%2==0?"Black":"White",i%2==0?"M":"L","","",false,"",List.of("barcode-"+i)));postings.add(new OzonPostingDto("posting-"+i,id,id,"awaiting_packaging","","","2026-10-11T00:00:00Z","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of(new OzonPostingItemDto(0,id,sku,"offer-"+i,"Item "+i,1,"RUB","100"))));}
        new OzonCatalogRepository().upsertPage(1,products,"");new OzonPostingRepository().upsertPage(1,postings);service=new OzonFbsSnapshotService();
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void moreThan500PostingsAreReachable(){var p=service.load(new OzonFbsQuery(1,"awaiting_packaging","","article",600,100));assertEquals(1001,p.total());assertEquals(100,p.postings().size());assertEquals("posting-601",p.postings().getFirst().postingNumber());}
    @Test void sortingPrecedesPaging(){var a=service.load(new OzonFbsQuery(1,"awaiting_packaging","","product,article",0,100));var b=service.load(new OzonFbsQuery(1,"awaiting_packaging","","product,article",100,100));assertEquals("posting-1",a.postings().getFirst().postingNumber());assertEquals("posting-100",a.postings().getLast().postingNumber());assertEquals("posting-101",b.postings().getFirst().postingNumber());}
    @Test void onlyPageSkusAreLoaded(){var p=service.load(new OzonFbsQuery(1,"awaiting_packaging","","article",0,100));assertEquals(100,p.productsBySku().size());assertEquals(List.of("barcode-1"),p.productsBySku().get("sku-1").barcodes());assertFalse(p.productsBySku().containsKey("sku-1001"));assertEquals(0,service.load(new OzonFbsQuery(2,"awaiting_packaging","","",0,100)).total());}
    @Test void verifiedGtinRequiresPublishedRegistryAndCurrentBarcode()throws Exception {try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO gtin_sync_catalog VALUES(1,'OZON','04645588781154','{\"published\":true,\"tradeUnit\":true}')");s.execute("INSERT INTO gtin_sync_mappings VALUES(1,'OZON','1','offer-1','04645588781154','now')");s.execute("UPDATE ozon_product_barcodes SET barcode='04645588781154' WHERE shop_id=1 AND product_id='1'");}var q=new OzonFbsQuery(1,"awaiting_packaging","sku-1","",0,100);assertEquals("04645588781154",service.load(q).verifiedGtinsBySku().get("sku-1"));try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE ozon_product_barcodes SET barcode='00000000000000' WHERE shop_id=1 AND product_id='1'");}assertFalse(service.load(q).verifiedGtinsBySku().containsKey("sku-1"));}
    @Test void searchAndCountUseSameLiteralFilter(){assertEquals(1,service.load(new OzonFbsQuery(1,"awaiting_packaging","sku-1001","",0,100)).total());assertEquals(0,service.load(new OzonFbsQuery(1,"awaiting_packaging","%","",0,100)).total());assertThrows(IllegalArgumentException.class,()->new OzonFbsQuery(1,"awaiting_packaging","","DROP TABLE",0,100));}
    @Test void missingCatalogProductIdStillSortsByOwnedSku()throws Exception {try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE ozon_posting_items SET product_id='' WHERE shop_id=1");s.execute("UPDATE ozon_products SET article='000 First' WHERE shop_id=1 AND product_id='1001'");}var p=service.load(new OzonFbsQuery(1,"awaiting_packaging","","article",0,100));assertEquals("posting-1001",p.postings().getFirst().postingNumber());assertEquals("posting-1000",service.load(new OzonFbsQuery(1,"awaiting_packaging","","article",1000,100)).postings().getFirst().postingNumber());}
}
