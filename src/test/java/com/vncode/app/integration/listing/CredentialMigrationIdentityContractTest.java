package com.vncode.app.integration.listing;

import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.KizService;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Characterizes existing ownership semantics using only synthetic credentials. */
class CredentialMigrationIdentityContractTest {
    @TempDir Path profile;
    private String previousDirectory;

    @BeforeEach void setup() throws Exception {
        previousDirectory = System.getProperty("vncode.appdata.dir");
        System.setProperty("vncode.appdata.dir", profile.toString());
        Database.initDatabase();
    }

    @AfterEach void cleanup() {
        if (previousDirectory == null) System.clearProperty("vncode.appdata.dir");
        else System.setProperty("vncode.appdata.dir", previousDirectory);
    }

    @Test void storedOwnerFingerprintUsesExactCredentialBytes() throws Exception {
        // Deliberate whitespace and a protector-like prefix are legacy plaintext here.
        String credential = "  dpapi:fixture  ";
        insert(7, "OZON", "client-7", credential);
        var shop = new Shop(7, "Synthetic shop", Marketplace.OZON, "client-7", credential);
        String recordedOwner = "3825b922d7f16413ed7e76977d0d1b2a6668a1a1dac53aaacb33c4a6821313aa";
        assertEquals(recordedOwner, ListingIdentity.fingerprint(shop));
        try (var connection = Database.getConnection()) {
            assertEquals(recordedOwner, ListingIdentity.fingerprint(connection, 7, Marketplace.OZON));
        }
        assertDoesNotThrow(() -> ListingIdentity.require(shop));
        assertNotEquals(recordedOwner, ListingIdentity.fingerprint(
                new Shop(7, "Synthetic shop", Marketplace.OZON, "client-7", credential.strip())));
        try (var connection = Database.getConnection();
             var update = connection.prepareStatement("UPDATE shops SET api_key=? WHERE id=?")) {
            update.setString(1, "different-synthetic-credential");
            update.setInt(2, 7);
            update.executeUpdate();
        }
        assertThrows(SecurityException.class, () -> ListingIdentity.require(shop));
        try (var connection = Database.getConnection()) {
            assertNotEquals(recordedOwner, ListingIdentity.fingerprint(connection, 7, Marketplace.OZON));
        }
    }

    @Test void duplicateWbCredentialsRemainAmbiguousAndNeverSelectAnOwner() throws Exception {
        insert(1, "WILDBERRIES", null, "synthetic-duplicate");
        insert(2, "WILDBERRIES", null, "synthetic-duplicate");
        var previousLicense = LicenseRuntime.service();
        try (var license = LicenseDomainFixture.active(profile.resolve("license-fixture"))) {
            LicenseRuntime.install(license);
            var failure = assertThrows(IOException.class,
                    () -> KizService.addDataMatrixCodeToOrder("synthetic-duplicate", 1L, "synthetic-code"));
            assertEquals("SHOP_CREDENTIALS_AMBIGUOUS", failure.getMessage());
        } finally {
            LicenseRuntime.install(previousLicense);
        }
    }

    private void insert(int id, String marketplace, String clientId, String credential) throws Exception {
        try (var connection = Database.getConnection();
             var insert = connection.prepareStatement("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(?,?,?,?,?)")) {
            insert.setInt(1, id);
            insert.setString(2, "Synthetic shop");
            insert.setString(3, marketplace);
            insert.setString(4, clientId);
            insert.setString(5, credential);
            insert.executeUpdate();
        }
    }
}
