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
    @Test void productViewGroupsWbSizesButVariantSourcesRemainUnchanged()throws Exception {
        card(1,100,"One card","sku-one");card(1,200,"Other card","sku-other");
        try(var c=Database.getConnection();var st=c.createStatement()){
            st.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,101,100,'L')");
            st.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,101,'sku-two')");
        }
        var repo=new ProductRepository();assertEquals(3,repo.loadPage(query("",null)).total());
        var cards=repo.loadProductPage(query("",null));assertEquals(2,cards.total());
        var first=cards.rows().stream().filter(r->r.key().cardId().equals("100")).findFirst().orElseThrow();assertEquals("",first.key().variantId());assertTrue(first.size().contains("M"));assertTrue(first.size().contains("L"));assertTrue(first.barcodes().containsAll(List.of("sku-one","sku-two")));
        assertEquals(2,repo.freezeProductKeys(query("",null)).size());assertTrue(repo.freezeProductKeys(query("",null)).stream().allMatch(k->k.variantId().isBlank()));
        var q=new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,false,null,1);String cursor=repo.loadProductPage(q).nextCursor();assertNotNull(cursor);
        assertEquals(1,repo.loadProductPage(new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,false,cursor,1)).rows().size());
        assertThrows(IllegalArgumentException.class,()->repo.loadPage(new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,false,cursor,1)));
        assertEquals(1,repo.loadProductPage(query("sku-two",null)).total());
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
    @Test void ozonVerifiedSnapshotProjectsAttributesButCredentialRotationHidesIt()throws Exception {
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,archived,synced_at) VALUES(2,'21','O-1','SKU-1','Ozon product',0,'now')");
        }
        var shop=new com.vncode.app.models.Shop(2,"Ozon",Marketplace.OZON,"fixture","fixture");
        var key=new ProductKey(2,Marketplace.OZON,"21","O-1");var repo=new ProductRepository();
        var q=new ProductQuery(2,Marketplace.OZON,"",List.of(),null,false,null,100);
        assertFalse(repo.loadPage(q).rows().getFirst().attributesLoaded());
        assertEquals(1,repo.loadPage(q).issueCounts().get(ProductIssueFilter.ATTRIBUTES_UNLOADED));
        repo.saveVerifiedSnapshot(shop,new ProductSnapshot(key,"live:readback","Ozon product","","Brand","O-1","9:1",List.of(),
            List.of(new ProductAttribute("7","Состав",List.of("Cotton"),null),new ProductAttribute("8","ТН ВЭД",List.of("6109100000"),null),new ProductAttribute("9","Пол",List.of("Мужской"),null),
                new ProductAttribute("17","Состав",List.of("Wrong complex attribute"),null,"8",0)),
            List.of(),null,List.of(),java.time.Instant.now(),Map.of("owner",com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop))));
        var row=repo.loadPage(q).rows().getFirst();assertTrue(row.attributesLoaded());assertEquals("Brand",row.brand());assertEquals("Cotton",row.material());assertEquals("6109100000",row.tnved());assertEquals("Мужской",row.gender());
        assertEquals(0,repo.loadPage(q).issueCounts().get(ProductIssueFilter.INVALID_TNVED));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated' WHERE id=2");}
        row=repo.loadPage(q).rows().getFirst();assertFalse(row.attributesLoaded());assertEquals("",row.brand());assertEquals("",row.material());
    }
    @Test void verifiedWbReadbackUpdatesEverySizeAndSurvivesStaleBackgroundCards()throws Exception {
        card(1,100,"Old title","barcode");
        try(var c=Database.getConnection();var st=c.createStatement()){
            st.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,101,100,'L')");
            st.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,100,204557,'Пол','[\"Мужской\"]')");
        }
        var shop=new com.vncode.app.models.Shop(1,"WB","fixture");var repo=new ProductRepository();
        var snapshot=new ProductSnapshot(new ProductKey(1,Marketplace.WILDBERRIES,"100","100"),"live:readback","New title","","New brand","own","9",List.of(),
            List.of(new ProductAttribute("44","Состав",List.of("Cotton"),null),new ProductAttribute("45","ТНВЭД",List.of("6103220000"),null)),List.of(),null,List.of(),java.time.Instant.now(),Map.of("owner",com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop)));
        repo.saveVerifiedSnapshot(shop,snapshot);
        var page=repo.loadPage(query("",null));assertEquals(2,page.total());
        for(var row:page.rows()){assertEquals("New brand",row.brand());assertEquals("New title",row.title());assertEquals("Cotton",row.material());assertEquals("6103220000",row.tnved());assertEquals("",row.gender(),"Verified absent field must not fall back to stale values");assertEquals("",row.color(),"Gender is not a color");}
        assertEquals(2,repo.loadPage(query("New title",null)).total());
        try(var c=Database.getConnection();var st=c.createStatement()){st.execute("UPDATE shops SET api_key='rotated' WHERE id=1");}
        assertEquals("Old title",repo.loadPage(query("",null)).rows().getFirst().title());
    }
    @Test void issueFilterAndFrozenSelectionAgreeAndCannotReuseAnotherCursor()throws Exception {
        card(1,1,"Missing",null);card(1,2,"Other",null);
        var q=new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,false,null,1,ProductIssueFilter.MISSING_BRAND);
        var repo=new ProductRepository();var page=repo.loadPage(q);assertEquals(2,page.total());assertEquals(2,repo.freezeKeys(q).size());
        assertThrows(IllegalArgumentException.class,()->repo.loadPage(new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,false,page.nextCursor(),1,ProductIssueFilter.INVALID_TNVED)));
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
