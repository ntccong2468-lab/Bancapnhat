package com.vncode.app.features.products;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductRepositoryTest {
    @TempDir Path root;
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", root.toString());
        Database.initDatabase();
        try (var c=Database.getConnection(); var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,api_key,marketplace,client_id) VALUES(1,'WB','fixture','WILDBERRIES',NULL),(2,'Ozon','fixture','OZON','fixture'),(3,'Other','fixture','WILDBERRIES',NULL)");
        }
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    void card(int shop,int id,String title,String barcode) throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,title,vendor_code,subject_name,synced_at) VALUES(?,?,?,'ART','Clothing','now')")) {
            p.setInt(1,shop);p.setInt(2,id);p.setString(3,title);p.executeUpdate();
            if(barcode!=null) {
                try(var s=c.createStatement()) {
                    s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES("+shop+","+id+","+id+",'M')");
                }
                try(var b=c.prepareStatement("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(?,?,?)")) {
                    b.setInt(1,shop);b.setInt(2,id);b.setString(3,barcode);b.executeUpdate();
                }
            }
        }
    }
    ProductQuery query(String text,String cursor){return new ProductQuery(1,Marketplace.WILDBERRIES,text,List.of(),null,false,cursor,100);}
    @Test void searchAndPagingAreShopScoped() throws Exception {
        for(int i=1;i<=251;i++)card(1,i,"Product "+String.format("%04d",i),null);
        card(3,999,"Foreign product",null);
        var repo=new ProductRepository();var first=repo.loadPage(query("",null));
        assertEquals(100,first.rows().size());assertEquals(251,first.total());
        var keys=new HashSet<ProductKey>();String cursor=null;
        do {var page=repo.loadPage(query("",cursor));for(var row:page.rows()){assertEquals(1,row.key().shopId());assertTrue(keys.add(row.key()));}cursor=page.nextCursor();} while(cursor!=null);
        assertEquals(251,keys.size());assertEquals(251,repo.freezeKeys(query("",null)).size());
        assertThrows(SecurityException.class,()->repo.loadPage(new ProductQuery(2,Marketplace.WILDBERRIES,"",List.of(),null,false,null,100)));
    }
    @Test void unicodeSearchAndLiteralWildcards() throws Exception {
        card(1,1,"БРЮКИ thể thao 100%_VN",null);card(1,2,"Other 100abcdVN",null);
        var repo=new ProductRepository();
        assertEquals(1,repo.loadPage(query("брюки",null)).total());
        assertEquals(1,repo.loadPage(query("THỂ THAO",null)).total());
        assertEquals(1,repo.loadPage(query("%_",null)).total());
    }
    @Test void cardWithoutVariantsRemainsVisible() throws Exception {
        card(1,1,"Draft card",null);
        var row=new ProductRepository().loadPage(query("",null)).rows().getFirst();
        assertEquals("1",row.key().cardId());assertTrue(row.key().variantId().isEmpty());
        assertTrue(row.barcodes().isEmpty());assertEquals(DocumentState.MISSING,row.documentState());
    }
    @Test void gtinPreservesLeadingZeroAndDoesNotTreatBarcodeAsProof() throws Exception {
        card(1,1,"Item","04610689132457");
        var row=new ProductRepository().loadPage(query("04610689132457",null)).rows().getFirst();
        assertEquals(List.of("04610689132457"),row.barcodes());assertEquals("",row.verifiedGtin());
    }
    @Test void ozonRowsRemainOwnedByOzonAndHaveTrueCount() throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,archived,synced_at) VALUES(2,'21','O-1','SKU-1','Ozon product',0,'now')");
        }
        var page=new ProductRepository().loadPage(new ProductQuery(2,Marketplace.OZON,"",List.of(),null,false,null,100));
        assertEquals(1,page.total());assertEquals(Marketplace.OZON,page.rows().getFirst().key().marketplace());
        assertThrows(SecurityException.class,()->new ProductRepository().localSnapshot(new ProductKey(1,Marketplace.OZON,"21","SKU-1")));
    }
    @Test void documentsRequireVerifiedSource() {
        assertThrows(IllegalArgumentException.class,()->new ProductDocument("id","declaration","number",null,null,"owner","scope",null,Marketplace.WILDBERRIES,DocumentState.VALID,"",null));
        var uploaded=new ProductDocument("id","declaration","number",null,null,"owner","scope",null,Marketplace.WILDBERRIES,DocumentState.UNVERIFIED,"",null);
        assertEquals(DocumentState.UNVERIFIED,uploaded.state());
    }
    @Test void wrongAssignedBarcodeIsStillMissingVerifiedGtin() throws Exception {
        card(1,1,"Wrong association","OTHER-BARCODE");
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO gtin_sync_catalog VALUES(1,'WILDBERRIES','04610689132457',?)")) {
            p.setString(1,"{\"gtin\":\"04610689132457\",\"tradeUnit\":true,\"published\":true}");p.executeUpdate();
            try(var s=c.createStatement()){s.execute("INSERT INTO gtin_sync_mappings VALUES(1,'WILDBERRIES','1','1','04610689132457','now')");}
        }
        var query=new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,true,null,100);
        var page=new ProductRepository().loadPage(query);
        assertEquals(1,page.total());assertEquals("",page.rows().getFirst().verifiedGtin());
    }
    @Test void indexedLargeCatalogKeepsBoundedFirstPage() throws Exception {
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,title,synced_at) VALUES(1,?,?,'now')")) {
            c.setAutoCommit(false);
            for(int i=1;i<=50000;i++){p.setInt(1,i);p.setString(2,"Product "+i);p.addBatch();}p.executeBatch();c.commit();
            try(var stmt=c.createStatement();var r=stmt.executeQuery("EXPLAIN QUERY PLAN SELECT nm_id FROM wb_product_cards WHERE shop_id=1 ORDER BY title,nm_id LIMIT 100")) {
                boolean indexed=false;while(r.next())indexed|=r.getString(4).contains("SEARCH")&&r.getString(4).contains("shop_id");assertTrue(indexed);
            }
        }
        var page=new ProductRepository().loadPage(query("",null));assertEquals(50000,page.total());assertEquals(100,page.rows().size());
    }
    @Test void migration4To5PreservesRows() throws Exception {
        card(1,1,"Existing",null);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("PRAGMA user_version=4");}
        Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){assertTrue(r.next());assertEquals(5,r.getInt(1));}
        assertEquals(1,new ProductRepository().loadPage(query("",null)).total());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(r.next());}
    }
}
