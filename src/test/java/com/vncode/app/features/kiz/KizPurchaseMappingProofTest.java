package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KizPurchaseMappingProofTest {
    @TempDir Path root;
    @BeforeEach void init(){System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void wbPurchaseRequiresTheExactProductVariantNotAnotherSizeOrCategory()throws Exception{
        var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);String gtin=GtinWriteTestFixture.GTIN;
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,synced_at) VALUES(1,101,'Fixture','fixture')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,11,101,'M'),(1,12,101,'L')");
            s.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,101,14177449,'Цвет','[\"Black\"]')");
            s.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,11,'M-SKU'),(1,11,'"+gtin+"'),(1,12,'L-SKU')");
        }
        catalog(shop);
        assertDoesNotThrow(()->KizPurchaseMappingProof.require(shop,List.of(new KizPurchaseMappingProof.Product("101","M-SKU",gtin)),Set.of(gtin)));
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(shop,List.of(new KizPurchaseMappingProof.Product("101","L-SKU",gtin)),Set.of(gtin)));
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(shop,List.of(new KizPurchaseMappingProof.Product("999","M-SKU",gtin)),Set.of(gtin)));
    }
    @Test void ozonPurchaseRequiresNativeProductAndSkuBarcodeProof()throws Exception{
        var shop=GtinWriteTestFixture.shop(Marketplace.OZON);String gtin=GtinWriteTestFixture.GTIN;
        new com.vncode.app.integration.ozon.OzonCatalogRepository().upsertPage(2,List.of(new com.vncode.app.integration.ozon.OzonProductDto("9001","offer-M","SKU-M","Synthetic","","Fixture","Black","M","Fashion","",false,"",List.of(gtin)),new com.vncode.app.integration.ozon.OzonProductDto("9002","offer-L","SKU-L","Synthetic","","Fixture","Black","L","Fashion","",false,"",List.of())),"");
        catalog(shop);
        assertDoesNotThrow(()->KizPurchaseMappingProof.require(shop,List.of(new KizPurchaseMappingProof.Product("9001","SKU-M",gtin)),Set.of(gtin)));
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(shop,List.of(new KizPurchaseMappingProof.Product("9002","SKU-L",gtin)),Set.of(gtin)));
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(shop,List.of(new KizPurchaseMappingProof.Product("9002","SKU-M",gtin)),Set.of(gtin)));
    }
    private static void catalog(com.vncode.app.models.Shop shop){
        var details=new com.vncode.app.features.catalogsync.CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),List.of());
        new com.vncode.app.features.gtinsync.GtinSyncRepository().saveCatalog(shop.getId(),shop.getMarketplace(),List.of(new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(GtinWriteTestFixture.GTIN,"Fixture","Black","M",true,true,com.vncode.app.features.gtinsync.RegisteredGtinProof.source(shop),java.time.Instant.now(),details)));
    }
}
