package com.vncode.app.ui.gs1;

import com.vncode.app.config.Database;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.I18nService;
import com.vncode.app.shared.ThemeService;
import com.vncode.app.ui.znack.ZnackAutomationController;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Layouts use synthetic identity text and do not contact GS1, Catalog or a marketplace. */
class Gs1ZnackHeadingLayoutTest {
    @TempDir Path root;

    @BeforeAll static void startFx() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }

    @Test void longIdentityAndStatusRemainReadableAtNarrowDesktopWidths() throws Exception {
        System.setProperty("vncode.appdata.dir", root.toString());
        Database.initDatabase();
        var previous = I18nService.getInstance().getCurrentLanguage();
        try {
            fx(() -> {
                for (var language : AppLanguage.values()) {
                    I18nService.getInstance().setLanguage(language);
                    checkGs1Heading();
                    checkZnackAccount();
                }
                return null;
            });
        } finally {
            fx(() -> { I18nService.getInstance().setLanguage(previous); return null; });
            com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
            System.clearProperty("vncode.appdata.dir");
        }
    }

    private static void checkGs1Heading() throws Exception {
        var pane = new Gs1Pane();
        var stage = new Stage();
        try {
            var shop = Gs1Pane.class.getDeclaredField("shop");
            shop.setAccessible(true);
            shop.set(pane, new Shop(1, "A long international clothing company name for the seller workspace", "fixture"));
            var inn = Gs1Pane.class.getDeclaredField("inn");
            inn.setAccessible(true);
            inn.set(pane, "7707083893");
            var render = Gs1Pane.class.getDeclaredMethod("render");
            render.setAccessible(true);
            render.invoke(pane);
            show(stage, pane, 520, 510);
            var identity = (Label) pane.lookup("#gs1ShopIdentity");
            var check = (Button) pane.lookup("#gs1CheckAllButton");
            assertNotNull(identity);
            assertNotNull(check);
            assertTrue(identity.getText().contains("7707083893"));
            assertTrue(identity.isWrapText());
            assertTrue(identity.getWidth() > 100);
            assertTrue(identity.getHeight() + 1 >= identity.prefHeight(identity.getWidth()), "Company name and INN must fit vertically");
            assertTrue(check.getWidth() + 1 >= check.prefWidth(-1), "The refresh button must show its full label");
            assertTrue(check.localToScene(check.getBoundsInLocal()).getMaxX() <= stage.getScene().getWidth() + 1);
        } finally { pane.dispose(); stage.close(); }
    }

    private static void checkZnackAccount() throws Exception {
        var loader = FxmlViewLoader.loader(ZnackAutomationController.class, "znack-automation-view.fxml");
        Parent view = FxmlViewLoader.load(loader);
        var controller = (ZnackAutomationController) loader.getController();
        var stage = new Stage();
        try {
            ((Label) loader.getNamespace().get("accountOwnerLabel")).setText("A long international clothing company name for the seller workspace");
            var status = (Label) loader.getNamespace().get("accountStatusLabel");
            status.setText("A long account status requiring verification before the seller proceeds");
            ((Label) loader.getNamespace().get("accountBalanceLabel")).setText("12 345 678 901,99 ₽");
            var information = (Tab) loader.getNamespace().get("informationTab");
            information.getTabPane().getSelectionModel().select(information);
            show(stage, view, 520, 510);
            assertTrue(status.isWrapText());
            assertTrue(status.getWidth() > 120, "The status must not collapse to a single-character column");
            assertTrue(status.getHeight() + 1 >= status.prefHeight(status.getWidth()), "The account status must fit vertically");
            assertTrue(((Label) loader.getNamespace().get("accountBalanceLabel")).isWrapText());
        } finally { controller.setShop(null); stage.close(); }
    }

    private static void show(Stage stage, Parent view, double width, double height) {
        var scene = new Scene(view, width, height);
        ThemeService.applyTheme(scene);
        stage.setScene(scene);
        stage.show();
        stage.setWidth(width);
        stage.setHeight(height);
        view.applyCss();
        view.layout();
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        var task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }
}
