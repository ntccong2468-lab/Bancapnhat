package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncRunRepositoryConcurrencyTest {
    @TempDir Path directory;
    final SyncRunRepository reports = new SyncRunRepository();
    final List<Shop> shops = new ArrayList<>();

    @BeforeEach void init() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase();
        try (var c = Database.getConnection(); var p = c.prepareStatement(
                "INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(?,?,'OZON',?,?)")) {
            for (int id = 1; id <= 10; id++) {
                var shop = new Shop(id, "Synthetic shop " + id, Marketplace.OZON,
                        "synthetic-client-" + id, "synthetic-key-" + id);
                p.setInt(1, id); p.setString(2, shop.getName());
                p.setString(3, shop.getClientId()); p.setString(4, shop.getApiKey()); p.executeUpdate();
                shops.add(shop);
            }
        }
    }

    @AfterEach void clear() { System.clearProperty("vncode.appdata.dir"); }

    @Test void progressWaitsForAnotherWriterInsteadOfFailingDuringReadToWriteUpgrade() throws Exception {
        var shop = shops.getFirst();
        String run = reports.begin(shop, "znack_catalog_read", "AUTHENTICATING");
        try (var writer = Database.getConnection(); var tx = writer.createStatement();
             var executor = Executors.newSingleThreadExecutor()) {
            tx.execute("BEGIN IMMEDIATE");
            var entered = new CountDownLatch(1);
            var future = executor.submit(() -> {
                entered.countDown();
                reports.stage(shop, run, "RUNNING", "READING_CARDS", "");
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> future.get(150, TimeUnit.MILLISECONDS));
            } finally { tx.execute("ROLLBACK"); }
            future.get(10, TimeUnit.SECONDS);
        }
        assertEquals("READING_CARDS", reports.find(run).orElseThrow().step());
        assertEquals(1, reports.events(run, null, 100).size());
    }

    @Test void tenShopsCanPersistConcurrentProgressAndItemsWithoutLosingHistory() throws Exception {
        var runs = new ArrayList<String>();
        for (var shop : shops) runs.add(reports.begin(shop, "znack_catalog_read", "AUTHENTICATING"));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(10)) {
            var futures = new ArrayList<Future<?>>();
            for (int index = 0; index < shops.size(); index++) {
                var shop = shops.get(index); String run = runs.get(index);
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int page = 0; page < 20; page++) {
                        reports.stage(shop, run, "RUNNING", "READING_CARDS", "");
                        reports.add(shop, run, "product-" + page, "variant", "sku", "article", "Blue", "M",
                                "", "SUCCEEDED", "", "{}");
                    }
                    reports.stage(shop, run, "SUCCEEDED", "SOURCE_COMPLETE", "");
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        }
        for (String run : runs) {
            var report = reports.find(run).orElseThrow();
            assertEquals("SUCCEEDED", report.state()); assertEquals(20, report.total());
            assertEquals(20, reports.items(run, 0, 100).size());
            assertEquals(21, reports.events(run, null, 100).size());
        }
        try (var c = Database.getConnection(); var s = c.createStatement(); var r = s.executeQuery("PRAGMA integrity_check")) {
            assertEquals("ok", r.getString(1));
        }
    }

    @Test void failingEventInsertRollsBackProgressAndDoesNotCreatePartialHistory() throws Exception {
        var shop = shops.getFirst(); String run = reports.begin(shop, "znack_catalog_read", "AUTHENTICATING");
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TRIGGER reject_test_event BEFORE INSERT ON sync_run_events "
                    + "WHEN NEW.step='READING_CARDS' BEGIN SELECT RAISE(ABORT,'synthetic event failure'); END");
        }
        assertThrows(IllegalStateException.class, () -> reports.stage(shop, run, "RUNNING", "READING_CARDS", ""));
        assertEquals("AUTHENTICATING", reports.find(run).orElseThrow().step());
        assertTrue(reports.events(run, null, 100).isEmpty());
    }

    @Test void catalogSaveAlsoWaitsBeforeItsOwnerReadAndPreservesTheCompletedSnapshot() throws Exception {
        var shop = shops.getFirst();
        var catalog = new com.vncode.app.features.gtinsync.GtinSyncRepository();
        var row = new com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin(
                "00000000000017", "Synthetic", "Blue", "M", true, true,
                "National Catalog@synthetic", java.time.Instant.now());
        try (var writer = Database.getConnection(); var tx = writer.createStatement();
             var executor = Executors.newSingleThreadExecutor()) {
            tx.execute("BEGIN IMMEDIATE");
            assertTrue(catalog.catalog(shop.getId(), shop.getMarketplace()).isEmpty());
            var entered = new CountDownLatch(1);
            var future = executor.submit(() -> {
                entered.countDown(); catalog.saveCatalog(shop.getId(), shop.getMarketplace(), List.of(row));
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> future.get(150, TimeUnit.MILLISECONDS));
            } finally { tx.execute("ROLLBACK"); }
            future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(List.of(row), catalog.catalog(shop.getId(), shop.getMarketplace()));
    }
}
