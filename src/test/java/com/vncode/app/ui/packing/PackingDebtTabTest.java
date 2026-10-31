package com.vncode.app.ui.packing;

import com.vncode.app.integration.wb.WbDebtShipmentService;
import com.vncode.app.models.Order;
import com.vncode.app.shared.FxmlViewLoader;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackingDebtTabTest {
    @BeforeAll static void toolkit() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException alreadyStarted) {} }
    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); Platform.runLater(task); return task.get(15,TimeUnit.SECONDS);
    }

    @Test void opensOnlyOrdersReturnedForSelectedShipmentAndKeepsPrintColumnAtRight() throws Exception {
        fx(() -> {
            FXMLLoader loader=FxmlViewLoader.loader(PackingController.class,"packing-view.fxml"); FxmlViewLoader.load(loader);
            PackingController controller=loader.getController();
            try {
                TabPane tabs=(TabPane)loader.getNamespace().get("packingTabPane");
                Tab debt=(Tab)loader.getNamespace().get("debtTab");
                assertSame(debt,tabs.getTabs().get(tabs.getTabs().indexOf(loader.getNamespace().get("dispatchTab"))+1));
                @SuppressWarnings("unchecked") TableView<Order> table=(TableView<Order>)loader.getNamespace().get("debtOrdersTable");
                Order selected=new Order(); selected.setId(123L); selected.setWbStatus("waiting"); selected.setSupplierStatus("complete");
                Order other=new Order(); other.setId(456L);
                var shipment=new WbDebtShipmentService.Shipment("S-1","shipment","2026-10-30T12:00:00Z",List.of(selected));
                var show=PackingController.class.getDeclaredMethod("showDebtOrders",WbDebtShipmentService.Shipment.class); show.setAccessible(true); show.invoke(controller,shipment);
                assertEquals(List.of(selected),table.getItems()); assertFalse(table.getItems().contains(other));
                @SuppressWarnings("unchecked") TableColumn<Order,String> status=(TableColumn<Order,String>)loader.getNamespace().get("debtOrderStatusTC");
                String text=status.getCellData(selected);
                assertTrue(text.contains(com.vncode.app.shared.I18nService.getInstance().tr("packing.debt.wb.waiting")));
                assertTrue(text.contains(com.vncode.app.shared.I18nService.getInstance().tr("packing.debt.supplier.complete")));
                assertSame(loader.getNamespace().get("debtPrintTC"),table.getColumns().getLast());
                var back=PackingController.class.getDeclaredMethod("onDebtBack");back.setAccessible(true);back.invoke(controller);
                assertFalse(table.isVisible());assertTrue(((TableView<?>)loader.getNamespace().get("debtShipmentsTable")).isVisible());
            } finally { controller.dispose(); }
            return null;
        });
    }
}
