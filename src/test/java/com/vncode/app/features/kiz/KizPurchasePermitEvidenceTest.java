package com.vncode.app.features.kiz;

import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.sync.ZnackCatalogSyncService;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class KizPurchasePermitEvidenceTest {
    @TempDir Path root;
    @BeforeEach void init(){System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}

    static Stream<Arguments> invalidPermits(){
        return Stream.of(Marketplace.WILDBERRIES,Marketplace.OZON).flatMap(market->Stream.of(
                Arguments.of(market,"expired","GTIN_DOCUMENT_EXPIRED"),
                Arguments.of(market,"revoked","GTIN_DOCUMENT_INACTIVE"),
                Arguments.of(market,"future","GTIN_DOCUMENT_NOT_YET_VALID")));
    }
    @ParameterizedTest @MethodSource("invalidPermits")
    void invalidKnownPermitBlocksNewPaidMappingWithoutChangingExistingStockIdentity(Marketplace market,String state,String reason)throws Exception{
        var shop=fixture(market);var card=card(shop,state);save(shop,card);
        var selection=List.of(selected(market));
        assertEquals(GtinWriteTestFixture.GTIN,KizPurchaseMappingProof.resolve(shop,selection).get(selection.getFirst()),
                "Permit validity must not remove the physical identity used for existing introduced stock");
        var failure=assertThrows(IllegalStateException.class,()->KizPurchaseMappingProof.require(shop,selection,Set.of(GtinWriteTestFixture.GTIN)));
        assertEquals(reason,failure.getMessage());assertNoPurchase();
    }
    @ParameterizedTest @MethodSource("invalidPermits")
    void projectionClearsInvalidPermitsAndCoordinatorReportsTheRichReasonBeforeSigning(Marketplace market,String state,String reason)throws Exception{
        var shop=fixture(market);var card=card(shop,state);save(shop,card);var repository=repository(shop);
        repository.upsertProducts(List.of(new ZnackModels.Product(GtinWriteTestFixture.GTIN,"Synthetic","6109100000","CONFORMITY_DECLARATION","OLD","2020-01-01",null)));
        repository.updateProductDocuments(GtinWriteTestFixture.GTIN,List.of(new ZnackModels.GoodsDocument("CONFORMITY_DECLARATION","OLD","2020-01-01")));
        ZnackCatalogSyncService.project(shop,List.of(card));
        var product=repository.findActiveProduct(GtinWriteTestFixture.GTIN).orElseThrow();
        var failure=assertThrows(IllegalStateException.class,()->new ZnackPurchaseCoordinator(repository,null,null,null)
                .validatePrerequisites(repository.getSettings(),GtinWriteTestFixture.GTIN,1));
        assertAll(()->assertEquals(reason,failure.getMessage()),
                ()->assertTrue(product.permitDocuments().isEmpty()),
                ()->assertNull(product.certificateNumber()),
                ()->assertEquals(List.of(card),new GtinSyncRepository().catalog(shop.getId(),market),"Keep rich validity/status evidence in the Catalog cache"));
        assertNoPurchase();
    }
    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void activePermitKeepsExistingPaidProofAndCoordinatorPrerequisites(Marketplace market)throws Exception{
        var shop=fixture(market);var card=card(shop,"active");save(shop,card);
        assertDoesNotThrow(()->KizPurchaseMappingProof.require(shop,List.of(selected(market)),Set.of(GtinWriteTestFixture.GTIN)));
        ZnackCatalogSyncService.project(shop,List.of(card));var repository=repository(shop);
        assertEquals("Synthetic declaration",repository.findActiveProduct(GtinWriteTestFixture.GTIN).orElseThrow().certificateNumber());
        var failure=assertThrows(IllegalStateException.class,()->new ZnackPurchaseCoordinator(repository,null,null,null)
                .validatePrerequisites(repository.getSettings(),GtinWriteTestFixture.GTIN,1));
        assertEquals("omsId is required before buying KIZ.",failure.getMessage());assertNoPurchase();
    }
    @ParameterizedTest @EnumSource(value=Marketplace.class,names={"WILDBERRIES","OZON"})
    void absentRichPermitObservationPreservesLegacyIdentityAndSavedDocuments(Marketplace market)throws Exception{
        var shop=fixture(market);var observed=card(shop,"active");
        var card=new GtinSyncModels.RegisteredGtin(observed.gtin(),observed.article(),observed.color(),observed.size(),true,true,observed.source(),observed.syncedAt(),
                new CatalogProductData(observed.details().fields(),List.of(),List.of()));save(shop,card);
        assertDoesNotThrow(()->KizPurchaseMappingProof.require(shop,List.of(selected(market)),Set.of(GtinWriteTestFixture.GTIN)));
        var repository=repository(shop);repository.upsertProducts(List.of(new ZnackModels.Product(GtinWriteTestFixture.GTIN,"Synthetic","6109100000","CONFORMITY_DECLARATION","OLD","2020-01-01",null)));
        repository.updateProductDocuments(GtinWriteTestFixture.GTIN,List.of(new ZnackModels.GoodsDocument("CONFORMITY_DECLARATION","OLD","2020-01-01")));
        ZnackCatalogSyncService.project(shop,List.of(card));assertEquals("OLD",repository.findActiveProduct(GtinWriteTestFixture.GTIN).orElseThrow().certificateNumber());assertNoPurchase();
    }
    @ParameterizedTest @ValueSource(strings={"REGISTRY_DOCUMENTS_PENDING","REGISTRY_DOCUMENTS_UNAVAILABLE"})
    void unresolvedRegistryEvidenceClearsProjectedPermitsAndReachesThePaidGuard(String reason)throws Exception{
        var shop=fixture(Marketplace.WILDBERRIES);var active=card(shop,"active");
        var fields=new HashMap<>(active.details().fields());fields.put("@documents_status",List.of(reason));
        var card=new GtinSyncModels.RegisteredGtin(active.gtin(),active.article(),active.color(),active.size(),true,true,active.source(),active.syncedAt(),
                new CatalogProductData(fields,List.of(),active.details().documents()));save(shop,card);
        ZnackCatalogSyncService.project(shop,List.of(card));var repository=repository(shop);
        assertTrue(repository.findActiveProduct(card.gtin()).orElseThrow().permitDocuments().isEmpty());
        var failure=assertThrows(IllegalStateException.class,()->new ZnackPurchaseCoordinator(repository,null,null,null)
                .validatePrerequisites(repository.getSettings(),card.gtin(),1));
        assertEquals(reason,failure.getMessage());assertNoPurchase();
    }
    @Test void mixedPermitsProjectOnlyTheActiveDocumentAndPermitTheExistingPrerequisiteFlow()throws Exception{
        var shop=fixture(Marketplace.WILDBERRIES);var active=card(shop,"active");var expired=card(shop,"expired");
        var card=new GtinSyncModels.RegisteredGtin(active.gtin(),active.article(),active.color(),active.size(),true,true,active.source(),active.syncedAt(),
                new CatalogProductData(active.details().fields(),List.of(),List.of(expired.details().documents().getFirst(),
                        new CatalogProductData.Document("DECLARATION","ACTIVE","2020-01-01",LocalDate.now().plusDays(365).toString(),false,"",Map.of("registryStatus","active")))));save(shop,card);
        assertDoesNotThrow(()->KizPurchaseMappingProof.require(shop,List.of(selected(Marketplace.WILDBERRIES)),Set.of(card.gtin())));
        ZnackCatalogSyncService.project(shop,List.of(card));var product=repository(shop).findActiveProduct(card.gtin()).orElseThrow();
        assertEquals(List.of(new ZnackModels.GoodsDocument("CONFORMITY_DECLARATION","ACTIVE","2020-01-01")),product.permitDocuments());
        assertEquals("ACTIVE",product.certificateNumber());assertNoPurchase();
    }
    @Test void canonicalCatalogAliasesCannotHideKnownInvalidPermitsFromThePaidGuard()throws Exception{
        var shop=fixture(Marketplace.WILDBERRIES);var expired=card(shop,"expired");
        save(shop,new GtinSyncModels.RegisteredGtin(expired.gtin().substring(1),expired.article(),expired.color(),expired.size(),true,true,expired.source(),expired.syncedAt(),expired.details()));
        var failure=assertThrows(IllegalStateException.class,()->KizPurchaseMappingProof.require(shop,List.of(selected(Marketplace.WILDBERRIES)),Set.of(expired.gtin())));
        assertEquals("GTIN_DOCUMENT_EXPIRED",failure.getMessage());assertNoPurchase();
    }
    @Test void coordinatorRechecksRichEvidenceWhenTheOlderProjectedProductStillHasACompletePermit()throws Exception{
        var shop=fixture(Marketplace.WILDBERRIES);var active=card(shop,"active");save(shop,active);
        ZnackCatalogSyncService.project(shop,List.of(active));var repository=repository(shop);
        save(shop,card(shop,"expired")); // The queued request must not trust its older flattened metadata.
        assertFalse(repository.findActiveProduct(active.gtin()).orElseThrow().permitDocuments().isEmpty());
        var failure=assertThrows(IllegalStateException.class,()->new ZnackPurchaseCoordinator(repository,null,null,null)
                .validatePrerequisites(repository.getSettings(),active.gtin(),1));
        assertEquals("GTIN_DOCUMENT_EXPIRED",failure.getMessage());assertNoPurchase();
    }
    private Shop fixture(Marketplace market)throws Exception{
        var shop=GtinWriteTestFixture.shop(market);
        if(market==Marketplace.WILDBERRIES)try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,synced_at) VALUES(1,101,'Fixture','fixture')");
            s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,11,101,'M')");
            s.execute("INSERT INTO wb_product_characteristics(shop_id,nm_id,characteristic_id,name,value_json) VALUES(1,101,14177449,'Цвет','[\"Black\"]')");
            s.execute("INSERT INTO wb_product_size_skus(shop_id,chrt_id,sku) VALUES(1,11,'SKU-M'),(1,11,'"+GtinWriteTestFixture.GTIN+"')");
        }else new OzonCatalogRepository().upsertPage(shop.getId(),List.of(new OzonProductDto("9001","offer-M","SKU-M","Synthetic","","Fixture","Black","M","Fashion","",false,"",List.of(GtinWriteTestFixture.GTIN))),"");
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_settings SET oms_connection='fixture-connection',auto_introduction=1 WHERE shop_id=?")){p.setInt(1,shop.getId());p.executeUpdate();}
        return shop;
    }
    private static GtinSyncModels.RegisteredGtin card(Shop shop,String state){
        var paper=new CatalogProductData.Document("DECLARATION","Synthetic declaration",state.equals("future")?LocalDate.now().plusDays(1).toString():"2020-01-01",
                state.equals("expired")?LocalDate.now().minusDays(1).toString():LocalDate.now().plusDays(365).toString(),false,"",Map.of("registryStatus",state.equals("revoked")?"revoked":"active"));
        var details=new CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published"),"tnved",List.of("6109100000")),List.of(),List.of(paper));
        return new GtinSyncModels.RegisteredGtin(GtinWriteTestFixture.GTIN,"Fixture","Black","M",true,true,RegisteredGtinProof.source(shop),Instant.now(),details);
    }
    private static KizPurchaseMappingProof.Product selected(Marketplace market){return new KizPurchaseMappingProof.Product(market==Marketplace.WILDBERRIES?"101":"9001","SKU-M",GtinWriteTestFixture.GTIN,"SYNTHETIC-ORDER");}
    private static void save(Shop shop,GtinSyncModels.RegisteredGtin card){new GtinSyncRepository().saveCatalog(shop.getId(),shop.getMarketplace(),List.of(card));}
    private static ZnackRepository repository(Shop shop){return new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName()));}
    private static void assertNoPurchase()throws Exception{try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT (SELECT count(*) FROM kiz_orders)+(SELECT count(*) FROM znack_purchase_pipelines)")){assertEquals(0,r.getInt(1));}}
}
