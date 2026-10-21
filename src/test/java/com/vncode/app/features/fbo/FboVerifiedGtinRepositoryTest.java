package com.vncode.app.features.fbo;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.kiz.KizAssignmentException;
import com.vncode.app.features.kiz.KizPurchaseMappingProof;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class FboVerifiedGtinRepositoryTest {
    @TempDir Path root;
    private String priorAppData;
    @BeforeEach void init(){priorAppData=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();}
    @AfterEach void restore(){if(priorAppData==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",priorAppData);}

    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void canonicalAliasesAndActualCachedVariantAuthorizeTheSelectedProduct(Marketplace market)throws Exception{
        Shop shop=fixture(market);var reference=reference(market);
        for(int digits:new int[]{8,12,13,14}){
            String barcode=GtinWriteTestFixture.GTIN.substring(14-digits);
            if(market==Marketplace.WILDBERRIES)sql("UPDATE wb_product_size_skus SET sku='"+barcode+"' WHERE sku='"+GtinWriteTestFixture.GTIN+"'");
            else sql("UPDATE ozon_product_barcodes SET barcode='"+barcode+"' WHERE product_id='9001'");
            assertEquals(GtinWriteTestFixture.GTIN,new FboVerifiedGtinRepository().resolve(shop.getId(),market,List.of(reference)).get(reference));
            assertDoesNotThrow(()->purchaseProof(shop,reference));
            if(market==Marketplace.WILDBERRIES)sql("UPDATE wb_product_size_skus SET sku='"+GtinWriteTestFixture.GTIN+"' WHERE sku='"+barcode+"'");
        }
    }

    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void staleForeignOrUnidentifiedCatalogFlagsCannotAuthorizeAnyPurchase(Marketplace market)throws Exception{
        Shop shop=fixture(market);var reference=reference(market);String pristine=catalogJson(shop);
        List<Consumer<JsonObject>> mutations=List.of(
                data->data.addProperty("source","National Catalog@another-account"),
                data->data.addProperty("owner","another-marketplace-account"),
                data->data.addProperty("syncedAt",Instant.now().minusSeconds(86401).toString()),
                data->data.addProperty("syncedAt",Instant.now().plusSeconds(3600).toString()),
                data->data.getAsJsonObject("details").getAsJsonObject("fields").remove("@catalog_id"),
                data->data.getAsJsonObject("details").getAsJsonObject("fields").add("@catalog_id",JsonParser.parseString("[\"0\"]")),
                data->data.getAsJsonObject("details").getAsJsonObject("fields").remove("@catalog_status"),
                data->data.getAsJsonObject("details").getAsJsonObject("fields").add("@catalog_status",JsonParser.parseString("[\"draft\"]")));
        for(var mutation:mutations){var data=JsonParser.parseString(pristine).getAsJsonObject();mutation.accept(data);saveJson(shop,data);
            assertTrue(new FboVerifiedGtinRepository().resolve(shop.getId(),market,List.of(reference)).isEmpty());
            assertThrows(KizAssignmentException.class,()->purchaseProof(shop,reference));
            assertEquals(0,pipelineCount());
        }
    }

    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void copiedBarcodeCannotOverrideWrongArticleColorOrSize(Marketplace market)throws Exception{
        Shop shop=fixture(market);var reference=reference(market);String pristine=catalogJson(shop);
        for(String field:List.of("article","color","size")){
            var data=JsonParser.parseString(pristine).getAsJsonObject();data.addProperty(field,"wrong-"+field);saveJson(shop,data);
            assertTrue(new FboVerifiedGtinRepository().resolve(shop.getId(),market,List.of(reference)).isEmpty(),field);
            assertThrows(KizAssignmentException.class,()->purchaseProof(shop,reference));assertEquals(0,pipelineCount());
        }
    }

    @Test void missingOrAmbiguousCachedPhysicalFactsAreNotReplacedByCallerClaims()throws Exception{
        Shop shop=fixture(Marketplace.WILDBERRIES);var reference=reference(Marketplace.WILDBERRIES);
        sql("UPDATE wb_product_characteristics SET value_json='[\"Black\",\"White\"]'");
        assertThrows(KizAssignmentException.class,()->purchaseProof(shop,reference));
        sql("UPDATE wb_product_characteristics SET value_json='[\"Black\"]'");
        sql("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,101,204557,'Пол','[\"Мужской\"]')");
        assertDoesNotThrow(()->purchaseProof(shop,reference));
        sql("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,101,333,'Цвет товара','[\"White\"]')");
        assertThrows(KizAssignmentException.class,()->purchaseProof(shop,reference));
        sql("DELETE FROM wb_product_characteristics WHERE characteristic_id=333");
        sql("UPDATE wb_product_cards SET vendor_code=''");
        assertTrue(new FboVerifiedGtinRepository().resolve(shop.getId(),shop.getMarketplace(),List.of(reference)).isEmpty());
        assertEquals(0,pipelineCount());
    }

    @Test void establishedOzonOfferAliasUsesActualColorAndExplicitSizeOnly()throws Exception{
        Shop shop=fixture(Marketplace.OZON);var reference=reference(Marketplace.OZON);
        sql("UPDATE ozon_products SET offer_id='152DASH-52',article='152DASH-52',color='Коричневый',size='52' WHERE product_id='9001'");
        var data=JsonParser.parseString(catalogJson(shop)).getAsJsonObject();data.addProperty("article","152 nau dashoa");data.addProperty("color","Коричневый");data.addProperty("size","52");saveJson(shop,data);
        assertDoesNotThrow(()->purchaseProof(shop,reference));
        sql("UPDATE ozon_products SET size='54' WHERE product_id='9001'");
        assertThrows(KizAssignmentException.class,()->purchaseProof(shop,reference));
        sql("UPDATE ozon_products SET size='52',color='Черный' WHERE product_id='9001'");
        assertThrows(KizAssignmentException.class,()->purchaseProof(shop,reference));assertEquals(0,pipelineCount());
    }

    @Test void aMatchingSandboxObservationCannotAuthorizeMarketplaceKizPurchases()throws Exception{
        Shop shop=fixture(Marketplace.WILDBERRIES);var reference=reference(Marketplace.WILDBERRIES);
        GtinWriteTestFixture.saveSettings(shop,GtinWriteTestFixture.CERTIFICATE,"https://markirovka.sandbox.crptech.ru/api/v3/true-api");
        var data=JsonParser.parseString(catalogJson(shop)).getAsJsonObject();data.addProperty("source",RegisteredGtinProof.source(shop));saveJson(shop,data);
        assertThrows(KizAssignmentException.class,()->purchaseProof(shop,reference));assertEquals(0,pipelineCount());
    }

    private Shop fixture(Marketplace market)throws Exception{
        Shop shop=GtinWriteTestFixture.shop(market);
        if(market==Marketplace.WILDBERRIES){
            sql("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,101,'Fixture','Synthetic','fixture')");
            sql("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size,wb_size) VALUES(1,11,101,'M','46')");
            sql("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,101,14177449,'Цвет','[\"Black\"]')");
            sql("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,11,'S-M'),(1,11,'"+GtinWriteTestFixture.GTIN+"')");
        }else new OzonCatalogRepository().upsertPage(shop.getId(),List.of(new OzonProductDto("9001","Fixture-M","S-M","Synthetic","","Fixture","Black","M","Fashion","",false,"",List.of(GtinWriteTestFixture.GTIN))),"");
        var details=new CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),List.of());
        new GtinSyncRepository().saveCatalog(shop.getId(),market,List.of(new GtinSyncModels.RegisteredGtin(GtinWriteTestFixture.GTIN,"Fixture","Black","M",true,true,RegisteredGtinProof.source(shop),Instant.now(),details)));
        return shop;
    }
    private static FboProductSku reference(Marketplace market){return new FboProductSku(101,"untrusted","","","","White","XXL","","S-M","",true,"S-M","","",market==Marketplace.OZON?"9001":"101");}
    private static void purchaseProof(Shop shop,FboProductSku reference){KizPurchaseMappingProof.require(shop,List.of(new KizPurchaseMappingProof.Product(reference.productId(),reference.catalogSku(),GtinWriteTestFixture.GTIN)),Set.of(GtinWriteTestFixture.GTIN));}
    private static String catalogJson(Shop shop)throws Exception{try(var c=Database.getConnection();var p=c.prepareStatement("SELECT data_json FROM gtin_sync_catalog WHERE shop_id=?")){p.setInt(1,shop.getId());try(var r=p.executeQuery()){return r.getString(1);}}}
    private static void saveJson(Shop shop,JsonObject data)throws Exception{try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE gtin_sync_catalog SET data_json=? WHERE shop_id=?")){p.setString(1,data.toString());p.setInt(2,shop.getId());p.executeUpdate();}}
    private static int pipelineCount()throws Exception{try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM znack_purchase_pipelines")){return r.getInt(1);}}
    private static void sql(String value)throws Exception{try(var c=Database.getConnection();var s=c.createStatement()){s.execute(value);}}
}
