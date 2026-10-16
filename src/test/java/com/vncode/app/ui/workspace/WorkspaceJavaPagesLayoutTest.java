package com.vncode.app.ui.workspace;
import com.vncode.app.config.Database;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceJavaPagesLayoutTest {
 @TempDir Path root;
 @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
 static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<T>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
 static void pulse()throws Exception{var done=new CompletableFuture<Void>();fx(()->{new javafx.animation.AnimationTimer(){int frames;public void handle(long now){if(++frames==2){stop();done.complete(null);}}}.start();return null;});done.get(5,TimeUnit.SECONDS);}
 @Test void javaPagesAndZnackTabsShareTheResponsiveWorkspace()throws Exception{
  System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();var language=I18nService.getInstance().getCurrentLanguage();var findings=new ArrayList<String>();var checked=new int[1];
  var api=(com.vncode.app.integration.license.LicenseApi)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{com.vncode.app.integration.license.LicenseApi.class},(p,m,a)->{if(m.getName().equals("supportsNetworkDiagnostics"))return false;if(m.getName().equals("networkMode"))return com.vncode.app.integration.license.LicenseApi.NetworkMode.AUTOMATIC;throw new AssertionError("Layout test must not request license API");});
  try(var service=new com.vncode.app.features.license.LicenseService(api,null,null,java.time.Clock.systemUTC(),System::nanoTime)){
   var pages=new LinkedHashMap<String,Supplier<Parent>>();
   pages.put("products",com.vncode.app.ui.products.ProductsPane::new);pages.put("product-edit",com.vncode.app.ui.products.ProductBulkEditPane::new);pages.put("listing-copy",com.vncode.app.ui.listingcopy.ListingCopyPane::new);pages.put("gs1",com.vncode.app.ui.gs1.Gs1Pane::new);pages.put("guides",com.vncode.app.ui.guides.GuidesPane::new);pages.put("warehouse",com.vncode.app.ui.kiz.KizWarehousePane::new);pages.put("license",()->new com.vncode.app.ui.license.LicensePane(service));pages.put("news",()->new com.vncode.app.ui.news.NewsPane(new com.vncode.app.features.news.NewsService(List.of(),new com.vncode.app.features.news.NewsRepository(root)),()->{},n->{}));pages.put("welcome",()->new com.vncode.app.ui.dashboard.WelcomePane(new VBox(),java.util.Collections.nCopies(6,()->{})));
   var stage=fx(()->new Stage());
   try{
    for(var lang:AppLanguage.values())for(var entry:pages.entrySet()){
     Parent view=fx(()->{I18nService.getInstance().setLanguage(lang);Parent pane=entry.getValue().get();pane.getStyleClass().add("app-page");var scene=new Scene(pane,940,510);scene.getStylesheets().add(com.vncode.app.MainApplication.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());stage.setScene(scene);stage.show();return pane;});
     try{for(String theme:List.of("theme-dark","theme-light"))for(int width:List.of(940,1120)){
      fx(()->{view.getStyleClass().removeAll("theme-dark","theme-light");view.getStyleClass().add(theme);stage.setWidth(width);stage.setHeight(width==940?510:650);return null;});pulse();fx(()->{check(stage.getScene(),entry.getKey()+" "+lang+" "+width,findings,checked);return null;});
     }}finally{fx(()->{try{view.getClass().getMethod("dispose").invoke(view);}catch(NoSuchMethodException none){}return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();}
    }
    for(var lang:AppLanguage.values()){
     var loader=fx(()->{I18nService.getInstance().setLanguage(lang);var l=FxmlViewLoader.loader(com.vncode.app.MainApplication.class,"/com/vncode/app/ui/znack/znack-automation-view.fxml");Parent view=l.load();var scene=new Scene(view,940,510);scene.getStylesheets().add(com.vncode.app.MainApplication.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());stage.setScene(scene);stage.setWidth(940);stage.setHeight(510);stage.show();return l;});
     try{pulse();var tabs=fx(()->((Parent)loader.getRoot()).lookupAll(".tab-pane").stream().filter(n->n instanceof TabPane).map(n->(TabPane)n).toList());for(var pane:tabs)for(var tab:List.copyOf(pane.getTabs())){fx(()->{pane.getSelectionModel().select(tab);return null;});pulse();fx(()->{check(stage.getScene(),"znack-tab "+tab.getText()+" "+lang,findings,checked);return null;});}}
     finally{fx(()->{try{loader.getController().getClass().getMethod("dispose").invoke(loader.getController());}catch(NoSuchMethodException none){}return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();}
    }
    System.out.println("WORKSPACE_JAVA_LAYOUT pages="+pages.size()+" locales=4 themes=2 sizes=2 fixed_action_checks="+checked[0]+" clipped="+findings.size());assertTrue(checked[0]>100);assertTrue(findings.isEmpty(),String.join("\n",findings));
   }finally{fx(()->{stage.close();I18nService.getInstance().setLanguage(language);return null;});}
  }finally{com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
 }
 static void check(Scene scene,String label,List<String> findings,int[] checked){
  var view=scene.getRoot();view.applyCss();view.layout();for(Node node:view.lookupAll(".button"))if(node instanceof ButtonBase){boolean visible=true,scrollY=false,scrollX=false;for(Node p=node;p!=null;p=p.getParent()){visible&=p.isVisible();if(p instanceof ScrollPane scroll){scrollY=true;scrollX|=scroll.getHbarPolicy()!=ScrollPane.ScrollBarPolicy.NEVER;}if(p instanceof TableView<?>||p instanceof ListView<?>){scrollX=true;scrollY=true;}}if(!visible||!node.isManaged())continue;checked[0]++;var b=node.localToScene(node.getBoundsInLocal());if(!scrollX&&(b.getMinX()<-1||b.getMaxX()>scene.getWidth()+1)||!scrollY&&(b.getMinY()<-1||b.getMaxY()>scene.getHeight()+1)){if(findings.size()<50)findings.add(label+" "+node.getId()+" "+b);}}
 }
}
