package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.gtinsync.GtinSyncRepository;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;

class ObservedCatalogReadRevisionTest {
    @TempDir Path root;
    Shop shop;
    ZnackRegistrationScope scope;
    OzonCardRegistrationRepository registrations;
    final ProductKey key=new ProductKey(2,Marketplace.OZON,"501","offer-501");
    final Draft draft=new Draft("6204",1,"Fixture","Brand",Map.of());
    @BeforeEach void setup()throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        shop=GtinWriteTestFixture.shop(Marketplace.OZON);
        scope=ZnackRegistrationScope.fromSettings(new ZnackRepository(new ZnackModels.ShopContext(2,"Fixture")).getSettings());
        registrations=new OzonCardRegistrationRepository();CatalogRotationKeys.save(shop,"synthetic-A");
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    private ZnackNationalCatalogService catalog(Runnable duringRead){
        String revision=CatalogReadRevision.current(shop.getId());
        var api=new ZnackApiClient(){
            @Override public String catalogReadRevision(){return revision;}
            @Override public void assertCatalogReadScope(){CatalogReadRevision.requireUnchanged(shop.getId(),revision);}
            @Override public JsonElement productCards(String base,String token,String gtin){
                duringRead.run();return JsonParser.parseString("{\"result\":[{\"gtin\":\""+gtin+"\",\"good_id\":91,\"good_status\":\"published\",\"good_signed\":true,\"good_detailed_status\":[\"published\"],\"identified_by\":[{\"type\":\"gtin\",\"value\":\""+gtin+"\",\"level\":\"trade-unit\"}]}]}");
            }
        };
        return new ZnackNationalCatalogService(api,null,null,new ZnackRepository(new ZnackModels.ShopContext(2,"Fixture")).getSettings());
    }
    private ZnackNationalCatalogService.ObservedCard observe()throws Exception{return catalog(()->{}).observedCardStatus("fixture",GtinWriteTestFixture.GTIN,91L);}
    private RegisteredGtin stored(){return new GtinSyncRepository().catalogItem(shop.getId(),Marketplace.OZON,GtinWriteTestFixture.GTIN);}
    private void readyToPublish(){
        assertTrue(registrations.claim(shop,key,"9001","",scope,false));
        assertTrue(registrations.transition(key,Status.CHECKING,Status.ALLOCATING_GTIN));
        registrations.generated(key,GtinWriteTestFixture.GTIN,ZnackNationalCatalogService.buildPayload(GtinWriteTestFixture.GTIN,draft,"").toString());
        assertTrue(registrations.transition(key,Status.READY_TO_SUBMIT,Status.FEED_SUBMITTING));
        registrations.submitted(key,"saved-feed");registrations.progress(key,Status.READY_TO_SIGN,91L);
    }
    @Test void freshPublicationKeepsItsReadEpochAndCanCompleteAfterPairedRefresh()throws Exception {
        readyToPublish();var observed=observe();CatalogReadRevision.completed(shop.getId(),observed.readRevision());
        registrations.publish(shop,key,scope,observed);
        assertEquals(observed.readRevision(),stored().readRevision());
        assertDoesNotThrow(()->RegisteredGtinProof.requireCurrent(shop,observed.gtin()));
        assertTrue(registrations.complete(shop,key,scope,observed.gtin()));
        assertEquals("saved-feed",registrations.find(key).feedId());
    }
    @Test void aLateOldPublicationCannotOverwriteFreshProofAfterRotationOrAba()throws Exception {
        readyToPublish();var old=observe();
        CatalogRotationKeys.save(shop,"synthetic-B");CatalogRotationKeys.save(shop,"synthetic-A");
        var fresh=observe();assertNotEquals(old.readRevision(),fresh.readRevision());
        CatalogReadRevision.completed(shop.getId(),fresh.readRevision());registrations.publish(shop,key,scope,fresh);
        var before=stored();var checkpoint=registrations.find(key);
        assertEquals("SOURCE_CONFIGURATION_CHANGED",assertThrows(IllegalStateException.class,()->registrations.publish(shop,key,scope,old)).getMessage());
        assertEquals(before,stored());
        assertEquals(com.vncode.app.features.products.ProductJson.GSON.toJson(checkpoint),com.vncode.app.features.products.ProductJson.GSON.toJson(registrations.find(key)));
        assertDoesNotThrow(()->RegisteredGtinProof.requireCurrent(shop,fresh.gtin()));
    }
    @Test void existingCardLinkRejectsAnOldReadAndKeepsFreshRevision()throws Exception {
        var old=observe();CatalogRotationKeys.save(shop,"synthetic-B");
        assertEquals("SOURCE_CONFIGURATION_CHANGED",assertThrows(IllegalStateException.class,()->registrations.linkObserved(shop,key,"9001","",scope,draft,old)).getMessage());
        assertNull(registrations.find(key));var fresh=observe();
        assertTrue(registrations.linkObserved(shop,key,"9001","",scope,draft,fresh));
        assertEquals(fresh.readRevision(),stored().readRevision());assertEquals("",registrations.find(key).feedId());
    }
    @Test void preservingPendingRegistryDocumentsKeepsTheNewCardReadRevision()throws Exception {
        readyToPublish();var observed=observe();
        var details=new CatalogProductData(Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published"),"@documents_status",List.of("REGISTRY_DOCUMENTS_PENDING")),List.of(),List.of(new CatalogProductData.Document("DECLARATION","FIXTURE","2025-01-01","",true,"",Map.of())));
        var pending=new RegisteredGtin(GtinWriteTestFixture.GTIN,"Fixture","","",true,true,RegisteredGtinProof.source(shop),Instant.now(),details,observed.readRevision());
        new GtinSyncRepository().saveCatalog(shop.getId(),Marketplace.OZON,List.of(pending));
        registrations.publish(shop,key,scope,observed);
        assertEquals(observed.readRevision(),stored().readRevision());
        assertEquals("REGISTRY_DOCUMENTS_PENDING",stored().details().single("@documents_status"));
        assertFalse(stored().details().documents().isEmpty());
    }
    @Test void aRotationDuringTheActualCardReadCannotReturnAReboundObservation()throws Exception {
        var catalog=catalog(()->{try{CatalogRotationKeys.save(shop,"synthetic-B");}catch(Exception failure){throw new IllegalStateException(failure);}});
        assertEquals("SOURCE_CONFIGURATION_CHANGED",assertThrows(IllegalStateException.class,()->catalog.observedCardStatus("fixture",GtinWriteTestFixture.GTIN,91L)).getMessage());
    }
}
