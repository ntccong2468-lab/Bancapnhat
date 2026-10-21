package com.vncode.app.shared;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class SellerMutationExecutorTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void separateShopsCanProgressTogetherWhileSameShopRemainsSerial()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());
        com.vncode.app.config.Database.initDatabase();
        var firstShop=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);
        var secondShop=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(com.vncode.app.integration.marketplace.Marketplace.OZON);
        var firstStarted=new CountDownLatch(1);var secondStarted=new CountDownLatch(1);var release=new CountDownLatch(1);
        var sameShopStarted=new java.util.concurrent.atomic.AtomicBoolean();
        var first=SellerMutationExecutor.submit(firstShop,()->{firstStarted.countDown();release.await(10,TimeUnit.SECONDS);return 1;});
        CompletableFuture<Integer> same=null,other=null;
        try{
            assertTrue(firstStarted.await(3,TimeUnit.SECONDS));
            same=SellerMutationExecutor.submit(firstShop,()->{sameShopStarted.set(true);return 2;});
            other=SellerMutationExecutor.submit(secondShop,()->{secondStarted.countDown();release.await(10,TimeUnit.SECONDS);return 3;});
            assertTrue(secondStarted.await(3,TimeUnit.SECONDS),"A queued same-shop write must not block another shop");
            assertFalse(sameShopStarted.get());assertEquals(2,SellerMutationExecutor.activeCount());
        }finally{
            release.countDown();try{first.get(10,TimeUnit.SECONDS);if(same!=null)same.get(10,TimeUnit.SECONDS);if(other!=null)other.get(10,TimeUnit.SECONDS);}finally{System.clearProperty("vncode.appdata.dir");}
        }
        assertTrue(sameShopStarted.get());
    }
    @org.junit.jupiter.api.Test void acceptedMutationKeepsApplicationCloseGuardBusy()throws Exception {
        var started=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var future=SellerMutationExecutor.submit(()->{started.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);return true;});
        try{org.junit.jupiter.api.Assertions.assertTrue(started.await(3,java.util.concurrent.TimeUnit.SECONDS));org.junit.jupiter.api.Assertions.assertTrue(AppTaskExecutor.hasRunningTasks(),"Application close guard must include accepted seller mutations");}finally{release.countDown();future.get(5,java.util.concurrent.TimeUnit.SECONDS);}
    }
    @Test void fullMutationQueueKeepsAcceptedOwnersAndRejectsBeforeWrite()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var owners=new java.util.concurrent.ConcurrentLinkedQueue<Integer>();var rejectedWrites=new AtomicInteger();
        var first=SellerMutationExecutor.submit(()->{entered.countDown();release.await(10,TimeUnit.SECONDS);owners.add(1);return 1;});
        assertTrue(entered.await(5,TimeUnit.SECONDS));var accepted=new java.util.ArrayList<CompletableFuture<Integer>>();
        try {for(int i=0;i<32;i++){int owner=i%10+1;accepted.add(SellerMutationExecutor.submit(()->{owners.add(owner);return owner;}));}assertEquals(32,SellerMutationExecutor.pendingCount());
            var rejected=SellerMutationExecutor.submit(()->{rejectedWrites.incrementAndGet();return 99;});assertInstanceOf(RejectedExecutionException.class,assertThrows(CompletionException.class,rejected::join).getCause());assertEquals(0,rejectedWrites.get());
        }finally{release.countDown();first.get(10,TimeUnit.SECONDS);for(var next:accepted)next.get(10,TimeUnit.SECONDS);}
        assertEquals(33,owners.size());assertEquals(2,SellerMutationExecutor.workerLimit());
    }
}
