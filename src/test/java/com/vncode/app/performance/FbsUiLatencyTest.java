package com.vncode.app.performance;

import com.vncode.app.config.Database;
import com.vncode.app.integration.ozon.OzonPostingDto;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import com.vncode.app.ui.ozon.OzonDashboardController;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class FbsUiLatencyTest {
 @TempDir Path root;
 @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
 static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<T>(work);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
 static void pulse()throws Exception{var done=new CompletableFuture<Void>();fx(()->{new javafx.animation.AnimationTimer(){int count;public void handle(long now){if(++count==2){stop();done.complete(null);}}}.start();return null;});done.get(5,TimeUnit.SECONDS);}
 @Test void committedOzonChangesReachTheRealPagedViewDuringTenShopDatabaseLoad()throws Exception{
  SellerWorkspaceFixture.create(root,10);System.setProperty("vncode.appdata.dir",root.toString());System.setProperty("vncode.runtime.diagnostics","true");
  var loader=fx(()->{var l=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");l.load();return l;});OzonDashboardController controller=loader.getController();
  var sidebar=new com.vncode.app.ui.shop.ShopSidebarController[1];var header=new com.vncode.app.ui.workspace.WorkspaceHeaderController[1];
  var language=I18nService.getInstance().getCurrentLanguage();
  var stage=fx(()->{var side=FxmlViewLoader.loader(com.vncode.app.ui.shop.ShopSidebarController.class,"shop-sidebar-view.fxml");var nav=(javafx.scene.Parent)side.load();sidebar[0]=side.getController();var top=FxmlViewLoader.loader(com.vncode.app.ui.workspace.WorkspaceHeaderController.class,"workspace-header-view.fxml");var bar=(javafx.scene.Parent)top.load();header[0]=top.getController();
    var selected=new Shop(1,"Synthetic shop 1",Marketplace.OZON,"1","synthetic-fixture-key");var shops=java.util.stream.IntStream.rangeClosed(1,10).mapToObj(i->new Shop(i,"Synthetic shop "+i,Marketplace.OZON,Integer.toString(i),"synthetic-fixture-key")).toList();header[0].setShops(shops,selected);header[0].setMarketplace(Marketplace.OZON);header[0].setControls(true,false,true,true);sidebar[0].setMarketplace(Marketplace.OZON);sidebar[0].setLicenseStatus(com.vncode.app.features.license.LicenseModels.Status.ACTIVE);sidebar[0].selectNavigation("packingButton");
    var content=new javafx.scene.layout.BorderPane((javafx.scene.Parent)loader.getRoot(),bar,null,null,null);var shell=new javafx.scene.layout.BorderPane(content,null,null,null,nav);var s=new Stage();var scene=new Scene(shell,1366,768);scene.widthProperty().addListener((o,old,width)->sidebar[0].setViewportWidth(width.doubleValue()));sidebar[0].setViewportWidth(1366);s.setScene(scene);scene.getStylesheets().add(com.vncode.app.MainApplication.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());s.show();controller.setShop(selected,false);return s;});
  TableView<OzonPostingDto> table=(TableView<OzonPostingDto>)loader.getNamespace().get("newOrdersTable");
  try{
   for(int n=0;n<500&&!fx(()->table.getItems().size()==100);n++)Thread.sleep(10);assertEquals(100,fx(()->table.getItems().size()));pulse();
   String posting=fx(()->table.getItems().getFirst().postingNumber());
   fx(()->{RuntimeDiagnostics.reset();RuntimeDiagnostics.startPulseMonitor();table.getSelectionModel().select(0);return null;});
   var latency=new ArrayList<Double>();var dispatch=new ArrayList<Double>();
   for(int sample=0;sample<20;sample++){
    String price=Integer.toString(10000+sample);
    try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_posting_items SET price=? WHERE shop_id>1 OR (shop_id=1 AND posting_number=?)")){p.setString(1,price);p.setString(2,posting);assertEquals(9001,p.executeUpdate());}
    long committed=System.nanoTime();var applied=new CompletableFuture<Void>();
    fx(()->{dispatch.add((System.nanoTime()-committed)/1e6);table.getItems().addListener(new javafx.collections.ListChangeListener<>(){public void onChanged(Change<? extends OzonPostingDto> change){if(table.getItems().stream().filter(p->p.postingNumber().equals(posting)).anyMatch(p->p.items().getFirst().price().equals(price))){table.getItems().removeListener(this);applied.complete(null);}}});controller.refreshLocalData();return null;});
    applied.get(5,TimeUnit.SECONDS);pulse();latency.add((System.nanoTime()-committed)/1e6);
    assertEquals(posting,fx(()->table.getSelectionModel().getSelectedItem().postingNumber()));assertTrue(fx(()->table.lookupAll(".table-row-cell").size())<40);
   }
   latency.sort(Double::compare);dispatch.sort(Double::compare);double p95=latency.get(18),dispatch95=dispatch.get(18);
   assertTrue(p95<2000,"Local committed updates must not remain stuck in the UI queue");
   System.out.println("FBS_UI_LATENCY synthetic=true shops=10 orders_per_shop=1000 products=100000 samples=20 visible_page=100 commit_to_pulse_p95_ms="+p95+" fx_dispatch_p95_ms="+dispatch95+" nativeWindows=false");
   System.out.println("FBS_UI_DIAGNOSTICS "+RuntimeDiagnostics.snapshot());
   String capture=System.getProperty("vncode.ui.capture.dir");if(capture!=null){
    fx(()->{I18nService.getInstance().setLanguage(AppLanguage.VI);controller.applyTranslations();header[0].applyTranslations();sidebar[0].applyTranslations();return null;});
    for(int width:List.of(1366,1024)){fx(()->{stage.setWidth(width);stage.setHeight(width==1024?600:768);return null;});pulse();fx(()->{var scene=stage.getScene();scene.getRoot().applyCss();scene.getRoot().layout();var image=scene.snapshot(null);int w=(int)image.getWidth(),h=(int)image.getHeight();var png=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.getPixelReader().getPixels(0,0,w,h,javafx.scene.image.PixelFormat.getIntArgbInstance(),((java.awt.image.DataBufferInt)png.getRaster().getDataBuffer()).getData(),0,w);java.nio.file.Files.createDirectories(Path.of(capture));javax.imageio.ImageIO.write(png,"png",Path.of(capture,"item7-workspace-"+width+".png").toFile());return null;});}
   }
  }finally{fx(()->{RuntimeDiagnostics.stopPulseMonitor();controller.dispose();header[0].dispose();stage.close();I18nService.getInstance().setLanguage(language);return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");System.clearProperty("vncode.runtime.diagnostics");}
 }
}
