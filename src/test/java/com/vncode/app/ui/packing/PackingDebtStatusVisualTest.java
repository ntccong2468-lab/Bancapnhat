package com.vncode.app.ui.packing;

import com.vncode.app.integration.wb.WbDebtShipmentService;
import com.vncode.app.config.Database;
import com.vncode.app.models.Order;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PackingDebtStatusVisualTest {
    @TempDir Path appData;
    @BeforeAll static void toolkit() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    @Test void shipmentAndOrderBadgesRenderActualStatusColours() throws Exception {
        System.setProperty("vncode.appdata.dir", appData.toString());
        Database.initDatabase();
        try {
        fx(() -> {
            AppLanguage previousLanguage = I18nService.getInstance().getCurrentLanguage();
            I18nService.getInstance().setLanguage(AppLanguage.VI);
            FXMLLoader loader = FxmlViewLoader.loader(PackingController.class, "packing-view.fxml");
            Parent root = FxmlViewLoader.load(loader);
            PackingController controller = loader.getController();
            Stage stage = new Stage();
            try {
                Scene scene = new Scene(root, 1280, 900);
                scene.getStylesheets().add(PackingController.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());
                scene.getStylesheets().add(PackingController.class.getResource("/com/vncode/app/styles/reference-dark.css").toExternalForm());
                root.getStyleClass().add("reference-dark");
                stage.setScene(scene);
                stage.show();
                TabPane tabs = (TabPane) loader.getNamespace().get("packingTabPane");
                tabs.getSelectionModel().select((Tab) loader.getNamespace().get("debtTab"));

                Order waiting = new Order(); waiting.setId(123L); waiting.setName("Waiting order"); waiting.setWbStatus("waiting"); waiting.setSupplierStatus("confirm");
                Order unknown = new Order(); unknown.setId(456L); unknown.setName("Unknown order");
                var shipment = new WbDebtShipmentService.Shipment("S-1", "Shipment", "2026-10-30T12:00:00Z", List.of(waiting, unknown));
                @SuppressWarnings("unchecked") var shipments = (TableView<WbDebtShipmentService.Shipment>) loader.getNamespace().get("debtShipmentsTable");
                @SuppressWarnings("unchecked") var shipmentStatus = (TableColumn<WbDebtShipmentService.Shipment, String>) loader.getNamespace().get("debtShipmentStatusTC");
                shipments.getItems().setAll(shipment);
                root.applyCss(); root.layout();
                Label shipmentBadge = badge(shipments, shipmentStatus, 0);
                assertEquals(Color.web("#4b3020"), shipmentBadge.getBackground().getFills().getFirst().getFill());
                assertEquals(Color.web("#ffad70"), shipmentBadge.getTextFill());
                assertFalse(shipmentBadge.getText().isBlank());
                save(root, appData.resolve("vncode-debt-shipment-preview.png"));

                var show = PackingController.class.getDeclaredMethod("showDebtOrders", WbDebtShipmentService.Shipment.class);
                show.setAccessible(true); show.invoke(controller, shipment);
                @SuppressWarnings("unchecked") var orders = (TableView<Order>) loader.getNamespace().get("debtOrdersTable");
                @SuppressWarnings("unchecked") var orderStatus = (TableColumn<Order, String>) loader.getNamespace().get("debtOrderStatusTC");
                root.applyCss(); root.layout();
                Label waitingBadge = badge(orders, orderStatus, 0);
                assertEquals(Color.web("#4b3020"), waitingBadge.getBackground().getFills().getFirst().getFill());
                assertEquals(Color.web("#ffad70"), waitingBadge.getTextFill());
                assertTrue(waitingBadge.getText().contains("WB:"));
                assertTrue(waitingBadge.getText().contains("Seller:"));
                Label unknownBadge = badge(orders, orderStatus, 1);
                assertEquals(Color.web("#292f39"), unknownBadge.getBackground().getFills().getFirst().getFill());
                assertEquals(Color.web("#cbd2dd"), unknownBadge.getTextFill());
                assertFalse(unknownBadge.getText().isBlank());
                save(root, appData.resolve("vncode-debt-orders-preview.png"));
                for (AppLanguage language : AppLanguage.values()) {
                    I18nService.getInstance().setLanguage(language);
                    for (int[] size : List.of(new int[]{1280, 900}, new int[]{940, 650})) {
                        stage.setWidth(size[0]); stage.setHeight(size[1]);
                        orders.refresh(); root.applyCss(); root.layout();
                        Label badge = badge(orders, orderStatus, 0);
                        assertTrue(badge.getText().contains("WB:"), language.toString());
                        assertTrue(badge.getText().contains("Seller:"), language.toString());
                        Text rendered = (Text) badge.lookup(".text");
                        assertNotNull(rendered, "Text must be rendered");
                        assertFalse(rendered.getText().contains("…"), language + " at " + size[0]);
                        assertTrue(rendered.getBoundsInParent().getMaxY() <= badge.getHeight() + 1,
                                language + " status is clipped vertically at " + size[0]);
                    }
                }
            } finally {
                controller.dispose();
                stage.close();
                I18nService.getInstance().setLanguage(previousLanguage);
            }
            return null;
        });
        } finally {
            System.clearProperty("vncode.appdata.dir");
        }
    }

    private static Label badge(TableView<?> table, TableColumn<?, ?> column, int index) {
        return table.lookupAll(".table-cell").stream()
                .filter(TableCell.class::isInstance).map(TableCell.class::cast)
                .filter(cell -> cell.getTableColumn() == column && cell.getIndex() == index)
                .map(TableCell::getGraphic).filter(Label.class::isInstance).map(Label.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("Status badge is not rendered for row " + index));
    }

    private static void save(Parent root, Path file) throws Exception {
        WritableImage image = root.snapshot(null, null);
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
        result.setRGB(0, 0, width, height, pixels, 0, width);
        assertTrue(ImageIO.write(result, "png", file.toFile()), "PNG screenshot writer must be available");
    }
}
