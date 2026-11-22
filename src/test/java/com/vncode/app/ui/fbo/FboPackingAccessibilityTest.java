package com.vncode.app.ui.fbo;

import com.vncode.app.config.Database;
import com.vncode.app.features.fbo.FboBarcodePrintItem;
import com.vncode.app.features.fbo.FboProductSku;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Local JavaFX controls only: no printing, purchase or seller API. */
class FboPackingAccessibilityTest {
    @TempDir Path root;
    @BeforeAll static void toolkit() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { } }
    private static <T> T fx(Callable<T> action) throws Exception {var task=new FutureTask<T>(action);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
    private static FboProductSku product(String sku,String article) {return new FboProductSku(100,article,"fixture","","Product","Blue","XL","",sku,"",false,sku);}

    @Test void rowNamesAndPrintScopeFollowCurrentLoadedProductsForBothMarketplaces() throws Exception {
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        var previous=I18nService.getInstance().getCurrentLanguage();
        try {
            for(var marketplace:List.of(Marketplace.WILDBERRIES,Marketplace.OZON)) fx(()->{
                I18nService.getInstance().setLanguage(AppLanguage.VI);
                var loader=FxmlViewLoader.loader(FboPackingController.class,"fbo-packing-view.fxml");
                var scene=new Scene(loader.load(),1200,700);var stage=new Stage();stage.setScene(scene);stage.show();
                var controller=(FboPackingController)loader.getController();controller.setMarketplace(marketplace);
                var first=product("sku-1","article-1");var second=product("sku-2","article-2");
                try {
                    var scope=(Label)loader.getNamespace().get("printScopeLabel");var print=(Button)loader.getNamespace().get("printButton");
                    controller.replaceProducts(List.of(first),false);scene.getRoot().applyCss();scene.getRoot().layout();
                    var qty=(TextField)scene.getRoot().lookup(".fbo-quantity-field");
                    var quick=(Button)scene.getRoot().lookup(".fbo-row-print-button");
                    assertNotNull(qty);assertNotNull(quick);assertTrue(qty.getAccessibleText().contains("article-1"));
                    assertTrue(quick.getAccessibleText().contains("article-1"));assertNotNull(quick.getTooltip());
                    assertFalse(scope.isVisible());qty.setText("2");assertTrue(scope.isVisible());
                    assertEquals(I18nService.getInstance().tr("fbo.print_scope"),scope.getText());
                    var printed=new AtomicReference<List<FboBarcodePrintItem>>();controller.setOnPrint(printed::set);print.fire();
                    assertEquals(1,printed.get().size());assertEquals("sku-1",printed.get().getFirst().product().catalogSku());assertEquals(2,printed.get().getFirst().quantity());
                    controller.replaceProducts(List.of(second),false);scene.getRoot().applyCss();scene.getRoot().layout();
                    assertFalse(scope.isVisible(),"An earlier filtered product does not silently join this print");assertTrue(print.isDisabled());
                    var nextQty=(TextField)scene.getRoot().lookup(".fbo-quantity-field");var nextQuick=(Button)scene.getRoot().lookup(".fbo-row-print-button");
                    assertTrue(nextQty.getAccessibleText().contains("article-2"));assertFalse(nextQty.getAccessibleText().contains("article-1"));
                    assertTrue(nextQuick.getAccessibleText().contains("article-2"));
                    return null;
                } finally {stage.close();}
            });
        } finally {fx(()->{I18nService.getInstance().setLanguage(previous);return null;});System.clearProperty("vncode.appdata.dir");}
    }
}
