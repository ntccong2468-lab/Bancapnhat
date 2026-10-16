package com.vncode.app.ui.workspace;

import com.vncode.app.config.Database;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspacePagesLayoutTest {
 @TempDir Path root;
 @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
 static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<T>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
 static void pulse()throws Exception{var done=new CompletableFuture<Void>();fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames==2){stop();done.complete(null);}}}.start();return null;});done.get(5,TimeUnit.SECONDS);}
 @Test void allFxmlPagesKeepFixedActionsInsideTheContentViewportAcrossLocalesAndThemes()throws Exception{
  System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();var language=I18nService.getInstance().getCurrentLanguage();var clipped=new ArrayList<String>();var findings=new TreeMap<String,Integer>();int[] checked={0};
  var paths=List.of("dashboard/dashboard-view.fxml","fbo/fbo-packing-view.fxml","fbosupply/fbo-supply-orders-view.fxml","finance/finance-dashboard-view.fxml","gtinsync/gtin-sync-view.fxml","history/print-history-view.fxml","kizmapping/kiz-mapping-view.fxml","ozon/ozon-dashboard-view.fxml","packing/packing-view.fxml","print/print-template-designer-view.fxml","shop/shop-dialog.fxml","supply/supply-detail-view.fxml","supply/supply-list-view.fxml","supply/supply-management-view.fxml","znack/znack-automation-view.fxml","znackregistration/znack-card-registration-view.fxml");
  Stage window=fx(()->new Stage());
  try{
   for(var lang:AppLanguage.values())for(String path:paths){
    var loader=fx(()->{I18nService.getInstance().setLanguage(lang);var l=FxmlViewLoader.loader(com.vncode.app.MainApplication.class,"/com/vncode/app/ui/"+path);Parent view=l.load();var scene=new Scene(view,940,510);scene.getStylesheets().add(com.vncode.app.MainApplication.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());window.setScene(scene);window.show();return l;});
    try{
     for(String theme:List.of("theme-dark","theme-light"))for(var size:List.of(new int[]{940,510},new int[]{1120,650})){
      fx(()->{var view=window.getScene().getRoot();view.getStyleClass().removeAll("theme-dark","theme-light");view.getStyleClass().add(theme);window.setWidth(size[0]);window.setHeight(size[1]);return null;});pulse();
      fx(()->{var scene=window.getScene();var view=scene.getRoot();view.applyCss();view.layout();assertTrue(view.getStyleClass().contains("app-page"));
       for(Node node:view.lookupAll(".button"))if(node instanceof ButtonBase){boolean visible=true,scrollable=false,horizontalScroll=false;for(Node parent=node;parent!=null;parent=parent.getParent()){visible&=parent.isVisible();if(parent instanceof ScrollPane scroll){scrollable=true;horizontalScroll|=scroll.getHbarPolicy()!=ScrollPane.ScrollBarPolicy.NEVER;}if(parent instanceof TableView<?>||parent instanceof ListView<?>){scrollable=true;horizontalScroll=true;}}if(!visible||!node.isManaged())continue;checked[0]++;var b=node.localToScene(node.getBoundsInLocal());if(!horizontalScroll&&(b.getMinX()<-1||b.getMaxX()>scene.getWidth()+1)||!scrollable&&(b.getMinY()<-1||b.getMaxY()>scene.getHeight()+1)){String finding=path+" "+lang+" "+size[0]+" "+node.getId()+" "+b;findings.merge(path,1,Integer::sum);if(findings.get(path)<=4)clipped.add(finding);}}
       return null;});
     }
    }finally{fx(()->{Object controller=loader.getController();if(controller!=null)try{controller.getClass().getMethod("dispose").invoke(controller);}catch(NoSuchMethodException none){}return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();}
   }
   assertTrue(checked[0]>100,"Layout checks must inspect actual fixed actions");System.out.println("WORKSPACE_LAYOUT pages="+paths.size()+" locales=4 themes=2 content_sizes=2 fixed_action_checks="+checked[0]+" clipped="+findings);assertTrue(clipped.isEmpty(),String.join("\n",clipped));
  }finally{fx(()->{window.close();I18nService.getInstance().setLanguage(language);return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
 }
}
