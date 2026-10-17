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
}
