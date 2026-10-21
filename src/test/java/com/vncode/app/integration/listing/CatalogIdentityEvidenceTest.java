package com.vncode.app.integration.listing;

import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.registration.CatalogProductDataParser;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CatalogIdentityEvidenceTest {
    @TempDir Path profile;
    private String previous;
    private Shop shop;
    private ProductKey key;
    @BeforeEach void setup()throws Exception{
        previous=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);key=new ProductKey(shop.getId(),shop.getMarketplace(),"101","201");
    }
    @AfterEach void cleanup(){if(previous==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previous);}
    @Test void exactVariantEvidenceCarriesTheActualCatalogIdThroughStorageWithoutInventingIt(){
        observe("{\"good_id\":\"000123\",\"good_status\":\"published\"}");
        var first=evidence();assertEquals("000123",first.catalogId());assertEquals("published",first.catalogStatus());assertEquals(GtinWriteTestFixture.GTIN,new GtinSyncRepository().mapping(nativeKey()));
        observe("{\"good_id\":456,\"good_status\":\"published\"}");var changed=evidence();assertEquals("456",changed.catalogId());assertNotEquals(first.observationHash(),changed.observationHash(),"A different remote card identity is a different observation");
        observe("{\"good_status\":\"published\",\"feed_id\":\"not-a-card-id\"}");assertEquals("",evidence().catalogId(),"Legacy/missing card identity must remain unknown, not a local feed identity");assertFalse(evidence().current(),"Missing remote identity stays historical rather than becoming current write proof");
        observe("{\"good_id\":789}");assertEquals("",evidence().catalogStatus(),"A cached boolean does not synthesize raw remote publication");assertFalse(evidence().current());
    }
    private void observe(String json){
        var details=CatalogProductDataParser.parse(JsonParser.parseString(json).getAsJsonObject());var repository=new GtinSyncRepository();
        repository.mergeCatalog(shop.getId(),shop.getMarketplace(),List.of(new GtinSyncModels.RegisteredGtin(GtinWriteTestFixture.GTIN,"Fixture","Brown","52",true,true,RegisteredGtinProof.source(shop),Instant.now(),details)));
        repository.saveMapping(nativeKey(),GtinWriteTestFixture.GTIN);
    }
    private GtinSyncModels.ProductKey nativeKey(){return new GtinSyncModels.ProductKey(key.shopId(),key.marketplace(),key.cardId(),key.variantId());}
    private com.vncode.app.features.products.ProductVariant.GtinEvidence evidence(){return ListingIdentity.verifiedGtinEvidence(shop,key,List.of(GtinWriteTestFixture.GTIN),"Fixture","Brown","52","").orElseThrow().evidence();}
}
