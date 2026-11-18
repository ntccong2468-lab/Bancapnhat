package com.vncode.app.ui.ozon;

import static org.junit.jupiter.api.Assertions.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.FxmlViewLoader;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonKizOnlyUiTest {
    @TempDir Path directory;
    @BeforeAll static void start() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException running) { } }

    @Test void localFallbackIsAvailableOnNewAndPrintedTabsAndReportsAllPagesWithoutPreparingCodes() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString()); Database.initDatabase();
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic','OZON','client','fixture')");
        }
        var shop = new Shop(1, "Synthetic", Marketplace.OZON, "client", "fixture");
        var data = new ArrayList<OzonPostingDto>();
        for (int i = 0; i < 142; i++) data.add(posting(String.format("POST-%03d", i)));
        new OzonPostingRepository().upsertPage(1, data);
        var loader = fx(() -> { var f = FxmlViewLoader.loader(OzonDashboardController.class, "ozon-dashboard-view.fxml"); new Scene(f.load(), 1100, 700); return f; });
        OzonDashboardController controller = loader.getController();
        Button button = (Button) loader.getNamespace().get("printKizOnlyButton");
        TabPane tabs = (TabPane) loader.getNamespace().get("orderStatusTabs");
        try {
            fx(() -> { controller.setShop(shop, false); return null; });
            await(() -> fx(() -> ((TableView<?>) loader.getNamespace().get("packingOrdersTable")).getItems().size() == 100));
            fx(() -> {
                assertFalse(button.isVisible(), "Do not offer a print-only fallback in the receiving tab");
                tabs.getSelectionModel().select((Tab) loader.getNamespace().get("packingOrdersTab")); return null;
            });
            await(() -> fx(() -> button.isVisible() && !button.isDisabled()));
            fx(() -> { assertNotNull(button.getOnAction()); assertFalse(button.getTooltip().getText().isBlank()); button.fire(); return null; });
            await(() -> fx(() -> ((TextArea) loader.getNamespace().get("batchResultsArea")).getText().contains("POST-141")));
            fx(() -> {
                assertSame(loader.getNamespace().get("packingOrdersTab"), tabs.getSelectionModel().getSelectedItem());
                String report = ((TextArea) loader.getNamespace().get("batchResultsArea")).getText();
                assertTrue(report.contains("POST-000")); assertTrue(report.contains("POST-141"));
                tabs.getSelectionModel().select((Tab) loader.getNamespace().get("printedOrdersTab")); assertTrue(button.isVisible());
                tabs.getSelectionModel().select((Tab) loader.getNamespace().get("deliveringOrdersTab")); assertFalse(button.isVisible());
                return null;
            });
            try (var c = Database.getConnection(); var s = c.createStatement()) {
                for (String table : List.of("kiz_orders", "ozon_exemplar_jobs", "ozon_action_log", "ozon_fbs_print_receipts"))
                    try (var r = s.executeQuery("SELECT count(*) FROM " + table)) { assertEquals(0, r.getInt(1), table); }
            }
        } finally {
            fx(() -> { controller.dispose(); return null; });
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle(); System.clearProperty("vncode.appdata.dir");
        }
    }

    @Test void finishingAnExportAfterChangingShopClearsTheOriginalBusyFlag() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString()); Database.initDatabase();
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'First','OZON','client','fixture'),(2,'Second','OZON','other','other')");
        }
        var first = new Shop(1, "First", Marketplace.OZON, "client", "fixture");
        var second = new Shop(2, "Second", Marketplace.OZON, "other", "other");
        var loader = fx(() -> { var f = FxmlViewLoader.loader(OzonDashboardController.class, "ozon-dashboard-view.fxml"); new Scene(f.load(), 1100, 700); return f; });
        OzonDashboardController controller = loader.getController();
        var busy = new ConcurrentHashMap<Integer, Boolean>();
        var task = new javafx.concurrent.Task<Void>() { @Override protected Void call() { return null; } };
        com.vncode.app.shared.ShopBatchRegistry.Lease lease = null;
        try {
            fx(() -> { controller.setOnBusy(busy::put); controller.setShop(first, false); return null; });
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
            lease = com.vncode.app.shared.ShopBatchRegistry.claim(first, "ozon-kiz-only", List.of(directory.resolve("late.pdf"))).orElseThrow();
            fx(() -> {
                var begin = OzonDashboardController.class.getDeclaredMethod("beginKizOnlyTask", javafx.concurrent.Task.class); begin.setAccessible(true); begin.invoke(controller, task);
                var cancellation = OzonDashboardController.class.getDeclaredField("activeKizOnlyCancellation"); cancellation.setAccessible(true);
                cancellation.set(controller, new OzonKizOnlyPrintService.Cancellation());
                assertEquals(Boolean.TRUE, busy.get(1)); controller.setShop(second, false);
                assertEquals(Boolean.TRUE, busy.get(1), "The original output still owns its lease while the store changes");
                assertFalse(((Button) loader.getNamespace().get("cancelSelectionButton")).isDisabled(), "A different shop must retain usable normal-selection cancellation");
                return null;
            });
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
            var held = lease;
            fx(() -> { controller.finishKizOnlyExport(first, held, task); assertEquals(Boolean.FALSE, busy.get(1)); return null; });
            assertFalse(com.vncode.app.shared.ShopBatchRegistry.hasShop(1));
        } finally {
            if (lease != null) lease.close();
            fx(() -> { controller.dispose(); return null; });
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle(); System.clearProperty("vncode.appdata.dir");
        }
    }

    @Test void returningToAnExportingShopUnlocksItsControlsWhenTheOldExportFinishes() throws Exception {
        finishExportAfterReturningToItsShop(false, false);
    }

    @Test void anOldExportCannotUnlockANewerLocalOperationAfterReturningToItsShop() throws Exception {
        finishExportAfterReturningToItsShop(true, false);
    }

    @Test void returningToAnExportingShopStaysLockedWhileAnotherBatchStillOwnsIt() throws Exception {
        finishExportAfterReturningToItsShop(false, true);
    }

    private void finishExportAfterReturningToItsShop(boolean newerOperation, boolean anotherBatch) throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString()); Database.initDatabase();
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'First','OZON','client','fixture'),(2,'Second','OZON','other','other')");
        }
        var first = new Shop(1, "First", Marketplace.OZON, "client", "fixture");
        var second = new Shop(2, "Second", Marketplace.OZON, "other", "other");
        var loader = fx(() -> { var f = FxmlViewLoader.loader(OzonDashboardController.class, "ozon-dashboard-view.fxml"); new Scene(f.load(), 1100, 700); return f; });
        OzonDashboardController controller = loader.getController();
        var busy = new ConcurrentHashMap<Integer, Boolean>();
        var oldTask = new javafx.concurrent.Task<Void>() { @Override protected Void call() { return null; } };
        com.vncode.app.shared.ShopBatchRegistry.Lease lease = null, otherLease = null;
        try {
            fx(() -> { controller.setOnBusy(busy::put); controller.setShop(first, false); return null; });
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
            lease = com.vncode.app.shared.ShopBatchRegistry.claim(first, "ozon-kiz-only", List.of(directory.resolve("returning.pdf"))).orElseThrow();
            fx(() -> {
                var begin = OzonDashboardController.class.getDeclaredMethod("beginKizOnlyTask", javafx.concurrent.Task.class); begin.setAccessible(true); begin.invoke(controller, oldTask);
                var cancellation = OzonDashboardController.class.getDeclaredField("activeKizOnlyCancellation"); cancellation.setAccessible(true);
                cancellation.set(controller, new OzonKizOnlyPrintService.Cancellation());
                controller.setShop(second, false); controller.setShop(first, false);
                assertTrue(((Button) loader.getNamespace().get("refreshButton")).isDisabled());
                assertTrue(((TableView<?>) loader.getNamespace().get("packingOrdersTable")).isDisabled());
                return null;
            });
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
            if (anotherBatch) otherLease = com.vncode.app.shared.ShopBatchRegistry.claim(first, "ozon-print", List.of(directory.resolve("newer.pdf"))).orElseThrow();
            if (newerOperation) fx(() -> {
                var begin = OzonDashboardController.class.getDeclaredMethod("beginKizOnlyTask", javafx.concurrent.Task.class); begin.setAccessible(true);
                begin.invoke(controller, new javafx.concurrent.Task<Void>() { @Override protected Void call() { return null; } });
                return null;
            });
            var held = lease;
            fx(() -> {
                controller.finishKizOnlyExport(first, held, oldTask);
                boolean remainsBusy = newerOperation || anotherBatch;
                assertEquals(remainsBusy, ((Button) loader.getNamespace().get("refreshButton")).isDisabled(), "Only the inherited wait for this completed lease may unlock the current shop");
                assertEquals(remainsBusy, ((TableView<?>) loader.getNamespace().get("packingOrdersTable")).isDisabled());
                assertEquals(remainsBusy, busy.get(1));
                return null;
            });
        } finally {
            if (lease != null) lease.close(); if (otherLease != null) otherLease.close();
            fx(() -> { controller.dispose(); return null; });
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle(); System.clearProperty("vncode.appdata.dir");
        }
    }

    private static OzonPostingDto posting(String number) {
        return new OzonPostingDto(number, number, number, "awaiting_deliver", "", "", "", "2026-11-17T08:00:00Z", "", "",
                new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false,
                List.of(new OzonPostingItemDto(0, "1001", "SKU", "article", "Synthetic", 1, "RUB", "1")));
    }
    private static <T> T fx(Callable<T> action) throws Exception {
        var future = new FutureTask<T>(action); Platform.runLater(future);
        try { return future.get(10, TimeUnit.SECONDS); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }
    private static void await(Callable<Boolean> ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) { if (ready.call()) return; Thread.sleep(20); }
        fail("KIZ-only UI did not settle");
    }
}
