package com.vncode.app.ui.packing;

import com.vncode.app.config.Database;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FbsResponsiveActionsTest {
    @TempDir Path root;

    @BeforeAll static void toolkit() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }
    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    @Test void selectionAndFilterActionsStayReadableInNarrowWorkspaces() throws Exception {
        System.setProperty("vncode.appdata.dir", root.toString());
        Database.initDatabase();
        var language = I18nService.getInstance().getCurrentLanguage();
        try {
            for (AppLanguage locale : AppLanguage.values()) {
                for (String page : new String[]{"packing/packing-view.fxml", "ozon/ozon-dashboard-view.fxml"}) {
                    FXMLLoader loader = fx(() -> {
                        I18nService.getInstance().setLanguage(locale);
                        var result = FxmlViewLoader.loader(com.vncode.app.MainApplication.class,
                                "/com/vncode/app/ui/" + page);
                        Parent view = result.load();
                        var scene = new Scene(view, 550, 560);
                        scene.getStylesheets().add(com.vncode.app.MainApplication.class
                                .getResource("/com/vncode/app/styles/theme.css").toExternalForm());
                        var stage = new Stage();stage.setScene(scene);stage.show();
                        stage.setWidth(550);stage.setHeight(560);
                        if (page.startsWith("packing/")) {
                            var bar = (Pane) result.getNamespace().get("selectionActionBar");
                            bar.setVisible(true);bar.setManaged(true);
                            ((Label) result.getNamespace().get("selectedCountLabel")).setText("100 000");
                            var orders=(TableView<?>) result.getNamespace().get("newOrdersTable");
                            assertEquals(I18nService.getInstance().tr("packing.empty"),((Label)orders.getPlaceholder()).getText());
                        }
                        return result;
                    });
                    try {
                        fx(() -> {
                            var view = loader.getRoot();
                            ((Parent) view).applyCss();((Parent) view).layout();
                            var scene = ((Parent) view).getScene();
                            String[] ids = page.startsWith("packing/")
                                    ? new String[]{"newShipmentButton", "addToShipmentButton"}
                                    : new String[]{"selectFilteredButton", "clearBatchSelectionButton"};
                            for (String id : ids) {
                                var button = (Button) loader.getNamespace().get(id);
                                var bounds = button.localToScene(button.getBoundsInLocal());
                                String detail = page + " " + locale + " " + id;
                                assertTrue(bounds.getMinX() >= -1 && bounds.getMaxX() <= scene.getWidth() + 1, detail);
                                assertTrue(button.getWidth() + 1 >= button.prefWidth(-1), detail + " is clipping its label");
                            }
                            return null;
                        });
                    } finally {
                        fx(() -> {
                            var controller = loader.getController();
                            if (controller != null) try { controller.getClass().getMethod("dispose").invoke(controller); }
                            catch (NoSuchMethodException ignored) { }
                            ((Stage) ((Parent) loader.getRoot()).getScene().getWindow()).close();
                            return null;
                        });
                        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
                    }
                }
            }
        } finally {
            fx(() -> { I18nService.getInstance().setLanguage(language); return null; });
            System.clearProperty("vncode.appdata.dir");
        }
    }
}
