package com.vncode.app.shared;
import javafx.application.Platform;
import javafx.concurrent.Task;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class CatalogTaskExecutorTest {
 @BeforeAll static void fx()throws Exception{var ready=new CountDownLatch(1);try{Platform.startup(ready::countDown);}catch(IllegalStateException active){Platform.runLater(ready::countDown);}assertTrue(ready.await(10,TimeUnit.SECONDS));Platform.setImplicitExit(false);}
 @Test void catalogReadRunsEvenWhenBothGeneralUiWorkersAreOccupied()throws Exception{
  var started=new CountDownLatch(2);var release=new CountDownLatch(1);var blockers=new java.util.ArrayList<Task<Void>>();
  try{
   for(int n=0;n<2;n++){var task=new Task<Void>(){protected Void call()throws Exception{started.countDown();release.await(10,TimeUnit.SECONDS);return null;}};blockers.add(task);assertTrue(AppTaskExecutor.execute(task));}
   assertTrue(started.await(5,TimeUnit.SECONDS));var ran=new CountDownLatch(1);
   var catalog=new Task<Void>(){protected Void call(){ran.countDown();return null;}};
   assertTrue(AppTaskExecutor.executeCatalog(catalog,e->fail(e)));assertTrue(ran.await(5,TimeUnit.SECONDS));catalog.get(5,TimeUnit.SECONDS);
  }finally{release.countDown();for(var task:blockers)task.get(10,TimeUnit.SECONDS);}
 }
 @RepeatedTest(3) void deadlineInterruptsAStuckReadAndDeliversFxCancellationCleanup()throws Exception{
  var started=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var exited=new CountDownLatch(1);var writes=new AtomicInteger();
  var task=new Task<Void>(){protected Void call()throws Exception{started.countDown();try{new CountDownLatch(1).await(10,TimeUnit.SECONDS);writes.incrementAndGet();return null;}finally{exited.countDown();}}};
  var setup=new FutureTask<Void>(()->{task.setOnCancelled(e->{assertTrue(Platform.isFxApplicationThread());cancelled.countDown();});return null;});Platform.runLater(setup);setup.get(5,TimeUnit.SECONDS);
  assertTrue(AppTaskExecutor.executeCatalog(task,Duration.ofMillis(500),e->fail(e)));assertTrue(started.await(5,TimeUnit.SECONDS));
  boolean observed=cancelled.await(5,TimeUnit.SECONDS);
  String outcome="pending";if(task.isDone())try{task.get();outcome="succeeded";}catch(Exception ended){outcome=ended.toString()+" cause="+ended.getCause();}
  assertTrue(observed,"FX cancellation missing; done="+task.isDone()+" cancelled="+task.isCancelled()+" exited="+exited.getCount()+" outcome="+outcome);
  assertTrue(exited.await(5,TimeUnit.SECONDS),"Canceled read must exit its worker");assertTrue(task.isCancelled());assertEquals(0,writes.get());
 }
}
