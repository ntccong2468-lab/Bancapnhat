package com.vncode.app.ui.finance;

import com.vncode.app.config.Database;
import com.vncode.app.features.finance.AnalyticsDatabase;
import com.vncode.app.features.finance.FinancePenaltySyncStore;
import com.vncode.app.features.finance.FinanceRawRow;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FinancePenaltyUiTest {
    @TempDir Path data;
    @BeforeAll static void start() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException alreadyStarted) { } }
    static <T> T fx(Callable<T> work) throws Exception { var task = new FutureTask<>(work); Platform.runLater(task); return task.get(15, TimeUnit.SECONDS); }
    static void await(Callable<Boolean> condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!fx(condition) && System.nanoTime() < until) Thread.sleep(25);
        assertTrue(fx(condition), "Timed out waiting for finance data");
    }
    static void pulse() throws Exception {
        var done = new java.util.concurrent.CompletableFuture<Void>();
        fx(() -> { new javafx.animation.AnimationTimer() { int frames; @Override public void handle(long now) {
            if (++frames == 2) { stop(); done.complete(null); }
        }}.start(); return null; });
        done.get(5, TimeUnit.SECONDS);
    }

    @Test void penaltyRowsStayScopedAndUsableAcrossPagesThemesAndWindowSizes() throws Exception {
        System.setProperty("vncode.appdata.dir", data.toString());
        Database.initDatabase();
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture WB','WILDBERRIES',NULL,'fixture'),(2,'Fixture Ozon','OZON','123','fixture')");
        }
        AnalyticsDatabase.initialize();
        try (var connection = AnalyticsDatabase.getConnection(); var p = connection.prepareStatement("""
                INSERT INTO finance_raw(shop_id,rrd_id,business_date,currency,operation_name,order_id,vendor_code,sku,penalty_cost,raw_json,synced_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            for (int i=0; i<56; i++) {
                int shop = i == 55 ? 2 : 1;
                p.setInt(1,shop); p.setString(2,"fixture-"+i); p.setString(3,LocalDate.now().toString());
                p.setString(4,"RUB"); p.setString(5,i == 54 ? null : "Marketplace operation");
                p.setString(6,"posting-"+i); p.setString(7,"article-"+i); p.setString(8,"sku-"+i);
                p.setDouble(9,i == 54 ? -75 : 20); p.setString(10,"{}"); p.setString(11,"fixture"); p.addBatch();
            }
            p.executeBatch();
        }
        try (var connection = AnalyticsDatabase.getConnection(); var p = connection.prepareStatement("""
                INSERT INTO finance_sync_state(shop_id,stream_name,api_family,phase,status,cursor,last_success_at,last_error,updated_at)
                VALUES(?,?,?,?,?,'0',?,?,?)
                """)) {
            p.setInt(1,1); p.setString(2,"WB_RECENT"); p.setString(3,"FINANCE"); p.setString(4,"PERIODIC_3"); p.setString(5,"IDLE");
            p.setString(6,java.time.Instant.now().minus(java.time.Duration.ofDays(3)).toString()); p.setString(7,null); p.setString(8,"fixture"); p.executeUpdate();
            p.setInt(1,2); p.setString(2,"OZON_RECENT"); p.setString(3,"OZON_FINANCE"); p.setString(4,"PERIODIC_3"); p.setString(5,"ERROR");
            p.setString(6,java.time.Instant.now().minus(java.time.Duration.ofHours(1)).toString()); p.setString(7,"fixture-error-private-marker"); p.setString(8,"fixture"); p.executeUpdate();
        }
        var previousLanguage = I18nService.getInstance().getCurrentLanguage();
        var previousTheme = ThemeService.getCurrentTheme();
        var loader = fx(() -> { I18nService.getInstance().setLanguage(AppLanguage.VI); return FxmlViewLoader.loader(FinanceDashboardController.class, "finance-dashboard-view.fxml"); });
        Parent view = fx(loader::load);
        FinanceDashboardController controller = loader.getController();
        FinancePenaltyController penaltyController = (FinancePenaltyController) loader.getNamespace().get("penaltyReportController");
        var tabs = (javafx.scene.control.TabPane) loader.getNamespace().get("reportTabs");
        var penalties = (javafx.scene.control.Tab) loader.getNamespace().get("penaltiesTab");
        var summary = (javafx.scene.control.Tab) loader.getNamespace().get("summaryTab");
        fx(() -> {
            assertEquals(summary,tabs.getSelectionModel().getSelectedItem());
            Parent overview=FxmlViewLoader.loader(com.vncode.app.ui.dashboard.DashboardController.class,"dashboard-view.fxml").load();
            assertNull(overview.lookup("#penaltyTable"),"Penalty report moved out of Overview");
            assertNotNull(loader.getNamespace().get("dailyTable"),"Original financial summary remains available");
            tabs.getSelectionModel().select(penalties);
            return null;
        });
        Stage stage = fx(() -> { var s = new Stage(); s.setScene(new Scene(view, 940, 510)); ThemeService.applyTheme(s.getScene()); s.show(); return s; });
        try {
            fx(() -> { controller.setShop(new Shop(1,"Fixture WB","fixture")); return null; });
            await(() -> ((javafx.scene.control.Label)view.lookup("#penaltyStatusLabel")).getText().contains("Chưa xác minh"));
            assertTrue(fx(() -> ((TableView<?>)view.lookup("#penaltyTable")).getItems().isEmpty()), "Unscoped legacy entries must remain hidden");
            assertTrue(fx(() -> ((javafx.scene.control.Label)((TableView<?>)view.lookup("#penaltyTable")).getPlaceholder()).getText()).contains("Chưa xác minh"));
            assertEquals("0 / 0", fx(() -> ((javafx.scene.control.Label)view.lookup("#penaltyPageLabel")).getText()));
            assertFalse(fx(() -> ((Button)view.lookup("#penaltySyncButton")).isDisabled()), "Seller can still request a sync");
            var verified = new java.util.ArrayList<FinanceRawRow>();
            for (int i = 0; i < 55; i++) verified.add(new FinanceRawRow("verified-"+i,"report",LocalDate.now().toString(),"RUB",
                    "document",i == 54 ? null : "Marketplace operation","order-"+i,"product","article","sku",
                    1,false,0,0,0,0,0,0,0,i == 54 ? -75 : 20,0,0,0,0,"{}"));
            var wb = new Shop(1,"Fixture WB","fixture");
            var store = new FinancePenaltySyncStore();
            var now = java.time.Instant.now();
            var queued = store.request(wb, LocalDate.now().minusDays(29), LocalDate.now(), now);
            var attempt = store.begin(wb, queued, now, now);
            assertNotNull(attempt);
            assertTrue(store.commitPage(wb, attempt, verified, "1", true, now));
            fx(() -> { controller.refresh(); return null; });
            await(() -> ((TableView<?>)view.lookup("#penaltyTable")).getItems().size() == 50);
            assertTrue(fx(() -> ((javafx.scene.control.Label)view.lookup("#penaltyStatusLabel")).getText()).contains("Đã đọc hết"));
            fx(() -> { ((Button)view.lookup("#penaltyNextButton")).fire(); return null; });
            await(() -> ((TableView<?>)view.lookup("#penaltyTable")).getItems().size() == 5);
            assertEquals("51–55 / 55", fx(() -> ((javafx.scene.control.Label)view.lookup("#penaltyPageLabel")).getText()));
            fx(() -> { ((javafx.scene.control.DatePicker)view.lookup("#penaltyFromDate")).setValue(LocalDate.now().minusDays(28)); return null; });
            await(() -> ((javafx.scene.control.Label)view.lookup("#penaltyStatusLabel")).getText().contains("Chưa xác minh"));
            assertTrue(fx(() -> ((TableView<?>)view.lookup("#penaltyTable")).getItems().isEmpty()), "A different date range must not inherit rows");
            fx(() -> { ((javafx.scene.control.DatePicker)view.lookup("#penaltyFromDate")).setValue(LocalDate.now().minusDays(29)); return null; });
            await(() -> ((TableView<?>)view.lookup("#penaltyTable")).getItems().size() == 50);
            try (var c = Database.getConnection(); var s = c.createStatement()) { s.execute("UPDATE shops SET api_key='new-fixture' WHERE id=1"); }
            fx(() -> { controller.setShop(new Shop(1,"Fixture WB","new-fixture")); return null; });
            await(() -> ((javafx.scene.control.Label)view.lookup("#penaltyStatusLabel")).getText().contains("Chưa xác minh"));
            assertTrue(fx(() -> ((TableView<?>)view.lookup("#penaltyTable")).getItems().isEmpty()), "A new key at the same shop ID must not inherit entries");
            var toPicker = fx(() -> (javafx.scene.control.DatePicker)view.lookup("#penaltyToDate"));
            assertEquals(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")), fx(() -> toPicker.getConverter().toString(toPicker.getValue())));
            assertNull(fx(() -> toPicker.getConverter().fromString("31/02/2026")), "Invalid dates must not silently roll forward");
            fx(() -> { controller.setShop(new Shop(2,"Fixture Ozon",com.vncode.app.integration.marketplace.Marketplace.OZON,"123","fixture")); return null; });
            await(() -> ((javafx.scene.control.Label)view.lookup("#penaltyStatusLabel")).getText().contains("Chưa hỗ trợ"));
            assertTrue(fx(() -> ((TableView<?>)view.lookup("#penaltyTable")).getItems().isEmpty()), "Do not show legacy rows after switching shop");
            String syncStatus = fx(() -> ((javafx.scene.control.Label)view.lookup("#penaltyStatusLabel")).getText());
            assertTrue(syncStatus.contains("Chưa hỗ trợ"), syncStatus);
            assertFalse(syncStatus.contains("private-marker"), "Do not display raw marketplace errors");
            assertTrue(fx(() -> ((javafx.scene.control.Label)view.lookup("#penaltyHelpLabel")).getText()).contains("Ozon"));
            for (String theme : List.of("dark", "light")) for (AppLanguage language : AppLanguage.values()) for (int[] size : List.of(new int[]{940,510}, new int[]{1000,650}, new int[]{1360,820})) {
                fx(() -> {
                    ThemeService.switchTheme(theme); ThemeService.applyTheme(stage.getScene());
                    I18nService.getInstance().setLanguage(language); controller.applyTranslations();
                    stage.setWidth(size[0]); stage.setHeight(size[1]); view.applyCss(); view.layout();
                    return null;
                });
                pulse();
                fx(() -> {
                    view.applyCss(); view.layout();
                    var table = (TableView<?>)view.lookup("#penaltyTable");
                    var footer = view.lookup("#penaltyNextButton");
                    var tableBounds = table.localToScene(table.getBoundsInLocal());
                    var footerBounds = footer.localToScene(footer.getBoundsInLocal());
                    assertTrue(tableBounds.getHeight() >= 100, "Penalty list must remain usable");
                    assertTrue(footerBounds.getMaxY() <= stage.getScene().getHeight()+1, language+" "+theme+" footer clipped");
                    assertTrue(footerBounds.getMaxX() <= stage.getScene().getWidth()+1, language+" "+theme+" footer clipped horizontally");
                    for (String labelId : List.of("#penaltyFromLabel", "#penaltyToLabel")) {
                        var label = (javafx.scene.control.Label)view.lookup(labelId);
                        assertTrue(label.getWidth()+0.5 >= label.prefWidth(-1), language+" "+theme+" date label clipped: "+labelId);
                    }
                    for (String pickerId : List.of("#penaltyFromDate", "#penaltyToDate")) {
                        var picker = (javafx.scene.control.DatePicker)view.lookup(pickerId);
                        var editor = picker.getEditor();
                        var text = new javafx.scene.text.Text(editor.getText()); text.setFont(editor.getFont());
                        var padding = editor.getInsets();
                        assertTrue(editor.getWidth()+0.5 >= text.getLayoutBounds().getWidth()+padding.getLeft()+padding.getRight(),
                                language+" "+theme+" date clipped: "+pickerId);
                    }
                    capture(stage.getScene(),theme,language,size);
                    return null;
                });
            }
            var newWb = new Shop(1,"Fixture WB","new-fixture");
            store.request(newWb, LocalDate.now().minusDays(29), LocalDate.now(), java.time.Instant.now());
            fx(() -> { I18nService.getInstance().setLanguage(AppLanguage.VI); controller.applyTranslations(); controller.setShop(newWb); return null; });
            await(() -> ((javafx.scene.control.Label)view.lookup("#penaltyStatusLabel")).getText().contains("Đã yêu cầu"));
            var field = FinancePenaltyController.class.getDeclaredField("penaltyPoll"); field.setAccessible(true);
            fx(() -> { tabs.getSelectionModel().select(summary); return null; });
            assertEquals(javafx.animation.Animation.Status.STOPPED,fx(() -> ((javafx.animation.PauseTransition)field.get(penaltyController)).getStatus()),"Hidden finance tab stops polling");
            fx(() -> { tabs.getSelectionModel().select(penalties); return null; });
            await(() -> ((javafx.scene.control.Label)view.lookup("#penaltyStatusLabel")).getText().contains("Đã yêu cầu"));
            fx(() -> { controller.dispose(); return null; });
            assertEquals(javafx.animation.Animation.Status.STOPPED, fx(() -> ((javafx.animation.PauseTransition)field.get(penaltyController)).getStatus()), "Closing workspace must stop polling");
        } finally {
            fx(() -> { controller.dispose(); stage.close(); I18nService.getInstance().setLanguage(previousLanguage); ThemeService.setCurrentTheme(previousTheme); return null; });
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
            com.vncode.app.testsupport.FinanceTestCleanup.detachAndAwait();
            System.clearProperty("vncode.appdata.dir");
        }
    }

    private static void capture(Scene scene, String theme, AppLanguage language, int[] size) throws Exception {
        String dir = System.getProperty("vncode.finance.penalty.capture.dir");
        if (dir == null || language != AppLanguage.VI) return;
        var image = scene.snapshot(null);
        var png = new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.getPixelReader().getPixels(0,0,(int)image.getWidth(),(int)image.getHeight(),javafx.scene.image.PixelFormat.getIntArgbInstance(),((java.awt.image.DataBufferInt)png.getRaster().getDataBuffer()).getData(),0,(int)image.getWidth());
        Files.createDirectories(Path.of(dir));
        javax.imageio.ImageIO.write(png,"png",Path.of(dir,"finance-penalty-"+theme+"-"+size[0]+"x"+size[1]+".png").toFile());
    }
}
