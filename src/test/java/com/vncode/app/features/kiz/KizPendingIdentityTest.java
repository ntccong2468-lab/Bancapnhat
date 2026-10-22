package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.features.fbo.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonProductGtinMappingRepository;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationRepository;
import com.vncode.app.integration.znack.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class KizPendingIdentityTest {
    @TempDir Path root;
    @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void acceptedOwnedPendingIdentityAuthorizesExactKizPurchaseWithoutPublishingIt(Marketplace market)throws Exception{
        var shop=PendingKizFixture.create(market);String nativeId=market==Marketplace.WILDBERRIES?"101":"9001";
        assertDoesNotThrow(()->KizPurchaseMappingProof.require(shop,List.of(new KizPurchaseMappingProof.Product(nativeId,"S-M",PendingKizFixture.GTIN)),Set.of(PendingKizFixture.GTIN)));
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(shop,List.of(new KizPurchaseMappingProof.Product(nativeId,"S-L",PendingKizFixture.GTIN)),Set.of(PendingKizFixture.GTIN)));
        var ref=new FboProductSku(101,"","","","","","","","S-M","",true,"S-M","","",nativeId);
        assertTrue(new FboVerifiedGtinRepository().resolve(shop.getId(),market,List.of(ref)).isEmpty());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM znack_products")){assertEquals(0,r.getInt(1));}
    }
    @Test void wbPendingIdentityResolvesTheExistingBarcodeBeforeMarketplacePublication()throws Exception{
        PendingKizFixture.create(Marketplace.WILDBERRIES);
        assertEquals(PendingKizFixture.GTIN,new ZnackCardRegistrationRepository().activeGtinsByBarcode(1).get("S-M"));
        assertFalse(new ZnackCardRegistrationRepository().activeGtinsByBarcode(1).containsKey("S-L"));
    }
    @Test void ozonPendingIdentityResolvesTheExactNativeSkuBeforeMarketplacePublication()throws Exception{
        PendingKizFixture.create(Marketplace.OZON);
        assertEquals(PendingKizFixture.GTIN,new OzonProductGtinMappingRepository().findResolvedBySku(2).get("S-M"));
        assertFalse(new OzonProductGtinMappingRepository().findResolvedBySku(2).containsKey("S-L"));
    }
    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void reviewedPendingMetadataReachesSigningConfigurationWithoutAnActiveCatalogProjection(Marketplace market)throws Exception{
        var shop=PendingKizFixture.create(market);
        PendingKizFixture.sql("UPDATE znack_settings SET oms_connection='fixture-connection',auto_introduction=1");
        var repository=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName()));
        var coordinator=new ZnackPurchaseCoordinator(repository,null,null,null);
        var failure=assertThrows(IllegalStateException.class,()->coordinator.validatePrerequisites(repository.getSettings(),PendingKizFixture.GTIN,1));
        assertEquals("omsId is required before buying KIZ.",failure.getMessage());
    }
    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void rejectedChangedOrUnacceptedPendingIdentityCannotAuthorizePurchase(Marketplace market)throws Exception{
        var shop=PendingKizFixture.create(market);String table=market==Marketplace.WILDBERRIES?"znack_card_registrations":"ozon_card_registrations";
        var product=new KizPurchaseMappingProof.Product(market==Marketplace.WILDBERRIES?"101":"9001","S-M",PendingKizFixture.GTIN);
        for(String status:List.of("ERROR","READY_TO_SUBMIT","FEED_SUBMITTING","FEED_REVIEW_REQUIRED")){
            PendingKizFixture.sql("UPDATE "+table+" SET status='"+status+"'");
            assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(shop,List.of(product),Set.of(PendingKizFixture.GTIN)));
        }
        PendingKizFixture.sql("UPDATE "+table+" SET status='PROCESSING',feed_id='',good_id=NULL");
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(shop,List.of(product),Set.of(PendingKizFixture.GTIN)));
        PendingKizFixture.sql("UPDATE "+table+" SET feed_id='accepted-feed',payload_json='{}'");
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(shop,List.of(product),Set.of(PendingKizFixture.GTIN)));
    }
    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void changingCurrentSizeOrSigningOwnerBlocksAnOtherwiseAcceptedRegistration(Marketplace market)throws Exception{
        var shop=PendingKizFixture.create(market);var product=new KizPurchaseMappingProof.Product(market==Marketplace.WILDBERRIES?"101":"9001","S-M",PendingKizFixture.GTIN);
        PendingKizFixture.sql(market==Marketplace.WILDBERRIES?"UPDATE wb_product_sizes SET tech_size='XL' WHERE chrt_id=11":"UPDATE ozon_products SET size='XL' WHERE product_id='9001'");
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(shop,List.of(product),Set.of(PendingKizFixture.GTIN)));
        PendingKizFixture.sql(market==Marketplace.WILDBERRIES?"UPDATE wb_product_sizes SET tech_size='M' WHERE chrt_id=11":"UPDATE ozon_products SET size='M' WHERE product_id='9001'");
        com.vncode.app.features.gtinsync.GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(shop,List.of(product),Set.of(PendingKizFixture.GTIN)));
    }
    @Test void reviewedCisTypeReachesTheSyntheticSigningBoundaryWithoutInventingAnActiveProduct()throws Exception{
        var shop=PendingKizFixture.create(Marketplace.WILDBERRIES);
        PendingKizFixture.sql("UPDATE znack_settings SET oms_connection='fixture-connection'");
        var repository=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName()));
        var captured=new java.util.concurrent.atomic.AtomicReference<com.google.gson.JsonObject>();
        var stop=new com.vncode.app.integration.znack.signature.CryptoProException(com.vncode.app.integration.znack.signature.CryptoProErrorCode.CRYPTOPRO_MISSING,"fixture-stop-before-signing");
        var service=new ZnackKizOrderService(null,null,(bytes,context)->{captured.set(com.google.gson.JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());throw stop;},repository);
        assertSame(stop,assertThrows(com.vncode.app.integration.znack.signature.CryptoProException.class,()->service.buy(repository.getSettings(),PendingKizFixture.GTIN,3)));
        var item=captured.get().getAsJsonArray("products").get(0).getAsJsonObject();
        assertEquals(PendingKizFixture.GTIN,item.get("gtin").getAsString());assertEquals(3,item.get("quantity").getAsInt());assertEquals("UNIT",item.get("cisType").getAsString());
    }
    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void sandboxRegistrationCannotAuthorizeProductionPurchaseEvenWithMatchingScope(Marketplace market)throws Exception{
        var shop=PendingKizFixture.create(market);
        com.vncode.app.features.gtinsync.GtinWriteTestFixture.saveSettings(shop,"A".repeat(40),"https://markirovka.sandbox.crptech.ru/api/v3/true-api");
        var repository=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName()));
        var scope=com.vncode.app.integration.znack.registration.ZnackRegistrationScope.fromSettings(repository.getSettings());
        if(market==Marketplace.WILDBERRIES){
            PendingKizFixture.sql("DELETE FROM znack_card_registration_scopes");
            new ZnackCardRegistrationRepository().bindScope(shop,11,scope);
        }
        else try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_card_registrations SET scope_json=?")){
            p.setString(1,new com.google.gson.Gson().toJson(scope));p.executeUpdate();
        }
        var product=new KizPurchaseMappingProof.Product(market==Marketplace.WILDBERRIES?"101":"9001","S-M",PendingKizFixture.GTIN);
        assertThrows(KizAssignmentException.class,()->KizPurchaseMappingProof.require(shop,List.of(product),Set.of(PendingKizFixture.GTIN)));
        assertTrue(new KizRegistrationIdentityRepository().product(shop.getId(),PendingKizFixture.GTIN).isEmpty());
        assertThrows(IllegalArgumentException.class,()->repository.enqueuePipeline(PendingKizFixture.GTIN,1,UUID.randomUUID().toString()));
    }
    @Test void pendingQueuePersistsOnlyAnInactiveForeignKeyAnchorAndRechecksItsOwner()throws Exception{
        var shop=PendingKizFixture.create(Marketplace.WILDBERRIES);var repository=new ZnackRepository(new ZnackModels.ShopContext(1,shop.getName()));
        String key=UUID.randomUUID().toString();long pipeline=repository.enqueuePipeline(PendingKizFixture.GTIN,3,key);
        assertEquals(pipeline,repository.findPipelineByRequestKey(key).orElseThrow().id());
        assertTrue(repository.findActiveProduct(PendingKizFixture.GTIN).isEmpty());assertTrue(repository.findProducts().isEmpty());
        assertTrue(repository.findProduct(PendingKizFixture.GTIN).isEmpty(),"Pending anchor must not bypass a fresh registration check");
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT card_status,card_detailed_status,good_mark_flag,good_turn_flag FROM znack_products")){
            assertEquals("pending_registration",r.getString(1));assertEquals("KIZ_REVIEWED_REGISTRATION",r.getString(2));assertNull(r.getObject(3));assertNull(r.getObject(4));
        }
        repository.deleteUnpublishedProducts(List.of(PendingKizFixture.GTIN));assertTrue(repository.findPipeline(pipeline).isPresent());
        com.vncode.app.features.gtinsync.GtinWriteTestFixture.saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        assertThrows(IllegalArgumentException.class,()->repository.enqueuePipeline(PendingKizFixture.GTIN,3,UUID.randomUUID().toString()));
        assertThrows(IllegalArgumentException.class,()->repository.createDraft(PendingKizFixture.GTIN,3));
    }    @Test void realPublicationActivatesThePendingAnchorWithoutLosingItsPurchase()throws Exception{
        var shop=PendingKizFixture.create(Marketplace.WILDBERRIES);var repository=new ZnackRepository(new ZnackModels.ShopContext(1,shop.getName()));
        long pipeline=repository.enqueuePipeline(PendingKizFixture.GTIN,1,UUID.randomUUID().toString());
        var product=new ZnackModels.Product(PendingKizFixture.GTIN,"Published","6109100000",null,null,null,null,true,true,"published","","",java.time.Instant.now(),"UNIT",List.of());
        repository.upsertProducts(List.of(product));
        assertTrue(repository.findActiveProduct(PendingKizFixture.GTIN).isPresent(),"Real publication must replace the inactive pending anchor");
        assertTrue(repository.findPipeline(pipeline).isPresent());
    }

}
