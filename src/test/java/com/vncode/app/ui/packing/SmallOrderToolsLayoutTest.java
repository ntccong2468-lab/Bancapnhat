package com.vncode.app.ui.packing;

import com.vncode.app.config.Database;
import com.vncode.app.features.fbo.FboProductSku;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.FxmlViewLoader;
import com.vncode.app.shared.I18nService;
import com.vncode.app.shared.ThemeService;
import com.vncode.app.ui.fbo.FboPackingController;
import com.vncode.app.ui.ozon.OzonDashboardController;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic local UI only: no print, KIZ purchase or seller network. */
class SmallOrderToolsLayoutTest {
    @TempDir Path root;
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    private static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<T>(work);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);}
    private static void fits(Node node,Scene scene,String context){
        var b=node.localToScene(node.getBoundsInLocal());assertTrue(node.isVisible()&&node.isManaged(),context+" must be visible");
        assertTrue(b.getMinX()>=-1&&b.getMaxX()<=scene.getWidth()+1,context+" outside workspace: "+b);
        assertTrue(b.getMinY()>=-1&&b.getMaxY()<=scene.getHeight()+1,context+" outside viewport: "+b);
        if(node instanceof Labeled text) {
            if(text.isWrapText())assertTrue(text.getHeight()+1>=text.prefHeight(text.getWidth()),context+" wrapped text clipped vertically");
            else assertTrue(text.getWidth()+1>=text.prefWidth(-1),context+" text clipped: actual="+text.getWidth()+" required="+text.prefWidth(-1));
        }
    }
    private static void below(Node node,TableView<?> table,String context){
        var item=node.localToScene(node.getBoundsInLocal());var data=table.localToScene(table.getBoundsInLocal());
        assertTrue(item.getMaxY()<=data.getMinY()+1,context+" overlaps the order list");
    }
    private static FboProductSku product(){return new FboProductSku(100,"FIXTURE-ARTICLE","Fixture","","Synthetic product","Black","XL","","FIXTURE-BARCODE","",false,"FIXTURE-SKU");}

    @Test void ozonActiveCancelAndHeaderActionsFitNarrowWorkspace()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();var language=I18nService.getInstance().getCurrentLanguage();
        try{
            for(var locale:AppLanguage.values())for(int width:new int[]{764,704}){
                var holder=fx(()->{
                    I18nService.getInstance().setLanguage(locale);var loader=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");
                    Parent page=loader.load();var scene=new Scene(page,width,600);ThemeService.applyTheme(scene);var stage=new Stage();stage.setScene(scene);stage.show();
                    var cancel=(Button)loader.getNamespace().get("cancelSelectionButton");cancel.setVisible(true);cancel.setManaged(true);
                    page.applyCss();page.layout();return new Object[]{loader,stage};
                });
                try {Thread.sleep(160);fx(()->{
                    var loader=(javafx.fxml.FXMLLoader)holder[0];var stage=(Stage)holder[1];var scene=stage.getScene();scene.getRoot().applyCss();scene.getRoot().layout();
                    var table=(TableView<?>)loader.getNamespace().get("newOrdersTable");String state=locale+" "+width;
                    for(String id:List.of("titleLabel","refreshButton","cancelSelectionButton","kizInventoryButton","ozonZnackRegistrationButton")){
                        var node=(Node)loader.getNamespace().get(id);fits(node,scene,state+" "+id);below(node,table,state+" "+id);
                    }
                    assertTrue(table.getHeight()>=220,state+" must keep a usable order list");
                    assertNotNull(((Button)loader.getNamespace().get("cancelSelectionButton")).getOnAction());
                    return null;
                });}finally{fx(()->{var l=(javafx.fxml.FXMLLoader)holder[0];((OzonDashboardController)l.getController()).dispose();((Stage)holder[1]).close();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();}
            }
        }finally{fx(()->{I18nService.getInstance().setLanguage(language);return null;});System.clearProperty("vncode.appdata.dir");}
    }

    @Test void fboFilterPrintAndLoadingFitNarrowWorkspaceForBothMarketplaces()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();var language=I18nService.getInstance().getCurrentLanguage();
        try{
            for(var locale:AppLanguage.values())for(var market:List.of(Marketplace.WILDBERRIES,Marketplace.OZON))for(int width:new int[]{764,704}){
                var holder=fx(()->{
                    I18nService.getInstance().setLanguage(locale);var loader=FxmlViewLoader.loader(FboPackingController.class,"fbo-packing-view.fxml");
                    Parent page=loader.load();var scene=new Scene(page,width,600);ThemeService.applyTheme(scene);var stage=new Stage();stage.setScene(scene);stage.show();
                    var controller=(FboPackingController)loader.getController();controller.setMarketplace(market);controller.replaceProducts(List.of(product()),false);
                    ((TextField)loader.getNamespace().get("searchField")).setText("fixture");controller.setLoading(true);page.applyCss();page.layout();
                    var quantity=(TextField)page.lookup(".fbo-quantity-field");assertNotNull(quantity);quantity.setText("10");page.applyCss();page.layout();return new Object[]{loader,stage};
                });
                try {Thread.sleep(160);fx(()->{
                    var loader=(javafx.fxml.FXMLLoader)holder[0];var scene=((Stage)holder[1]).getScene();scene.getRoot().applyCss();scene.getRoot().layout();
                    var table=(TableView<?>)loader.getNamespace().get("productTable");String state=locale+" "+market+" "+width;
                    for(String id:List.of("searchField","categoryMenuButton","clearFiltersButton","loadingLabel","printButton","printScopeLabel")){
                        var node=(Node)loader.getNamespace().get(id);fits(node,scene,state+" "+id);below(node,table,state+" "+id);
                    }
                    assertTrue(table.getHeight()>=220,state+" must keep a usable product list");assertNotNull(((Button)loader.getNamespace().get("printButton")).getOnAction());return null;
                });}finally{fx(()->{((Stage)holder[1]).close();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();}
            }
        }finally{fx(()->{I18nService.getInstance().setLanguage(language);return null;});System.clearProperty("vncode.appdata.dir");}
    }
}
