package com.vncode.app.ui.ozon;

import com.vncode.app.config.Database;
import com.vncode.app.features.kiz.*;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.FxmlViewLoader;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonShipmentSourcesUiTest {
 @TempDir Path root;
 @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
 static <T>T fx(Callable<T> f)throws Exception{var t=new FutureTask<T>(f);Platform.runLater(t);return t.get(10,TimeUnit.SECONDS);}
 static void await(Callable<Boolean> predicate)throws Exception{for(int i=0;i<400;i++){if(fx(predicate))return;Thread.sleep(10);}fail("Ozon source UI did not settle");}
 @Test void realFxmlShowsCategoryUploadCountsAndFrozenSourceControls()throws Exception{
  System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
  try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Synthetic Ozon','OZON','11','fixture')");}
  var shop=new Shop(1,"Synthetic Ozon",Marketplace.OZON,"11","fixture");
  new OzonCatalogRepository().upsertPage(1,List.of(new OzonProductDto("101","offer-a","1001","Synthetic set","",false,"",List.of()).withCardAttributes("a","Grey","XL","Bộ quần áo","").withCategoryIdentity("10","20")),"");
  var loader=fx(()->{var l=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");new Scene(l.load(),824,600);return l;});
  OzonDashboardController controller=loader.getController();
  var stage=fx(()->{var v=new javafx.stage.Stage();v.setScene(((javafx.scene.Parent)loader.getRoot()).getScene());v.getScene().getStylesheets().add(com.vncode.app.MainApplication.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());v.show();controller.setShop(shop,false);((TabPane)loader.getNamespace().get("orderStatusTabs")).getSelectionModel().select((Tab)loader.getNamespace().get("shipmentKizTab"));return v;});
  try{
   var content=stage.getScene().getRoot();
   await(()->!((CheckBox)content.lookup("#shipment-kiz-imported")).isDisabled());
   fx(()->{content.applyCss();content.layout();assertTrue(((CheckBox)content.lookup("#shipment-kiz-imported")).isSelected());assertFalse(((CheckBox)content.lookup("#shipment-kiz-purchased")).isSelected());var table=(TableView<?>)content.lookup("#shipment-kiz-categories");assertEquals(1,table.getItems().size());assertFalse(table.lookupAll("#shipment-kiz-upload").isEmpty());assertFalse(((TitledPane)loader.getNamespace().get("gtinInventoryPanel")).isManaged());return null;});
   String category=new ShipmentKizRepository().refreshCategories(shop).getFirst().id();
   var synthetic=root.resolve("synthetic.txt");Files.writeString(synthetic,"01"+com.vncode.app.testsupport.CategoryStockFixture.GTIN+"21"+"0000000000001\u001d91ABCD\u001d92RklYVFVSRS1OT1QtQS1SRUFMLUNSWVBUTy1TSUdOQVRVUkU=");
   new KizWarehouseService().importFile(shop,KizImportReader.read(synthetic),true,category);
   fx(()->{((Button)content.lookup("#shipment-kiz-refresh")).fire();return null;});
   await(()->{var table=(TableView<ShipmentKizRepository.CategoryStock>)content.lookup("#shipment-kiz-categories");return !table.getItems().isEmpty()&&table.getItems().getFirst().ready()==1;});
   fx(()->{((CheckBox)content.lookup("#shipment-kiz-purchased")).fire();return null;});
   await(()->!((CheckBox)content.lookup("#shipment-kiz-purchased")).isDisabled());
   assertEquals(new KizSourceSelection(true,true),new ShipmentKizRepository().selection(shop,"OZON:FBS"));
   fx(()->{assertTrue(((TitledPane)loader.getNamespace().get("gtinInventoryPanel")).isManaged());return null;});
   String capture=System.getProperty("vncode.ui.capture.dir");if(capture!=null)fx(()->{var language=com.vncode.app.shared.I18nService.getInstance().getCurrentLanguage();com.vncode.app.shared.I18nService.getInstance().setLanguage(com.vncode.app.shared.AppLanguage.VI);controller.applyTranslations();content.applyCss();content.layout();var image=stage.getScene().snapshot(null);int w=(int)image.getWidth(),h=(int)image.getHeight();var png=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.getPixelReader().getPixels(0,0,w,h,javafx.scene.image.PixelFormat.getIntArgbInstance(),((java.awt.image.DataBufferInt)png.getRaster().getDataBuffer()).getData(),0,w);Files.createDirectories(Path.of(capture));javax.imageio.ImageIO.write(png,"png",Path.of(capture,"item3-ozon-sources.png").toFile());com.vncode.app.shared.I18nService.getInstance().setLanguage(language);return null;});
  }finally{fx(()->{controller.dispose();stage.close();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
 }
}
