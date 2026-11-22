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
 @Test void localViewRunsWhileBothGeneralWorkersAreBlocked()throws Exception{
  var started=new CountDownLatch(2);var release=new CountDownLatch(1);var blockers=new java.util.ArrayList<Task<Void>>();
  try{
   for(int n=0;n<2;n++){var blocker=new Task<Void>(){protected Void call()throws Exception{started.countDown();release.await(10,TimeUnit.SECONDS);return null;}};blockers.add(blocker);assertTrue(AppTaskExecutor.execute(blocker));}
   assertTrue(started.await(5,TimeUnit.SECONDS));var ran=new CountDownLatch(1);
   var local=new Task<Void>(){protected Void call(){ran.countDown();return null;}};
   AppTaskExecutor.executeLocal(1,local,error->fail(error));assertTrue(ran.await(3,TimeUnit.SECONDS));local.get(3,TimeUnit.SECONDS);
  }finally{release.countDown();for(var blocker:blockers)blocker.get(10,TimeUnit.SECONDS);}
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

 @Test void expiredQueuedReadsFreeCapacityWhileCatalogWorkersAreBusy()throws Exception{
  assertCancelledReadsFreeCapacity(false);
 }

 @Test void manuallyCancelledQueuedReadsFreeCapacityWhileCatalogWorkersAreBusy()throws Exception{
  assertCancelledReadsFreeCapacity(true);
 }

 private void assertCancelledReadsFreeCapacity(boolean cancelManually)throws Exception{
  var started=new CountDownLatch(2);
  var release=new CountDownLatch(1);
  var cancelled=new CountDownLatch(20);
  var blockers=new java.util.ArrayList<Task<Void>>();
  var expiredCalls=new AtomicInteger();
  try{
   for(int i=0;i<2;i++){
    var blocker=new Task<Void>(){
     protected Void call()throws Exception{started.countDown();release.await(15,TimeUnit.SECONDS);return null;}
    };
    blockers.add(blocker);
    assertTrue(AppTaskExecutor.executeCatalog(blocker,error->fail(error)));
   }
   assertTrue(started.await(5,TimeUnit.SECONDS));
   var enqueue=new FutureTask<Void>(()->{
    var queued=new java.util.ArrayList<Task<Void>>();
    for(int i=0;i<20;i++){
     var expired=new Task<Void>(){protected Void call(){expiredCalls.incrementAndGet();return null;}};
     expired.setOnCancelled(event->cancelled.countDown());
     queued.add(expired);
     var deadline=cancelManually?Duration.ofMinutes(1):Duration.ofMillis(300);
     assertTrue(AppTaskExecutor.executeCatalog(expired,deadline,error->fail(error)));
    }
    if(cancelManually)queued.forEach(task->task.cancel(true));
    return null;
   });
   Platform.runLater(enqueue);
   enqueue.get(5,TimeUnit.SECONDS);
   assertTrue(cancelled.await(5,TimeUnit.SECONDS));
   var fresh=new Task<Void>(){protected Void call(){return null;}};
   assertTrue(AppTaskExecutor.executeCatalog(fresh,error->fail(error)),"Cancelled reads still occupy the queue");
   release.countDown();
   fresh.get(5,TimeUnit.SECONDS);
   assertEquals(0,expiredCalls.get());
  }finally{
   release.countDown();
   for(var blocker:blockers)blocker.get(5,TimeUnit.SECONDS);
  }
 }
}
