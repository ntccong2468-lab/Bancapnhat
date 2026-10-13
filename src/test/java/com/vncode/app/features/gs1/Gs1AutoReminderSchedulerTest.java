package com.vncode.app.features.gs1;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class Gs1AutoReminderSchedulerTest {
    @Test void closeWaitsForAnAcceptedMailOperationAndPreventsLaterChecks()throws Exception{
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var checks=new AtomicInteger();
        try(var scheduler=new Gs1AutoReminderScheduler(()->{
            checks.incrementAndGet();started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        },0,10_000)){
            scheduler.start();assertTrue(started.await(3,TimeUnit.SECONDS));assertFalse(scheduler.tryStop());
            release.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);boolean stopped=false;
            while(!stopped&&System.nanoTime()<deadline){stopped=scheduler.tryStop();if(!stopped)Thread.sleep(5);}
            assertTrue(stopped);scheduler.start();assertEquals(1,checks.get());
        }finally{release.countDown();}
    }
}
