package com.vncode.app.shared;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ShopReadCoordinatorTest {
    @Test void duplicatesShareOneRead() throws Exception {
        try(var reads=new ShopReadCoordinator(2,64)) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
            var key=new ShopReadCoordinator.Key(1,"products","filter");
            var first=reads.submit(key,false,()->{calls.incrementAndGet();entered.countDown();release.await(5,TimeUnit.SECONDS);return "snapshot";});
            assertTrue(entered.await(5,TimeUnit.SECONDS));var second=reads.submit(key,true,()->"wrong");
            assertSame(first,second);release.countDown();assertEquals("snapshot",second.get(5,TimeUnit.SECONDS));assertEquals(1,calls.get());
        }
    }
    @Test void twoWorkerLimitAndBoundedQueue() throws Exception {
        try(var reads=new ShopReadCoordinator(2,64)) {
            var started=new CountDownLatch(2);var release=new CountDownLatch(1);
            for(int i=0;i<2;i++)reads.submit(new ShopReadCoordinator.Key(i+1,"blocked",""),false,()->{started.countDown();release.await(5,TimeUnit.SECONDS);return 1;});
            assertTrue(started.await(5,TimeUnit.SECONDS));
            for(int i=0;i<64;i++)reads.submit(new ShopReadCoordinator.Key(3,"queued",String.valueOf(i)),false,()->1);
            assertEquals(2,reads.activeCount());assertEquals(64,reads.pendingCount());
            assertTrue(reads.submit(new ShopReadCoordinator.Key(4,"overflow",""),false,()->1).isCompletedExceptionally());
            var visible=reads.submit(new ShopReadCoordinator.Key(4,"visible",""),true,()->2);
            assertEquals(64,reads.pendingCount());release.countDown();assertEquals(2,visible.get(5,TimeUnit.SECONDS));
        }
    }
    @Test void boundedQueueRejectsOrEvictsOnlyReadsAndCancelsByOwner() throws Exception {
        try(var reads=new ShopReadCoordinator(1,1)) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var active=reads.submit(new ShopReadCoordinator.Key(1,"blocked",""),false,()->{entered.countDown();release.await(5,TimeUnit.SECONDS);return 1;});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var old=reads.submit(new ShopReadCoordinator.Key(1,"queued",""),false,()->1);
            var next=reads.submit(new ShopReadCoordinator.Key(2,"visible",""),true,()->2);
            assertTrue(old.isCancelled());reads.cancelReads(1);assertTrue(active.isCancelled());
            assertEquals(2,next.get(5,TimeUnit.SECONDS));
        }
    }
}
