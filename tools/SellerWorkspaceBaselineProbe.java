import com.google.gson.Gson;
import com.vncode.app.config.Database;
import com.vncode.app.models.Shop;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.shared.*;
import com.vncode.app.ui.ozon.OzonDashboardController;
import javafx.application.Platform;
import javafx.animation.AnimationTimer;
import javafx.scene.*;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Common Ozon FBS comparison for pre-products builds (for example baseline 62e7e32). */
public final class SellerWorkspaceBaselineProbe {
 private static Stage stage;private static OzonDashboardController controller;private static long previous,stalls,maxGap;
 private static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(30,TimeUnit.SECONDS);}
 private static void pulse()throws Exception{var done=new CompletableFuture<Void>();fx(()->{new AnimationTimer(){int frames;public void handle(long now){if(++frames==2){stop();done.complete(null);}}}.start();return null;});done.get(10,TimeUnit.SECONDS);}
 public static void main(String[] args)throws Exception{
  Path data=Path.of(args[0]).toAbsolutePath(),output=Path.of(args[1]).toAbsolutePath();int cycles=Integer.parseInt(args[2]);if(!Files.isRegularFile(data.resolve("fixture.json"))||cycles<1||cycles>20)throw new IllegalArgumentException("Generated fixture required");Files.createDirectories(output);System.setProperty("vncode.appdata.dir",data.toString());
  Database.initDatabase();var shops=new ArrayList<Shop>();try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT id,name,client_id,api_key FROM shops WHERE marketplace='OZON' ORDER BY id")){while(r.next())shops.add(new Shop(r.getInt(1),r.getString(2),Marketplace.OZON,r.getString(3),r.getString(4)));}
  var started=new CountDownLatch(1);Platform.startup(started::countDown);started.await(10,TimeUnit.SECONDS);Platform.setImplicitExit(false);var elapsed=new ArrayList<Long>();
  try{
   fx(()->{var loader=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");Parent root=FxmlViewLoader.load(loader);controller=loader.getController();stage=new Stage();var scene=new Scene(root,1024,600);scene.getStylesheets().add(com.vncode.app.MainApplication.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());ThemeService.applyTheme(scene);stage.setScene(scene);stage.show();new AnimationTimer(){public void handle(long now){if(previous!=0){long gap=now-previous;maxGap=Math.max(maxGap,gap);if(gap>500000000L)stalls++;}previous=now;}}.start();return null;});
   for(int cycle=0;cycle<cycles;cycle++)for(var shop:shops){
    long start=System.nanoTime();fx(()->{controller.setShop(shop,false);return null;});long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);boolean ready=false;
    do{ready=fx(()->{var field=controller.getClass().getDeclaredField("newOrdersTable");field.setAccessible(true);return ((TableView<?>)field.get(controller)).getItems().size()>=100&&!AppTaskExecutor.hasRunningTasks();});if(!ready)Thread.sleep(10);}while(!ready&&System.nanoTime()<until);if(!ready)throw new IllegalStateException("Baseline FBS did not render");pulse();elapsed.add(System.nanoTime()-start);
   }
   elapsed.sort(Long::compare);var runtime=Runtime.getRuntime();var report=new LinkedHashMap<String,Object>();report.put("result","passed");report.put("appName","VN code");report.put("version",com.vncode.app.BuildConfig.getAppVersion());report.put("dataset",new Gson().fromJson(Files.readString(data.resolve("fixture.json")),Map.class));report.put("cycles",cycles);report.put("baselineCommonFbsOnly",true);report.put("productsBaselineAvailable",false);report.put("cached-fbs-to-pulse",Map.of("samples",elapsed.size(),"medianMs",elapsed.get(elapsed.size()/2)/1e6,"p95Ms",elapsed.get((int)Math.ceil(elapsed.size()*.95)-1)/1e6));report.put("fxStallsOver500ms",stalls);report.put("maxFxPulseGapMs",maxGap/1e6);report.put("heapUsedBytes",runtime.totalMemory()-runtime.freeMemory());report.put("maxHeapBytes",runtime.maxMemory());report.put("outputScale",fx(()->stage.getOutputScaleX()));report.put("liveMarketplaceMutations",false);Files.writeString(output.resolve("workspace-probe.json"),new Gson().toJson(report));
  }finally{fx(()->{if(controller!=null)try{controller.getClass().getDeclaredMethod("dispose").invoke(controller);}catch(NoSuchMethodException unavailable){}if(stage!=null)stage.close();return null;});Platform.exit();}
 }
}
