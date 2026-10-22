package com.vncode.app.ui;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import com.vncode.app.ui.products.ProductsPane;
import com.vncode.app.ui.tnved.ProductTnvedPicker;
import com.vncode.app.ui.znack.ZnackAutomationController;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Renders real views with synthetic local data, without a marketplace or Catalog connection. */
class DarkReferenceScreensTest {
    @TempDir Path data;
    AppLanguage previous;
    final Shop shop=new Shop(1,"VNCode fixture","fixture");
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",data.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'VNCode fixture','WILDBERRIES','fixture')");}
        fx(()->{previous=I18nService.getInstance().getCurrentLanguage();I18nService.getInstance().setLanguage(AppLanguage.VI);ThemeService.initializeAppearance();return null;});
    }
    @AfterEach void cleanup()throws Exception{
        fx(()->{for(var window:List.copyOf(Window.getWindows()))if(window instanceof Stage stage)stage.close();I18nService.getInstance().setLanguage(previous);return null;});
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");
    }
    @Test void productReferenceKeepsFullWidthActionsAndReadableMetadata()throws Exception{
        var rows=new ArrayList<ProductRow>();
        for(int i=0;i<6;i++)rows.add(new ProductRow(new ProductKey(1,Marketplace.WILDBERRIES,""+(1825999876-i),""+(11+i)),"Мужской тёплый спортивный костюм на флисе 48–56","152 nau "+i,""+(1825999876-i),"Костюмы спортивные","VNCode fixture","коричневый","XL",List.of("2044431062877"),"","","Хлопок 80%, полиэстер 20%","6112110000",DocumentState.VALID,"Мужской",true));
        try(var reads=new ShopReadCoordinator(2,16)){
            var pane=fx(()->{var view=new ProductsPane(q->new ProductPage(rows,rows.size(),null,Map.of(ProductIssueFilter.INVALID_TNVED,0L,ProductIssueFilter.MISSING_BRAND,0L,ProductIssueFilter.MISSING_MATERIAL,0L)),reads);show(view,1360,823);view.setOnEdit((field,keys)->{});view.setShop(shop);return view;});
            try{
                await(()->((ListView<?>)pane.lookup("#productRows")).getItems().size()==6&&!((Button)pane.lookup("#productSync")).isDisabled());
                fx(()->{pane.applyCss();pane.layout();capture(pane,"products");((CheckBox)pane.lookup("#productSelectAll")).fire();return null;});
                await(()->pane.selectedKeys().size()==6);
                fx(()->{pane.applyCss();pane.layout();double y=-1;for(String field:List.of("brand","material","gender","tnved","documents")){var button=(Button)pane.lookup("#productEdit_"+field);assertFalse(button.isDisabled());var bounds=button.localToScene(button.getBoundsInLocal());if(y<0)y=bounds.getMinY();else assertEquals(y,bounds.getMinY(),1);}capture(pane,"products-selected");return null;});
            }finally{fx(()->{pane.dispose();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.closeAndAwait(reads);}
        }
    }
    @Test void settingsRequireExplicitEditAndCancelRestoresSavedValues()throws Exception{
        fx(()->{
            var loader=FxmlViewLoader.loader(ZnackAutomationController.class,"znack-automation-view.fxml");Parent view=FxmlViewLoader.load(loader);var controller=(ZnackAutomationController)loader.getController();controller.setShop(shop);show(view,1349,809);
            var settings=(Tab)loader.getNamespace().get("settingsTab");settings.getTabPane().getSelectionModel().select(settings);
            var oms=(TextField)loader.getNamespace().get("omsIdField");var key=(PasswordField)loader.getNamespace().get("catalogKeyField");var edit=(Button)loader.getNamespace().get("editSettingsButton");var save=(Button)loader.getNamespace().get("saveButton");
            assertFalse(oms.isEditable());assertFalse(key.isEditable());assertFalse(save.isVisible());
            view.applyCss();view.layout();capture(view,"znack-settings");
            String original=oms.getText();edit.fire();assertTrue(oms.isEditable());assertTrue(save.isVisible());oms.setText("unsaved-change");edit.fire();assertEquals(original,oms.getText());assertFalse(oms.isEditable());assertFalse(save.isVisible());var warehouse=settings.getTabPane().getTabs().stream().filter(tab->"warehouseTab".equals(tab.getId())).findFirst().orElseThrow();assertFalse(warehouse.isClosable());settings.getTabPane().getSelectionModel().select(warehouse);view.applyCss();view.layout();assertNotNull(view.lookup("#kiz-warehouse-import"));capture(view,"znack-kiz-warehouse");controller.setShop(null);return null;
        });
    }
    @Test void tnvedPickerOffersPerProductProposalAndCancelDoesNotApply()throws Exception{
        var key=new ProductKey(1,Marketplace.WILDBERRIES,"123","456");
        new ProductRepository().saveVerifiedSnapshot(shop,new ProductSnapshot(key,"live:fixture","Костюм спортивный","","VNCode fixture","fixture","9",List.of(),List.of(new ProductAttribute("1","Состав",List.of("Хлопок"),""),new ProductAttribute("2","Пол",List.of("Мужской"),""),new ProductAttribute("3","Тип ткани",List.of("Трикотаж"),"")),List.of(),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop))));
        var selected=new AtomicReference<ProductTnvedPicker.Choice>();
        fx(()->{ProductTnvedPicker.show(null,shop,List.of(key),selected::set);return null;});
        await(()->picker()!=null&&picker().lookup("#tnvedApplyEach") instanceof Button button&&!button.isDisabled());
        fx(()->{var pane=picker();pane.applyCss();pane.layout();capture(pane,"tnved-picker");var copy=pane.lookup("#tnvedCopy_6112110000");assertNotNull(copy);assertNotNull(((Button)copy).getGraphic());var window=pane.getScene().getWindow();((Button)pane.lookup("#tnvedPickerCancel")).fire();assertFalse(window.isShowing());assertNull(selected.get());return null;});
    }
    static Parent picker(){for(var window:Window.getWindows())if(window.getScene()!=null){var node=window.getScene().getRoot().lookup("#productTnvedPicker");if(node instanceof Parent parent)return parent;}return null;}
    static Stage show(Parent view,int width,int height){var scene=new Scene(view,width,height);ThemeService.applyTheme(scene);var stage=new Stage();stage.setScene(scene);stage.show();return stage;}
    static void capture(Parent view,String name)throws Exception{
        String dir=System.getProperty("vncode.reference.capture.dir");if(dir==null)return;Files.createDirectories(Path.of(dir));var image=view.snapshot(null,null);var png=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,image.getPixelReader().getArgb(x,y));javax.imageio.ImageIO.write(png,"png",Path.of(dir,name+".png").toFile());
    }
    static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
    static void await(Callable<Boolean> check)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!fx(check)&&System.nanoTime()<end)Thread.sleep(15);assertTrue(fx(check));}
}
