package com.vncode.app.ui.ozon;

import com.vncode.app.config.Database;
import com.vncode.app.features.ozon.OzonFbsPage;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class OzonBulkUiTest {
    @TempDir Path root;
    private FXMLLoader loader;
    private OzonDashboardController controller;
    private Stage window;
    private final AtomicInteger remoteCalls = new AtomicInteger();
    private final AtomicReference<Throwable> dialogFailure = new AtomicReference<>();
    private final Shop shop = new Shop(1, "Bulk fixture", Marketplace.OZON, "11", "fixture");

    @BeforeAll static void startFx() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) {}
    }
    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", root.toString()); Database.initDatabase();
        try (var c = Database.getConnection(); var statement = c.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Bulk fixture','OZON','11','fixture'),(2,'Other fixture','OZON','22','other')");
        }
        fx(() -> {
            loader = FxmlViewLoader.loader(OzonDashboardController.class, "ozon-dashboard-view.fxml");
            loader.setControllerFactory(type -> new OzonDashboardController(new OzonSyncWorkflow(owner -> {
                remoteCalls.incrementAndGet(); throw new AssertionError("This UI test must not call Ozon");
            })));
            window = new Stage(); window.setScene(new Scene(loader.load(), 1024, 650)); window.show();
            controller = loader.getController(); return null;
        });
    }
    @AfterEach void cleanup() throws Exception {
        fx(() -> { controller.dispose(); window.close(); return null; });
        awaitWorkers();
        assertEquals(0, remoteCalls.get());
        assertEquals(0, ShopBatchRegistry.activeCount());
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");
    }

    @Test void fullFilteredSelectionIncludes1000OrdersAndSurvivesPagingWithoutRenderingThemAll() throws Exception {
        seed1000("awaiting_packaging", "order");
        new OzonPostingRepository().upsertDetail(2, posting("other-order", "awaiting_packaging"));
        fx(() -> { controller.setShop(shop, false); return null; });
        awaitFx(() -> table("newOrdersTable").getItems().size() == 100);
        fx(() -> {
            assertTrue(node("newOrdersTab", Tab.class).getText().contains("1000"));
            assertEquals(100, controller.currentPageLimit());
            node("selectFilteredButton", Button.class).fire(); return null;
        });
        awaitFx(() -> selected().size() == 1000 && !node("selectFilteredButton", Button.class).isDisabled());
        fx(() -> {
            assertFalse(selected().contains("other-order"));
            node("nextPageButton", Button.class).fire(); return null;
        });
        awaitFx(() -> node("pageStatusLabel", Label.class).getText().startsWith("101"));
        fx(() -> {
            assertEquals(100, table("newOrdersTable").getItems().size()); assertEquals(1000, selected().size());
            var checkbox = (CheckBox) node("newOrderSelectTC", TableColumn.class).getGraphic();
            assertTrue(checkbox.isSelected()); checkbox.fire();
            assertEquals(900, selected().size(), "Deselecting a page preserves the selected orders on other pages");
            node("clearBatchSelectionButton", Button.class).fire(); assertTrue(selected().isEmpty());
            node("postingSearch", TextField.class).setText("order-10"); return null;
        });
        awaitFx(() -> table("newOrdersTable").getItems().size() == 12 && node("newOrdersTab", Tab.class).getText().contains("12"));
        fx(() -> { node("selectFilteredButton", Button.class).fire(); return null; });
        awaitFx(() -> selected().size() == 12);
        fx(() -> {
            assertTrue(selected().stream().allMatch(number -> number.contains("order-10")));
            assertEquals(12, table("newOrdersTable").getItems().size()); return null;
        });
    }

    @Test void cancellingFullShipmentConfirmationStartsNoPackingOrPrintJob() throws Exception {
        seed1000("awaiting_packaging", "order"); seed1000("awaiting_deliver", "ready");
        fx(() -> { controller.setShop(shop, false); return null; });
        awaitFx(() -> table("newOrdersTable").getItems().size() == 100 && table("packingOrdersTable").getItems().size() == 100);
        fx(() -> { node("selectFilteredButton", Button.class).fire(); return null; });
        awaitFx(() -> selected().size() == 1000 && !node("moveToPackingButton", Button.class).isDisabled());
        fx(() -> {
            cancelDialog(() -> node("moveToPackingButton", Button.class).fire(), dialog -> {
                assertTrue(dialog.getHeaderText().contains("1000"));
                return dialog.getButtonTypes().stream().filter(type -> type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
            });
            assertEquals(1000, selected().size());
            assertFalse(node("loadingIndicator", ProgressIndicator.class).isVisible());
            node("orderStatusTabs", TabPane.class).getSelectionModel().select(node("packingOrdersTab", Tab.class));
            return null;
        });
        CountDownLatch cancelled = new CountDownLatch(1);
        fx(() -> {
            var timer = dialogCanceller(dialog -> {
                assertEquals(MessageFormat.format(I18nService.getInstance().tr("ozon.batch.confirm_print"), 1000), dialog.getContentText());
                cancelled.countDown(); return ButtonType.NO;
            });
            timer.play(); node("printAllButton", Button.class).fire(); return null;
        });
        boolean dialogCancelled = cancelled.await(5, TimeUnit.SECONDS);
        assertNoDialogFailure();
        assertTrue(dialogCancelled); awaitWorkers();
        fx(() -> { assertFalse(node("loadingIndicator", ProgressIndicator.class).isVisible()); return null; });
        try (var c = Database.getConnection(); var s = c.createStatement(); var row = s.executeQuery("SELECT COUNT(*) FROM ozon_exemplar_jobs")) {
            assertEquals(0, row.getInt(1));
        }
        try (var files = Files.walk(root)) { assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".pdf"))); }
        assertEquals(0, ShopBatchRegistry.activeCount());
    }

    @Test void slowBackgroundReadKeepsFxPulsingAndOnlyTheNewestSearchReachesTheTable() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), pulses = new CountDownLatch(4);
        AtomicBoolean first = new AtomicBoolean(true);
        var timer = new AnimationTimer() { @Override public void handle(long now) { pulses.countDown(); } };
        try {
            fx(() -> {
                controller.setFbsReader(query -> {
                    assertFalse(Platform.isFxApplicationThread());
                    if (first.compareAndSet(true, false)) {
                        entered.countDown();
                        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                    }
                    String marker = query.text().isEmpty() ? "old" : "filtered";
                    return new OzonFbsPage(List.of(posting(marker, query.status())), 1000, Map.of());
                });
                timer.start(); controller.setShop(shop, false); return null;
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(pulses.await(3, TimeUnit.SECONDS), "A blocked read worker must not block JavaFX frames");
            fx(() -> { node("postingSearch", TextField.class).setText("filtered"); return null; });
            awaitFx(() -> ((javafx.animation.PauseTransition) field("postingDebounce")).getStatus() == javafx.animation.Animation.Status.STOPPED);
            release.countDown();
            awaitFx(() -> !table("newOrdersTable").getItems().isEmpty()
                    && table("newOrdersTable").getItems().getFirst().postingNumber().equals("filtered"));
            fx(() -> { assertEquals(1, table("newOrdersTable").getItems().size()); return null; });
        } finally { release.countDown(); fx(() -> { timer.stop(); return null; }); }
    }

    private void seed1000(String status, String prefix) {
        var values = new ArrayList<OzonPostingDto>();
        for (int i = 1; i <= 1000; i++) values.add(posting(prefix + "-" + i, status));
        new OzonPostingRepository().upsertPage(1, values);
    }
    private OzonPostingDto posting(String number, String status) {
        return new OzonPostingDto(number, number, number, status, "", "fixture", "2026-10-13T12:00:00Z", "", "", "",
                new OzonRequirements(List.of(), List.of(), List.of()), List.of(), false,
                List.of(new OzonPostingItemDto(0, "1", "11", "fixture", "Fixture item", 1, "RUB", "100")));
    }
    private void cancelDialog(Runnable action, java.util.function.Function<DialogPane, ButtonType> choice) {
        var timer = dialogCanceller(choice); timer.play(); try { action.run(); } finally { timer.stop(); }
        assertNoDialogFailure();
    }
    private Timeline dialogCanceller(java.util.function.Function<DialogPane, ButtonType> choice) {
        var timer = new Timeline(); timer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        timer.getKeyFrames().add(new KeyFrame(Duration.millis(50), ignored -> {
            for (Window candidate : List.copyOf(Window.getWindows())) if (candidate instanceof Stage stage && stage.getOwner() == window && stage.isShowing()) {
                var rootNode = stage.getScene().getRoot(); var found = rootNode instanceof DialogPane ? rootNode : rootNode.lookup(".dialog-pane");
                if (found instanceof DialogPane dialog) {
                    try { var type = choice.apply(dialog); timer.stop(); ((Button) dialog.lookupButton(type)).fire(); }
                    catch (Throwable error) { dialogFailure.compareAndSet(null, error); timer.stop(); stage.close(); }
                    return;
                }
            }
        }));
        return timer;
    }
    private void assertNoDialogFailure() {
        Throwable failure = dialogFailure.get();
        if (failure != null) throw new AssertionError("Owned confirmation dialog failed validation", failure);
    }
    private <T> T node(String key, Class<T> type) { return type.cast(loader.getNamespace().get(key)); }
    @SuppressWarnings("unchecked") private TableView<OzonPostingDto> table(String key) { return (TableView<OzonPostingDto>) loader.getNamespace().get(key); }
    @SuppressWarnings("unchecked") private Set<String> selected() { return (Set<String>) field("selectedPostingNumbers"); }
    private Object field(String name) {
        try { var field = OzonDashboardController.class.getDeclaredField(name); field.setAccessible(true); return field.get(controller); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void awaitFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) { if (fx(condition::getAsBoolean)) return; Thread.sleep(10); }
        assertTrue(fx(condition::getAsBoolean), "Ozon workspace did not reach the expected state");
    }
    private static <T> T fx(Callable<T> operation) throws Exception {
        var task = new FutureTask<>(operation); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS);
    }
    private static void awaitWorkers() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && (AppTaskExecutor.hasRunningTasks()
                || ShopReadCoordinator.shared().activeCount() > 0 || ShopReadCoordinator.shared().pendingCount() > 0)) Thread.sleep(10);
        assertFalse(AppTaskExecutor.hasRunningTasks()); assertEquals(0, ShopReadCoordinator.shared().activeCount());
        assertEquals(0, ShopReadCoordinator.shared().pendingCount()); fx(() -> null);
    }
}
