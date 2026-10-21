package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.features.gtinsync.GtinWriteTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogCredentialRepositoryTest {
    @TempDir Path root; private Shop a,b; private CatalogCredentialRepository store;
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", root.toString()); Database.initDatabase();
        a=shop(Marketplace.WILDBERRIES); b=shop(Marketplace.OZON);
        store=new CatalogCredentialRepository(new CatalogCredentialRepository.Protection() {
            public String protect(String value) { return "fixture-encrypted:"+java.util.Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            public String reveal(String value) { return new String(java.util.Base64.getDecoder().decode(value.substring(18)),java.nio.charset.StandardCharsets.UTF_8); }
        });
    }
    @AfterEach void close(){System.clearProperty("vncode.appdata.dir");}
    @Test void protectsPerShopAndRejectsChangedCertificateEnvironmentAndCredentials() throws Exception {
        var original=RegisteredGtinProof.current(a);
        store.saveVerified(a,original,"fixture-key-one");
        assertEquals("fixture-key-one",store.read(a,original).orElseThrow());
        assertFalse(store.hasSaved(b.getId())); assertTrue(store.read(b,RegisteredGtinProof.current(b)).isEmpty());
        try(var c=Database.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT protected_key FROM znack_catalog_credentials")) {
            assertTrue(r.next()); assertFalse(r.getString(1).contains("fixture-key-one"));
        }
        saveSettings(a,"B".repeat(40),ZnackModels.PRODUCTION_TRUE_API);
        assertThrows(IllegalStateException.class,()->store.read(a,original));
        assertThrows(IOException.class,()->store.read(a,RegisteredGtinProof.current(a)));
        saveSettings(a,CERTIFICATE,"https://sandbox.example.invalid/api/v3/true-api");
        assertThrows(IOException.class,()->store.read(a,RegisteredGtinProof.current(a)));
        store.remove(a);assertFalse(store.hasSaved(a.getId()));
    }
    @Test void removalAndRotationInvalidateOpenClientsWithoutAPlaintextFallback() throws Exception {
        var scope=RegisteredGtinProof.current(a);
        store.saveVerified(a,scope,"fixture-key-one");
        String digest=com.vncode.app.integration.listing.ListingIdentity.hash("fixture-key-one");
        store.requireUnchanged(a,scope,digest);
        store.saveVerified(a,scope,"fixture-key-two");
        assertThrows(IllegalStateException.class,()->store.requireUnchanged(a,scope,digest));
        store.remove(a);assertThrows(IllegalStateException.class,()->store.requireUnchanged(a,scope,digest));
    }
    @Test void failedRemoteCheckKeepsPreviousKeyAndDoesNotSaveTheRejectedKey() throws Exception {
        var scope=RegisteredGtinProof.current(a);store.saveVerified(a,scope,"fixture-original");
        var service=new CatalogConnectionService(store,(shop,settings,owner,key,guard)->{throw new IOException("CATALOG_KEY_OWNER_MISMATCH");});
        assertThrows(IOException.class,()->service.checkAndSave(a,"fixture-foreign",()->true));
        assertEquals("fixture-original",store.read(a,scope).orElseThrow());
    }
    @Test void storeOnlyFollowsSuccessfulCheckAndShopSwitchDropsTheResult() throws Exception {
        var active=new AtomicBoolean(true);
        var service=new CatalogConnectionService(store,(shop,settings,owner,key,guard)->{guard.run();active.set(false);});
        assertThrows(IllegalStateException.class,()->service.checkAndSave(a,"fixture-key",active::get));
        assertFalse(store.hasSaved(a.getId()));
        var success=new CatalogConnectionService(store,(shop,settings,owner,key,guard)->guard.run());
        success.checkAndSave(a,"fixture-key",()->true);
        success.checkAndSave(a,"",()->true);
        assertEquals("fixture-key",store.read(a,RegisteredGtinProof.current(a)).orElseThrow());
    }
    @Test void invalidKeyAndBrokenSecretProtectionNeverWriteARecord() throws Exception {
        for(String key:new String[]{"", "abc\nxyz", "abc xyz", "x".repeat(8193)})
            assertThrows(IOException.class,()->CatalogCredentialRepository.normalize(key));
        var broken=new CatalogCredentialRepository(new CatalogCredentialRepository.Protection(){
            public String protect(String value)throws IOException{throw new IOException("Fixture encryption unavailable");}
            public String reveal(String value)throws IOException{throw new IOException("Fixture decryption unavailable");}
        });
        assertThrows(IOException.class,()->broken.saveVerified(a,RegisteredGtinProof.current(a),"fixture-key"));
        assertFalse(store.hasSaved(a.getId()));
    }
}
