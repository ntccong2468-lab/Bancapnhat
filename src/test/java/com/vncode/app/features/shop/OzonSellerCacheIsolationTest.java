package com.vncode.app.features.shop;

import com.vncode.app.config.Database;
import com.vncode.app.features.license.LicenseDomainFixture;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseService;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OzonSellerCacheIsolationTest {
    @TempDir Path directory;
    private LicenseService previous, fixture;
    private ShopRepository shops;

    @BeforeEach void setUp() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
        previous = LicenseRuntime.service();
        fixture = LicenseDomainFixture.active(directory.resolve("license-fixture"));
        LicenseRuntime.install(fixture);
        shops = new ShopRepository();
        try (var connection = Database.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(101,'A','OZON','seller-a','key-a'),(102,'B','OZON','seller-b','key-b')");
            // Same posting number in two sellers must remain distinct.
            statement.executeUpdate("INSERT INTO ozon_postings(shop_id,posting_number,status,synced_at) VALUES(101,'same-order','awaiting_packaging','2026-10-31T00:00:00Z'),(102,'same-order','delivering','2026-10-31T00:00:00Z')");
        }
    }

    @AfterEach void tearDown() throws Exception {
        if (previous != null) LicenseRuntime.install(previous);
        if (fixture != null) fixture.close();
        System.clearProperty("vncode.appdata.dir");
    }

    @Test void changingSellerIsRejectedAndSwitchingBetweenSeparateShopsPreservesBothCaches() throws Exception {
        var otherSeller = new Shop(101, "A changed", Marketplace.OZON, "seller-b", "key-b");
        assertThrows(IllegalArgumentException.class, () -> shops.update(101, otherSeller));
        assertEquals("seller-a", shops.findById(101).getClientId());
        assertEquals("seller-b", shops.findById(102).getClientId());
        assertEquals("awaiting_packaging", status(101));
        assertEquals("delivering", status(102));
    }

    @Test void rotatingApiKeyForSameSellerPreservesItsOrdersAndOtherSeller() throws Exception {
        assertEquals(1, shops.update(101, new Shop(101, "A renamed", Marketplace.OZON, "seller-a", "rotated-key")));
        assertEquals("rotated-key", shops.findById(101).getApiKey());
        assertEquals("key-b", shops.findById(102).getApiKey());
        assertEquals("awaiting_packaging", status(101));
        assertEquals("delivering", status(102));
    }

    @Test void fullSqliteSnapshotRestoreKeepsTheShopToSellerMappingAndCaches() throws Exception {
        Path restored = directory.resolve("restored");
        Files.createDirectories(restored);
        Path backup = restored.resolve("database.db");
        try (var connection = Database.getConnection(); var statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, backup.toString());
            statement.execute();
        }
        System.setProperty("vncode.appdata.dir", restored.toString());
        Database.initDatabase();
        assertEquals("seller-a", shops.findById(101).getClientId());
        assertEquals("seller-b", shops.findById(102).getClientId());
        assertEquals("awaiting_packaging", status(101));
        assertEquals("delivering", status(102));
        assertThrows(IllegalArgumentException.class, () -> shops.update(101, new Shop(101, "A", Marketplace.OZON, "seller-b", "key-b")));
    }

    private String status(int shopId) throws Exception {
        try (var connection = Database.getConnection(); var statement = connection.prepareStatement("SELECT status FROM ozon_postings WHERE shop_id=? AND posting_number='same-order'")) {
            statement.setInt(1, shopId);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }
}
