package com.vncode.app.ui.packing;

import com.vncode.app.config.Database;
import com.vncode.app.integration.wb.WbSupplySummary;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic table only; the supplied callback never calls a seller API. */
class PackingKeyboardAccessibilityTest {
    @TempDir Path root;
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    private static <T>T fx(Callable<T> action)throws Exception{var task=new FutureTask<T>(action);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
    private static KeyEvent enter(){return new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ENTER,false,false,false,false);}

    @Test void enterOpensOnlySelectedShipmentAndHeaderNamesFilteredSelection()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try {fx(()->{
            var loader=FxmlViewLoader.loader(PackingController.class,"packing-view.fxml");var scene=new Scene(loader.load(),1100,720);
            var stage=new Stage();stage.setScene(scene);stage.show();var controller=(PackingController)loader.getController();
            try {
                @SuppressWarnings("unchecked") var selected=(TableColumn<Object,Boolean>)loader.getNamespace().get("selectedTC");
                var checkbox=(CheckBox)selected.getGraphic();
                assertEquals(I18nService.getInstance().tr("packing.select_filtered"),checkbox.getAccessibleText());assertNotNull(checkbox.getTooltip());
                var tabs=(TabPane)loader.getNamespace().get("packingTabPane");tabs.getSelectionModel().select((Tab)loader.getNamespace().get("preparationTab"));
                @SuppressWarnings("unchecked") var table=(TableView<WbSupplySummary>)loader.getNamespace().get("preparationTable");
                var supply=new WbSupplySummary("WB-FIXTURE-1","Fixture",false,false,"2026-01-01T00:00:00Z",1);table.getItems().setAll(supply);
                var opened=new AtomicReference<WbSupplySummary>();var count=new AtomicInteger();controller.setOnPrintSupply(value->{opened.set(value);count.incrementAndGet();});
                table.getSelectionModel().select(supply);scene.getRoot().applyCss();scene.getRoot().layout();
                assertEquals(I18nService.getInstance().tr("packing.open_shipment_help"),table.getAccessibleHelp());
                var child=table.lookup(".table-row-cell");assertNotNull(child);Event.fireEvent(child,enter());assertEquals(0,count.get(),"A child control must not also open the shipment");
                Event.fireEvent(table,enter());assertEquals(1,count.get());assertSame(supply,opened.get());
                table.getSelectionModel().clearSelection();Event.fireEvent(table,enter());assertEquals(1,count.get());
                return null;
            } finally {controller.dispose();stage.close();}
        });} finally {com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    }
}
