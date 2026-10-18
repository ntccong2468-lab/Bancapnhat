package com.vncode.app.ui.znackregistration;
import com.vncode.app.features.sync.ZnackGtinBackgroundService;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class GtinBackgroundStatusTest {
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
 String previousDirectory;
 @BeforeEach void isolateData()throws Exception{previousDirectory=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",directory.toString());com.vncode.app.config.Database.initDatabase();}
 @AfterEach void restoreData(){if(previousDirectory==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previousDirectory);}
 @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
 static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
 @Test void oneBackgroundTaskUpdatesBothOriginalAndReopenedViewsWithoutDialogs()throws Exception{
  var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);var refreshed=new java.util.concurrent.atomic.AtomicInteger();
  var task=new Task<ZnackGtinBackgroundService.Result>(){protected ZnackGtinBackgroundService.Result call()throws Exception{updateMessage("GTIN 12/50");entered.countDown();finish.await(5,TimeUnit.SECONDS);return new ZnackGtinBackgroundService.Result("run",new ZnackGtinBackgroundService.Progress(50,50,47,2,1));}};
  var first=fx(()->{var pane=new StackPane(new TableView<>());new Scene(pane,800,500);GtinBackgroundStatus.attach(pane,task,refreshed::incrementAndGet);AppTaskExecutor.executeCatalog(task,e->fail(e));return pane;});assertTrue(entered.await(3,TimeUnit.SECONDS));
  var second=fx(()->{var pane=new StackPane(new TableView<>());new Scene(pane,800,500);GtinBackgroundStatus.attach(pane,task,refreshed::incrementAndGet);assertEquals(javafx.geometry.Pos.BOTTOM_CENTER,StackPane.getAlignment(pane.lookup("#gtinBackgroundPill")));assertTrue(pane.lookupAll(".dialog-pane").isEmpty());return pane;});
  finish.countDown();task.get(3,TimeUnit.SECONDS);for(int i=0;i<100&&refreshed.get()<2;i++){Thread.sleep(10);fx(()->null);}
  fx(()->{assertEquals(2,refreshed.get());for(var pane:java.util.List.of(first,second)){assertTrue(pane.lookupAll(".label").stream().anyMatch(n->((Label)n).getText().contains("47")));assertTrue(pane.lookupAll(".progress-indicator").isEmpty());}return null;});
 }
 @Test void failedCatalogAuthenticationReplacesSpinnerWithSpecificCryptoProError()throws Exception{
  var i18n=I18nService.getInstance();String advice=i18n.tr("znack.signature.error.private_key");
  var task=new Task<ZnackGtinBackgroundService.Result>(){protected ZnackGtinBackgroundService.Result call()throws Exception{
   throw new java.io.IOException("Catalog certificate signing failed",new com.vncode.app.integration.znack.signature.CryptoProException(
       com.vncode.app.integration.znack.signature.CryptoProErrorCode.PRIVATE_KEY_UNAVAILABLE,"token=secret-value"));
  }};
  var finished=new CountDownLatch(1);
  var pane=fx(()->{var host=new StackPane(new TableView<>());new Scene(host,800,500);
   GtinBackgroundStatus.attach(host,task,()->fail("Failed authentication must not trigger successful reload"));
   task.stateProperty().addListener((o,a,b)->{if(b==javafx.concurrent.Worker.State.FAILED)finished.countDown();});
   AppTaskExecutor.executeCatalog(task,e->{});return host;});
  assertTrue(finished.await(5,TimeUnit.SECONDS));
  fx(()->{var pill=pane.lookup("#gtinBackgroundPill");assertNotNull(pill);
   assertEquals(javafx.geometry.Pos.BOTTOM_CENTER,StackPane.getAlignment(pill));
   assertNotNull(pane.lookup("#gtinBackgroundErrorDetails"));
   assertTrue(pane.lookupAll(".progress-indicator").isEmpty());assertTrue(pane.lookupAll(".dialog-pane").isEmpty());
   assertTrue(pane.lookupAll(".label").stream().map(n->((Label)n).getText()).anyMatch(t->t.contains(advice)&&t.contains("[CRYPTOPRO_PRIVATE_KEY_UNAVAILABLE]")&&!t.contains("secret-value")));
   return null;});
 }
 @Test void reopeningCancelledTaskNeverRestartsSpinnerOrClaimsCryptoProFailed()throws Exception{
  fx(()->{var task=new Task<ZnackGtinBackgroundService.Result>(){protected ZnackGtinBackgroundService.Result call(){throw new AssertionError("Cancelled task must not run");}};
   task.cancel(false);var host=new StackPane(new TableView<>());new Scene(host,800,500);
   GtinBackgroundStatus.attach(host,task,()->fail("Cancellation is not a successful update"));
   String text=host.lookupAll(".label").stream().map(n->((Label)n).getText()).collect(java.util.stream.Collectors.joining("\n"));
   assertTrue(text.contains(I18nService.getInstance().tr("gtin.background.cancelled")));
   assertFalse(text.contains(I18nService.getInstance().tr("znack.signature.error.failed")));
   assertTrue(host.lookupAll(".progress-indicator").isEmpty());return null;});
 }
 @Test void gtinUpdateUsesTheShopMutationQueueInsteadOfTheReadDeadlineExecutor()throws Exception{
  var shop=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var ran=new CountDownLatch(1);
  var prior=SellerMutationExecutor.submit(shop,()->{entered.countDown();release.await(5,TimeUnit.SECONDS);return true;});
  var task=new Task<ZnackGtinBackgroundService.Result>(){protected ZnackGtinBackgroundService.Result call(){ran.countDown();return new ZnackGtinBackgroundService.Result("fixture",new ZnackGtinBackgroundService.Progress(1,1,1,0,0));}};
  java.util.concurrent.CompletableFuture<Void> accepted=null;
  try{
   assertTrue(entered.await(3,TimeUnit.SECONDS));
   accepted=fx(()->GtinBackgroundStatus.executeMutation(shop,task,error->fail(error)));
   assertFalse(ran.await(150,TimeUnit.MILLISECONDS),"The existing shop write must finish before another GTIN write starts");
   assertEquals(1,SellerMutationExecutor.pendingCount());assertTrue(AppTaskExecutor.hasRunningTasks());
  }finally{release.countDown();prior.get(5,TimeUnit.SECONDS);if(accepted!=null)accepted.get(5,TimeUnit.SECONDS);}
  assertEquals(1,task.get(3,TimeUnit.SECONDS).progress().succeeded());assertEquals(0,ran.getCount());
 }
}
