package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.features.shop.ShopRepository;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;

class OzonRegistrationIntegrationTest {
    @TempDir Path directory;
    private final String gtin="04631993764363";
    private OzonCardRegistrationRepository registrations;
    private Shop shop;
    private ProductKey key;
    private ZnackRepository settings;
    private ZnackRegistrationScope scope;
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture A','OZON','fixture-a','synthetic-a'),(2,'Fixture B','OZON','fixture-b','synthetic-b')");
            s.execute("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,synced_at) VALUES(1,'101','offer-a','501','Fixture A','2026-10-14T00:00:00Z'),(2,'101','offer-a','501','Fixture B','2026-10-14T00:00:00Z')");
        }
        shop=new Shop(1,"Fixture A",Marketplace.OZON,"fixture-a","synthetic-a");
        key=new ProductKey(1,Marketplace.OZON,"101","offer-a");
        settings=new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture A"));
        settings.saveSettings(ZnackRegistrationScopeTest.settings("7707083893","a".repeat(40)));
        scope=ZnackRegistrationScope.fromSettings(settings.getSettings());
        registrations=new OzonCardRegistrationRepository();
    }
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    private void generated() {
        assertTrue(registrations.claim(shop,key,"501","",scope,false));
        assertTrue(registrations.transition(key,Status.CHECKING,Status.ALLOCATING_GTIN));
        registrations.generated(key,gtin,"{\"good_name\":\"Fixture\",\"tnved\":\"6204\"}");
    }
    @Test void candidateSearchStaysInItsShopAndIncludesRowsBeyondMarketplaceBarcodes() {
        generated();var products=new ProductRepository();
        assertEquals(List.of(key),products.freezeKeys(new ProductQuery(1,Marketplace.OZON,gtin,List.of(),null,false,null,300)));
        assertTrue(products.freezeKeys(new ProductQuery(2,Marketplace.OZON,gtin,List.of(),null,false,null,300)).isEmpty());
        assertEquals("",products.loadPage(new ProductQuery(1,Marketplace.OZON,gtin,List.of(),null,false,null,300)).rows().getFirst().verifiedGtin());
    }
    @Test void pendingNativeRegistrationBlocksShopDeletionAndSignerReplacement() {
        generated();assertThrows(IllegalStateException.class,()->new ShopRepository().delete(1));
        var current=settings.getSettings();
        assertThrows(ZnackRepository.SignerChangeBlockedException.class,()->settings.saveVerifiedCertificate(current,
                ZnackRegistrationScopeTest.settings("7807083893","b".repeat(40))));
        assertEquals(scope,ZnackRegistrationScope.fromSettings(settings.getSettings()));
        assertNotNull(new ShopRepository().findById(1));assertEquals(gtin,registrations.find(key).gtin());
    }
    @Test void publishedCandidateDoesNotChangeTheKizMappingUntilExplicitCompletionAndSurvivesReinitialization() throws Exception {
        generated();assertTrue(registrations.transition(key,Status.READY_TO_SUBMIT,Status.FEED_SUBMITTING));
        registrations.submitted(key,"fixture-feed");registrations.progress(key,Status.READY_TO_SIGN,901L);
        registrations.publish(shop,key,scope,901L);
        var mappings=new com.vncode.app.integration.ozon.OzonProductGtinMappingRepository();
        assertNull(mappings.findGtin(1,"501"));assertEquals("",registrations.find(key).activeGtin());
        Database.initDatabase();assertEquals("fixture-feed",registrations.find(key).feedId());
        assertThrows(IllegalStateException.class,()->new ShopRepository().delete(1));
        assertThrows(IllegalArgumentException.class,()->registrations.complete(shop,key,scope,gtin),"A legacy publication notification cannot manufacture remote Catalog proof");
        var raw=com.google.gson.JsonParser.parseString("{\"gtin\":\""+gtin+"\",\"good_id\":901,\"good_status\":\"published\",\"good_name\":\"Fixture\"}").getAsJsonObject();
        registrations.publish(shop,key,scope,new ZnackNationalCatalogService.ObservedCard(901,gtin,"published",new ZnackNationalCatalogService.FeedProgress("Signed",901L,List.of()),CatalogProductDataParser.parse(raw),java.time.Instant.now(),true));
        assertTrue(registrations.complete(shop,key,scope,gtin));assertEquals(gtin,mappings.findGtin(1,"501"));
        assertEquals(gtin,registrations.find(key).activeGtin());assertNull(mappings.findGtin(2,"501"));
        try(var c=Database.getConnection();var s=c.createStatement()) {
            try(var r=s.executeQuery("PRAGMA integrity_check")){assertEquals("ok",r.getString(1));}
            try(var r=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(r.next());}
        }
    }
}
