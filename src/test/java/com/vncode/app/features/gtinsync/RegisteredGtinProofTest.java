package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static com.vncode.app.features.gtinsync.GtinWriteTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredGtinProofTest {
    @TempDir Path root;Shop shop;
    @BeforeEach void init()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=shop(Marketplace.WILDBERRIES);}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    @Test void freshPublishedTradeUnitBelongsToExactTestedEnterpriseEnvironmentAndCredentials()throws Exception {
        var source=RegisteredGtinProof.source(shop);assertTrue(source.matches("National Catalog@[a-f0-9]{64}"));
        RegisteredGtinProof.requireCurrent(shop,GTIN);
        saveSettings(shop,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        assertNotEquals(source,RegisteredGtinProof.source(shop));assertThrows(IllegalArgumentException.class,()->RegisteredGtinProof.requireCurrent(shop,GTIN));
        saveSettings(shop,CERTIFICATE,"https://sandbox.example.invalid/api/v3/true-api");
        assertNotEquals(source,RegisteredGtinProof.source(shop));assertThrows(IllegalArgumentException.class,()->RegisteredGtinProof.requireCurrent(shop,GTIN));
    }
    @Test void staleUnpublishedOrBoxRowsDoNotAuthorizeWrites() {
        var source=RegisteredGtinProof.source(shop);var repo=new GtinSyncRepository();
        for(var row:List.of(new RegisteredGtin(GTIN,"","","",true,true,source,Instant.now().minusSeconds(86401)),new RegisteredGtin(GTIN,"","","",false,true,source,Instant.now()),new RegisteredGtin(GTIN,"","","",true,false,source,Instant.now()))){
            repo.saveCatalog(1,Marketplace.WILDBERRIES,List.of(row));assertThrows(IllegalArgumentException.class,()->RegisteredGtinProof.requireCurrent(shop,GTIN));
        }
    }
    @Test void expiredOrUntestedCertificateCannotProduceScope() {
        var repository=new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture"));var settings=repository.getSettings();
        assertThrows(IllegalStateException.class,()->RegisteredGtinProof.scope(shop,settings,Instant.now().plusSeconds(86401)));
        assertThrows(IllegalStateException.class,()->RegisteredGtinProof.scope(shop,ZnackModels.Settings.empty(),Instant.now()));
        assertFalse(RegisteredGtinProof.current(shop).toString().contains("7700000000"));
    }
    @Test void normalizedLeadingZerosNeverLoseUnrelatedBarcodes() {
        assertTrue(RegisteredGtinProof.sameCode("00000017",GTIN));assertFalse(RegisteredGtinProof.sameCode("legacy",GTIN));
    }
    @Test void configuredGoodsOwnerCannotChangeToAnotherEnterpriseUnderExistingProof() {
        var repository=new ZnackRepository(new ZnackModels.ShopContext(1,"Fixture"));var settings=repository.getSettings();
        var changed=new ZnackModels.Settings(settings.trueApiBaseUrl(),"","","",settings.participantInn(),"","7800000000","",settings.signerCertificate(),"[]","","","",false,"","[]",settings.certificateMetadataJson(),settings.signerTestedAt());
        repository.saveSettings(changed);assertThrows(IllegalStateException.class,()->RegisteredGtinProof.requireCurrent(shop,GTIN));
    }
    @Test void sandboxAndUnverifiedOriginsCannotAuthorizeProductionSellerWrite() {
        for(String base:List.of("https://sandbox.example.invalid/api/v3/true-api","https://markirovka.crpt.ru.evil.invalid/api/v3/true-api","http://markirovka.crpt.ru/api/v3/true-api")){
            saveSettings(shop,CERTIFICATE,base);catalog(shop);
            assertThrows(IllegalArgumentException.class,()->RegisteredGtinProof.requireCurrent(shop,GTIN));
        }
    }
}
