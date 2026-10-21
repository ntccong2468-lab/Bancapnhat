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
 @Test void completedPillDisappearsWithoutRemovingTheNextRunningTask()throws Exception{
  var removed=new CountDownLatch(1);var host=fx(()->{
   var pane=new StackPane();new Scene(pane,800,500);
   var first=RegistrationStatusPill.show(pane,"registrationBackgroundPill","Finished",false);
   RegistrationStatusPill.dismissAfter(pane,first,javafx.util.Duration.millis(40));
   var next=RegistrationStatusPill.show(pane,"registrationBackgroundPill","Next card",true);
   var timer=new javafx.animation.PauseTransition(javafx.util.Duration.millis(150));timer.setOnFinished(e->{assertTrue(pane.getChildren().contains(next));removed.countDown();});timer.play();
   return pane;
  });assertTrue(removed.await(3,TimeUnit.SECONDS));
  var gone=new CountDownLatch(1);fx(()->{
   var finished=RegistrationStatusPill.show(host,"registrationBackgroundPill","Finished",false);
   host.getChildren().addListener((javafx.collections.ListChangeListener<javafx.scene.Node>)c->{if(!host.getChildren().contains(finished))gone.countDown();});
   RegistrationStatusPill.dismissAfter(host,finished,javafx.util.Duration.millis(40));return null;
  });assertTrue(gone.await(3,TimeUnit.SECONDS));
  fx(()->{assertNull(host.lookup("#registrationBackgroundPill"));return null;});
 }
 @Test void resultCopySeparatesMarketplaceUpdatesFromUnmatchedAndUnconfirmedGtins(){
  var result=new ZnackGtinBackgroundService.Result("run",new ZnackGtinBackgroundService.Progress(10,10,7,2,1),new ZnackGtinBackgroundService.Inventory(15,12,3,2));
  String text=GtinBackgroundStatus.summary(result);assertTrue(text.contains("7/12"));assertTrue(text.contains("5"));assertTrue(text.contains("3"));assertFalse(text.contains("gtin.background.summary"));
 }
 @Test void missingRequestedRowsRemainUnknownAndAreNotReportedAsAbsentGtins(){
  var result=new ZnackGtinBackgroundService.Result("run",new ZnackGtinBackgroundService.Progress(0,0,0,0,0),new ZnackGtinBackgroundService.Inventory(3,0,0,0,3));
  var i18n=I18nService.getInstance();String known=java.text.MessageFormat.format(i18n.tr("gtin.background.summary"),0,0,0,0,0,0);
  assertEquals(known+" · "+i18n.tr("znack.registration.report_unverified")+": 3",GtinBackgroundStatus.summary(result));
  var legacy=new ZnackGtinBackgroundService.Result("legacy",new ZnackGtinBackgroundService.Progress(0,0,0,0,0),new ZnackGtinBackgroundService.Inventory(0,0,0,0));assertEquals(known,GtinBackgroundStatus.summary(legacy));
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
 @Test void persistedPendingReadbackRefreshesCountsAndDismissesOnlyAfterSettlement()throws Exception{
  var shop=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);
  var repo=new com.vncode.app.features.sync.SyncRunRepository();String run=repo.begin(shop,"znack_marketplace","READBACK");
  var item=repo.add(shop,run,"100","101","","","","",com.vncode.app.features.gtinsync.GtinWriteTestFixture.GTIN,"WAITING_REMOTE","","{}");
  var context=com.vncode.app.features.sync.GtinRunStatusSnapshot.capture(shop,java.util.Set.of());
  var initial=com.vncode.app.features.sync.GtinRunStatusSnapshot.read(context,run).result();
  var pending=new CountDownLatch(1);var verified=new CountDownLatch(1);var gone=new CountDownLatch(1);
  var pane=fx(()->{var host=new StackPane();new Scene(host,800,500);
   GtinBackgroundStatus.observe(host,context,initial,()->{},value->{if(value.progress().pending()==1)pending.countDown();if(value.progress().succeeded()==1)verified.countDown();},javafx.util.Duration.millis(30),javafx.util.Duration.millis(160));
   host.getChildren().addListener((javafx.collections.ListChangeListener<javafx.scene.Node>)change->{if(host.lookup("#gtinBackgroundPill")==null)gone.countDown();});return host;});
  assertTrue(pending.await(4,TimeUnit.SECONDS));assertEquals(1,gone.getCount());
  repo.registrationProgress(shop,item,item.gtin(),"SUCCEEDED","VERIFIED","","{\"marketplaceVerified\":true}");
  assertTrue(verified.await(4,TimeUnit.SECONDS));fx(()->{assertTrue(pane.lookupAll(".progress-indicator").isEmpty());assertTrue(pane.lookupAll(".label").stream().map(n->((Label)n).getText()).anyMatch(t->t.contains("1/1")));return null;});
  assertTrue(gone.await(4,TimeUnit.SECONDS));
 }
 @Test void oldRunAndChangedOwnerNeverOverwriteTheCurrentStatusPill()throws Exception{
  var shop=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);
  var repo=new com.vncode.app.features.sync.SyncRunRepository();String old=repo.begin(shop,"znack_marketplace","READBACK"),next=repo.begin(shop,"znack_marketplace","READBACK");
  repo.add(shop,old,"100","101","","","","",com.vncode.app.features.gtinsync.GtinWriteTestFixture.GTIN,"SUCCEEDED","","{}");
  repo.add(shop,next,"200","201","","","","",com.vncode.app.features.gtinsync.GtinWriteTestFixture.GTIN,"WAITING_REMOTE","","{}");
  var context=com.vncode.app.features.sync.GtinRunStatusSnapshot.capture(shop,java.util.Set.of());var stale=new java.util.concurrent.atomic.AtomicInteger();var current=new CountDownLatch(1);
  var pane=fx(()->{var host=new StackPane();new Scene(host,800,500);
   GtinBackgroundStatus.observe(host,context,com.vncode.app.features.sync.GtinRunStatusSnapshot.read(context,old).result(),()->{},value->stale.incrementAndGet(),javafx.util.Duration.millis(30),javafx.util.Duration.millis(100));
   GtinBackgroundStatus.observe(host,context,com.vncode.app.features.sync.GtinRunStatusSnapshot.read(context,next).result(),()->{},value->{assertEquals(next,value.run());current.countDown();},javafx.util.Duration.millis(30),javafx.util.Duration.millis(100));return host;});
  assertTrue(current.await(4,TimeUnit.SECONDS));assertEquals(0,stale.get());
  try(var c=com.vncode.app.config.Database.getConnection();var p=c.prepareStatement("UPDATE shops SET api_key='rotated-fixture' WHERE id=?")){p.setInt(1,shop.getId());p.executeUpdate();}
  var removed=new CountDownLatch(1);fx(()->{if(pane.lookup("#gtinBackgroundPill")==null)removed.countDown();else pane.getChildren().addListener((javafx.collections.ListChangeListener<javafx.scene.Node>)change->{if(pane.lookup("#gtinBackgroundPill")==null)removed.countDown();});return null;});
  assertTrue(removed.await(4,TimeUnit.SECONDS));assertEquals(0,stale.get());
 }
 @Test void aRejectedQueuedMutationCannotReplaceANewerShopsStatusPill()throws Exception{
  fx(()->{var host=new StackPane();new Scene(host,800,500);
   var task=new Task<ZnackGtinBackgroundService.Result>(){protected ZnackGtinBackgroundService.Result call(){throw new AssertionError("Rejected mutation must not run");}};
   var old=RegistrationStatusPill.show(host,"gtinBackgroundPill","Old shop",true);
   var current=RegistrationStatusPill.show(host,"gtinBackgroundPill","Current shop",true);
   GtinBackgroundStatus.finishRejected(host,old,task,new java.util.concurrent.RejectedExecutionException("queue unavailable"));
   assertSame(current,host.lookup("#gtinBackgroundPill"));assertTrue(host.getChildren().contains(current));
   GtinBackgroundStatus.finishRejected(host,current,task,new java.util.concurrent.RejectedExecutionException("queue unavailable"));
   assertFalse(host.getChildren().contains(current));assertTrue(host.lookupAll(".progress-indicator").isEmpty());assertNotNull(host.lookup("#gtinBackgroundErrorDetails"));return null;});
 }
 @Test void detachingPendingViewRemovesOnlyItsPillAndReopeningReadsTheCompletedDurableRun()throws Exception{
  var shop=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);
  var repo=new com.vncode.app.features.sync.SyncRunRepository();String run=repo.begin(shop,"znack_marketplace","READBACK");
  var item=repo.add(shop,run,"100","101","","","","",com.vncode.app.features.gtinsync.GtinWriteTestFixture.GTIN,"WAITING_REMOTE","","{}");
  var context=com.vncode.app.features.sync.GtinRunStatusSnapshot.capture(shop,java.util.Set.of());
  var initial=com.vncode.app.features.sync.GtinRunStatusSnapshot.read(context,run).result();var pending=new CountDownLatch(1);var removed=new CountDownLatch(1);
  var pane=fx(()->{var host=new StackPane();new Scene(host,800,500);
   GtinBackgroundStatus.observe(host,context,initial,()->{},result->pending.countDown(),javafx.util.Duration.millis(30),javafx.util.Duration.millis(200));
   host.getChildren().addListener((javafx.collections.ListChangeListener<javafx.scene.Node>)change->{if(host.lookup("#gtinBackgroundPill")==null)removed.countDown();});return host;});
  assertTrue(pending.await(4,TimeUnit.SECONDS));fx(()->{pane.getScene().setRoot(new StackPane());assertNull(pane.getScene());return null;});
  assertTrue(removed.await(4,TimeUnit.SECONDS),"Detached cached views must not retain a permanent pending spinner");
  // The durable worker remains independent from the detached view and completes its readback.
  var work=java.util.concurrent.CompletableFuture.runAsync(()->repo.registrationProgress(shop,item,item.gtin(),"SUCCEEDED","VERIFIED","","{\"marketplaceVerified\":true}"));work.get(4,TimeUnit.SECONDS);
  assertEquals("SUCCEEDED",repo.item(run,item.id()).orElseThrow().state());var complete=new CountDownLatch(1);
  fx(()->{new Scene(pane,800,500);assertNull(pane.lookup("#gtinBackgroundPill"));
   GtinBackgroundStatus.observe(pane,context,initial,()->{},result->{if(result.progress().succeeded()==1)complete.countDown();},javafx.util.Duration.millis(30),javafx.util.Duration.millis(300));return null;});
  assertTrue(complete.await(4,TimeUnit.SECONDS));fx(()->{assertTrue(pane.lookupAll(".progress-indicator").isEmpty());assertTrue(pane.lookupAll(".label").stream().map(n->((Label)n).getText()).anyMatch(text->text.contains("1/1")));return null;});
 }
}
