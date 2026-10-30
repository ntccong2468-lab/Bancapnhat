package com.vncode.app.features.kizmapping;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedKizMappingsTest {
    @TempDir Path path;
    static final String GTIN="04645588781154";
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",path.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'WB','WILDBERRIES',NULL,'fixture'),(2,'Ozon','OZON','client','fixture2')");
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,synced_at) VALUES(1,100,'fixture')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,nm_id,chrt_id) VALUES(1,100,101),(1,100,102)");
            s.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,101,'old-alias'),(1,101,'"+GTIN+"'),(1,102,'sibling')");
            s.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,article,synced_at) VALUES(2,'100','offer','201','shared','fixture'),(2,'101','sibling','202','shared','fixture')");
            s.execute("INSERT INTO ozon_product_barcodes(shop_id,product_id,barcode) VALUES(2,'100','"+GTIN+"')");
        }
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void proposedGtinDoesNotAuthorizeAssignmentAndVerifiedWbVariantDoesNotChangeSibling(){
        var repo=new GtinSyncRepository();var key=new GtinSyncModels.ProductKey(1,Marketplace.WILDBERRIES,"100","101");
        repo.saveCatalog(1,Marketplace.WILDBERRIES,List.of(new GtinSyncModels.RegisteredGtin(GTIN,"","","",true,true,"National Catalog@fixture",Instant.now())));
        repo.saveMapping(key,GTIN);assertTrue(read(1,Marketplace.WILDBERRIES).isEmpty());repo.recordVerifiedMapping(key,GTIN);
        assertEquals(Map.of("old-alias",GTIN,GTIN,GTIN),read(1,Marketplace.WILDBERRIES));
        assertEquals(GTIN,new com.vncode.app.integration.znack.registration.ZnackCardRegistrationRepository().activeGtinsByBarcode(1).get("old-alias"));
    }
    @Test void ozonChoiceUsesProductAndOfferRatherThanBroadArticleAndRotationInvalidatesProof()throws Exception{
        var repo=new GtinSyncRepository();var key=new GtinSyncModels.ProductKey(2,Marketplace.OZON,"100","offer");
        repo.saveCatalog(2,Marketplace.OZON,List.of(new GtinSyncModels.RegisteredGtin(GTIN,"","","",true,true,"National Catalog@fixture",Instant.now())));repo.recordVerifiedMapping(key,GTIN);
        assertEquals(Map.of("201",GTIN),read(2,Marketplace.OZON));assertEquals(GTIN,new com.vncode.app.integration.ozon.OzonProductGtinMappingRepository().findResolvedBySku(2).get("201"));
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated' WHERE id=2");}
        assertTrue(read(2,Marketplace.OZON).isEmpty());
    }
    @Test void ozonHistoricalProofIsNotAnActiveMappingWhenCurrentBarcodeIsAbsent()throws Exception{
        var repo=new GtinSyncRepository();var key=new GtinSyncModels.ProductKey(2,Marketplace.OZON,"100","offer");
        repo.saveCatalog(2,Marketplace.OZON,List.of(new GtinSyncModels.RegisteredGtin(GTIN,"","","",true,true,"National Catalog@fixture",Instant.now())));
        repo.recordVerifiedMapping(key,GTIN);assertEquals(Map.of("201",GTIN),read(2,Marketplace.OZON));
        try(var c=Database.getConnection();var p=c.prepareStatement("DELETE FROM ozon_product_barcodes WHERE shop_id=2 AND product_id='100'")){p.executeUpdate();}
        assertTrue(read(2,Marketplace.OZON).isEmpty(),"A newer absent barcode cannot be called a verified live mapping");
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO ozon_product_barcodes(shop_id,product_id,barcode) VALUES(2,'100',?)")){
            p.setString(1,GTIN.substring(1));p.executeUpdate();
        }
        assertEquals(Map.of("201",GTIN),read(2,Marketplace.OZON),"GTIN-13 and its padded GTIN-14 are equivalent");
    }
    Map<String,String> read(int id,Marketplace market){try(var c=Database.getConnection()){return VerifiedKizMappings.read(c,id,market);}catch(Exception e){throw new RuntimeException(e);}}
}
