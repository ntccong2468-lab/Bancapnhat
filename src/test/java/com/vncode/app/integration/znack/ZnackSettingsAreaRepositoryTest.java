package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.ZnackModels.Settings;
import com.vncode.app.integration.znack.ZnackModels.ShopContext;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic shops and local SQLite only; no marketplace or paid operations. */
class ZnackSettingsAreaRepositoryTest {
    @TempDir Path root;
    private ZnackRepository first, second;

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", root.toString());
        Database.initDatabase();
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'One','fixture-one'),(2,'Two','fixture-two')");
        }
        first = new ZnackRepository(new ShopContext(1, "One"));
        second = new ZnackRepository(new ShopContext(2, "Two"));
    }

    @AfterEach void cleanup() { System.clearProperty("vncode.appdata.dir"); }

    @Test void documentThenStaleConnectionPreservesBothAreasAndTheOtherShop() {
        Settings original = settings("old-oms", "old-connection")
                .withDefaultGoodsDocument("CONFORMITY_DECLARATION", "OLD", "01.01.2026", "31.12.2027");
        first.saveSettings(original);
        second.saveSettings(original);
        Settings staleEditor = settings("new-oms", "new-connection")
                .withDefaultGoodsDocument(original.documentType(), original.documentNumber(), original.documentDate(), original.documentExpiryDate());

        first.saveDefaultGoodsDocument("CONFORMITY_CERTIFICATE", "NEW", "02.02.2026", "31.12.2029");
        Settings merged = first.saveConnectionSettings(staleEditor);

        assertEquals("new-oms", merged.omsId());
        assertEquals("new-connection", merged.omsConnection());
        assertEquals("CONFORMITY_CERTIFICATE", merged.documentType());
        assertEquals("NEW", merged.documentNumber());
        assertEquals("02.02.2026", merged.documentDate());
        assertEquals("31.12.2029", merged.documentExpiryDate());
        assertEquals(merged, first.getSettings());
        assertEquals(original, second.getSettings());
    }

    @Test void connectionThenDocumentPreservesBothAndCreatesMissingSettings() {
        Settings saved = first.saveConnectionSettings(settings("new-oms", "new-connection"));
        assertEquals("new-oms", saved.omsId());
        assertEquals("new-connection", saved.omsConnection());

        Settings merged = first.saveDefaultGoodsDocument("CONFORMITY_DECLARATION", "NEW", "02.02.2026", "31.12.2029");
        assertEquals("new-oms", merged.omsId());
        assertEquals("new-connection", merged.omsConnection());
        assertEquals("NEW", merged.documentNumber());
        assertEquals("31.12.2029", merged.documentExpiryDate());
        assertEquals(merged, first.getSettings());

        Settings secondInitial = second.saveDefaultGoodsDocument("CONFORMITY_DECLARATION", "OTHER", "01.03.2026", "");
        assertEquals("OTHER", secondInitial.documentNumber());
        assertEquals("", secondInitial.documentExpiryDate());
        assertEquals(secondInitial, second.getSettings());
        assertEquals(merged, first.getSettings());
    }

    @Test void existingAndLegacyPipelinesKeepOriginalConnectionAcrossBothSaves() throws Exception {
        Settings original = settings("old-oms", "old-connection")
                .withDefaultGoodsDocument("CONFORMITY_DECLARATION", "OLD", "01.01.2026", "31.12.2027");
        first.saveSettings(original);
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(1,'04600000000001','Fixture','2026-01-01T00:00:00Z'),(1,'04600000000002','Legacy','2026-01-01T00:00:00Z')");
        }
        long existing = first.createPipeline("04600000000001", 1);
        long legacy = first.createPipeline("04600000000002", 1);
        try (var c = Database.getConnection(); var p = c.prepareStatement("DELETE FROM znack_pipeline_connections WHERE shop_id=1 AND pipeline_id=?")) {
            p.setLong(1, legacy); p.executeUpdate();
        }

        first.saveDefaultGoodsDocument("CONFORMITY_CERTIFICATE", "NEW", "02.02.2026", "31.12.2029");
        first.saveConnectionSettings(settings("new-oms", "new-connection"));
        assertEquals(original, first.pipelineSettings(existing));
        assertEquals(original, first.pipelineSettings(legacy), "A legacy pipeline must be bound before changing settings");
        assertEquals("new-oms", first.getSettings().omsId());
        assertEquals("NEW", first.getSettings().documentNumber());
    }

    private static Settings settings(String oms, String connection) {
        return new Settings("", "", oms, connection, "", "", "", "", "", "[]", "", "", "", false,
                "", "[]", "", null, "", "", "", 60, "", "");
    }
}
