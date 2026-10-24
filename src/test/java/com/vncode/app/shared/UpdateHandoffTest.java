package com.vncode.app.shared;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UpdateHandoffTest {
    @Test void installationHoldsNewWorkUntilCancelledOrFinished() throws Exception {
        try (var executor=Executors.newSingleThreadExecutor()) {
            Future<Integer> queued;
            try (var pause=UpdateHandoff.pause()) {
                queued=executor.submit(()->{try(var work=UpdateHandoff.work()){return 42;}});
                assertThrows(TimeoutException.class,()->queued.get(30,TimeUnit.MILLISECONDS));
                assertTrue(UpdateHandoff.awaitIdle(30));
            }
            assertEquals(42,queued.get(2,TimeUnit.SECONDS));
        }
    }
    @Test void currentWriteDrainsBeforeSnapshotWhileItsContinuationCanFinish() throws Exception {
        try (var executor=Executors.newSingleThreadExecutor();var write=UpdateHandoff.work();var pause=UpdateHandoff.pause()) {
            var ticket=UpdateHandoff.continuation();
            assertEquals(7,executor.submit(()->{try(var child=UpdateHandoff.work(ticket)){return 7;}}).get(2,TimeUnit.SECONDS));
            assertFalse(UpdateHandoff.awaitIdle(1));
            write.close();
            assertTrue(UpdateHandoff.awaitIdle(10));
        }
    }
    @Test void savedDurableBoundaryPausesTheRestOfALargeQueue() throws Exception {
        var entered=new CountDownLatch(1);var boundary=new CountDownLatch(1);var saved=new AtomicInteger();
        try (var executor=Executors.newSingleThreadExecutor()) {
            var worker=executor.submit(()->{try(var work=UpdateHandoff.work()){
                saved.incrementAndGet();entered.countDown();boundary.await();UpdateHandoff.checkpoint();saved.incrementAndGet();
            }return null;});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            try (var pause=UpdateHandoff.pause()) {
                boundary.countDown();
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(!UpdateHandoff.awaitIdle(10)&&System.nanoTime()<until) { }
                assertTrue(UpdateHandoff.awaitIdle(10));assertEquals(1,saved.get());assertFalse(worker.isDone());
            }
            worker.get(2,TimeUnit.SECONDS);assertEquals(2,saved.get());
        }
    }
    @Test void anOldContinuationCannotBypassTheInstaller() throws Exception {
        UpdateHandoff.Ticket ticket;
        try(var work=UpdateHandoff.work()){ticket=UpdateHandoff.continuation();}
        try(var executor=Executors.newSingleThreadExecutor()) {
            Future<Integer> queued;
            try(var pause=UpdateHandoff.pause()) {
                queued=executor.submit(()->{try(var work=UpdateHandoff.work(ticket)){return 1;}});
                assertThrows(TimeoutException.class,()->queued.get(30,TimeUnit.MILLISECONDS));
                assertTrue(UpdateHandoff.awaitIdle(10));
            }
            assertEquals(1,queued.get(2,TimeUnit.SECONDS));
        }
    }
}
