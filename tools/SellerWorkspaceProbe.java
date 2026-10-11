import com.google.gson.Gson;
import com.vncode.app.performance.SellerWorkspaceFixture;
import com.vncode.app.config.Database;
import com.vncode.app.models.Shop;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.features.products.ProductRow;
import com.vncode.app.shared.*;
import com.vncode.app.ui.products.ProductsPane;
import com.vncode.app.ui.gs1.Gs1Pane;
import com.vncode.app.ui.listingcopy.ListingCopyPane;
import com.vncode.app.ui.ozon.OzonDashboardController;
import javafx.application.Platform;
import javafx.animation.AnimationTimer;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;

/** Offline JavaFX probe of the built classes. The EXE launcher has a separate native gate. */
public final class SellerWorkspaceProbe {
 private static Stage stage;private static ProductsPane products;private static Gs1Pane gs1;private static ListingCopyPane copy;private static OzonDashboardController ozon;private static Parent ozonRoot;
 private static <T>T fx(Callable<T> work)throws Exception {var task=new FutureTask<>(work);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);}
 private static Object field(Object value,String name)throws Exception {Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
 private static void pulse()throws Exception {var done=new CompletableFuture<Void>();fx(()->{new AnimationTimer(){int frames;public void handle(long now){if(++frames==2){stop();done.complete(null);}}}.start();return null;});done.get(10,TimeUnit.SECONDS);}
 private static void ready(Callable<Boolean> predicate)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);do{if(fx(predicate))return;Thread.sleep(10);}while(System.nanoTime()<until);throw new IllegalStateException("Cached page did not render");}
 private static boolean drained(){return !AppTaskExecutor.hasRunningTasks()&&ShopReadCoordinator.shared().activeCount()==0&&ShopReadCoordinator.shared().pendingCount()==0&&ShopReadCoordinator.images().activeCount()==0&&ShopReadCoordinator.images().pendingCount()==0&&SellerMutationExecutor.activeCount()==0&&SellerMutationExecutor.pendingCount()==0;}
 public static void main(String[] args)throws Exception {
  if(args.length<3)throw new IllegalArgumentException("seed data ozonShops | run data output [cycles]");
  Path data=Path.of(args[1]).toAbsolutePath();if(args[0].equals("seed")){SellerWorkspaceFixture.create(data,Integer.parseInt(args[2]));return;}
  if(!Files.isRegularFile(data.resolve("fixture.json")))throw new IllegalArgumentException("Generated fixture marker required");
  Path output=Path.of(args[2]).toAbsolutePath();Files.createDirectories(output);System.setProperty("vncode.appdata.dir",data.toString());System.setProperty("vncode.runtime.diagnostics","true");
  int cycles=args.length>3?Integer.parseInt(args[3]):20;if(cycles<1||cycles>20)throw new IllegalArgumentException("Invalid cycle count");
  var started=new CountDownLatch(1);Platform.startup(started::countDown);if(!started.await(10,TimeUnit.SECONDS))throw new IllegalStateException("FX did not start");Platform.setImplicitExit(false);
  try {
   Database.initDatabase();
   var shops=new ArrayList<Shop>();try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT id,name,marketplace,client_id,api_key FROM shops ORDER BY id")){while(r.next())shops.add(new Shop(r.getInt(1),r.getString(2),Marketplace.valueOf(r.getString(3)),r.getString(4),r.getString(5)));}if(shops.size()!=10)throw new IllegalStateException("Need ten fixture shops");
   var listeners=I18nService.class.getDeclaredField("listeners");listeners.setAccessible(true);int before=((List<?>)listeners.get(I18nService.getInstance())).size();
   fx(()->{products=new ProductsPane();gs1=new Gs1Pane();copy=new ListingCopyPane();var loader=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");ozonRoot=FxmlViewLoader.load(loader);ozon=loader.getController();stage=new Stage();var scene=new Scene(products,1024,600);scene.getStylesheets().add(com.vncode.app.MainApplication.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());ThemeService.applyTheme(scene);stage.setScene(scene);stage.show();RuntimeDiagnostics.startPulseMonitor();return null;});
   for(int cycle=0;cycle<cycles;cycle++)for(var shop:shops){
    long start=System.nanoTime();fx(()->{products.setShop(shop);stage.getScene().setRoot(products);ThemeService.applyTheme(stage.getScene());return null;});
    ready(()->{var rows=(ListView<ProductRow>)field(products,"rows");return rows.getItems().size()==100&&rows.getItems().getFirst().key().shopId()==shop.getId();});pulse();RuntimeDiagnostics.record("cached-shop-to-pulse",System.nanoTime()-start);
    if(shop.getMarketplace()==Marketplace.OZON){long fbsStart=System.nanoTime();fx(()->{ozon.setShop(shop,false);stage.getScene().setRoot(ozonRoot);ThemeService.applyTheme(stage.getScene());return null;});ready(()->{var table=(TableView<?>)field(ozon,"newOrdersTable");return table.getItems().size()==100;});pulse();RuntimeDiagnostics.record("cached-fbs-to-pulse",System.nanoTime()-fbsStart);}
   }
   var ozonShop=shops.stream().filter(s->s.getMarketplace()==Marketplace.OZON).findFirst().orElseThrow();
   for(var language:AppLanguage.values())for(int[] size:new int[][]{{1024,600},{1366,768}}){
    fx(()->{I18nService.getInstance().setLanguage(language);products.applyTranslations();copy.applyTranslations();ozon.applyTranslations();products.setShop(ozonShop);gs1.setShop(ozonShop);copy.setShop(ozonShop);ozon.setShop(ozonShop,false);stage.setWidth(size[0]);stage.setHeight(size[1]);return null;});ready(SellerWorkspaceProbe::drained);pulse();
    var routes=new LinkedHashMap<String,Parent>();routes.put("products",products);routes.put("gs1",gs1);routes.put("listing-copy",copy);routes.put("ozon-fbs",ozonRoot);
    for(var route:routes.entrySet()){fx(()->{stage.getScene().setRoot(route.getValue());ThemeService.applyTheme(stage.getScene());return null;});pulse();var screenshot=fx(()->stage.getScene().snapshot(null));int width=(int)screenshot.getWidth(),height=(int)screenshot.getHeight();var png=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);screenshot.getPixelReader().getPixels(0,0,width,height,javafx.scene.image.PixelFormat.getIntArgbInstance(),((java.awt.image.DataBufferInt)png.getRaster().getDataBuffer()).getData(),0,width);ImageIO.write(png,"png",output.resolve(route.getKey()+"-"+language.code()+"-"+size[0]+"x"+size[1]+".png").toFile());}
   }
   ready(SellerWorkspaceProbe::drained);pulse();var report=new LinkedHashMap<String,Object>(RuntimeDiagnostics.snapshot());report.put("result","passed");report.put("appName","VN code");report.put("version",com.vncode.app.BuildConfig.getAppVersion());report.put("dataset",new Gson().fromJson(Files.readString(data.resolve("fixture.json")),Map.class));report.put("cycles",cycles);report.put("liveMarketplaceMutations",false);report.put("targetWindowsLowEndMeasured",false);report.put("outputScale",fx(()->stage.getOutputScaleX()));
   fx(()->{products.dispose();gs1.dispose();copy.dispose();ozon.dispose();RuntimeDiagnostics.stopPulseMonitor();stage.close();return null;});ready(SellerWorkspaceProbe::drained);int after=((List<?>)listeners.get(I18nService.getInstance())).size();if(before!=after)throw new IllegalStateException("Language listener leaked");
   if(((Number)report.get("screenCacheBytes")).longValue()>67108864||((Number)report.get("readWorkers")).intValue()!=3||((Number)report.get("imageWorkers")).intValue()!=2)throw new IllegalStateException("Runtime budget exceeded");report.put("listenersBefore",before);report.put("listenersAfter",after);Files.writeString(output.resolve("workspace-probe.json"),new Gson().toJson(report));
  }finally{fx(()->{RuntimeDiagnostics.stopPulseMonitor();if(products!=null)products.dispose();if(gs1!=null)gs1.dispose();if(copy!=null)copy.dispose();if(ozon!=null)ozon.dispose();if(stage!=null)stage.close();return null;});Platform.exit();}
 }
}
