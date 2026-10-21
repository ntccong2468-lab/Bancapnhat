package com.vncode.app.shared;

import javafx.application.Platform;
import javafx.concurrent.Task;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AppTaskExecutorTest {
    @BeforeAll static void fx() throws Exception {var ready=new CountDownLatch(1);try{Platform.startup(ready::countDown);}catch(IllegalStateException started){Platform.runLater(ready::countDown);}assertTrue(ready.await(5,TimeUnit.SECONDS));Platform.setImplicitExit(false);}
    @Test void fullQueueRejectsBeforeActionAndDoesNotLeaveBusy() throws Exception {
        var started=new CountDownLatch(2);var release=new CountDownLatch(1);var accepted=new java.util.ArrayList<Task<Void>>();var writes=new AtomicInteger();var busy=new AtomicBoolean(true);
        try {
            for(int i=0;i<2;i++){var task=new Task<Void>(){protected Void call()throws Exception{started.countDown();release.await(10,TimeUnit.SECONDS);return null;}};accepted.add(task);assertTrue(AppTaskExecutor.execute(task));}
            assertTrue(started.await(5,TimeUnit.SECONDS));
            for(int i=0;i<64;i++){var task=new Task<Void>(){protected Void call(){return null;}};accepted.add(task);assertTrue(AppTaskExecutor.execute(task));}
            var rejected=new Task<Void>(){protected Void call(){writes.incrementAndGet();return null;}};
            var result=new FutureTask<Void>(()->{if(!AppTaskExecutor.execute(rejected))busy.set(false);return null;});Platform.runLater(result);result.get(5,TimeUnit.SECONDS);
            assertFalse(busy.get());assertTrue(rejected.isCancelled());assertEquals(0,writes.get());assertEquals(66,AppTaskExecutor.getRunningTaskCount());
        } finally {release.countDown();for(var task:accepted)task.get(10,TimeUnit.SECONDS);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(AppTaskExecutor.hasRunningTasks()&&System.nanoTime()<deadline)Thread.sleep(5);assertFalse(AppTaskExecutor.hasRunningTasks());}
    }
}
