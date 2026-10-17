package com.vncode.app.shared;

import com.google.gson.JsonObject;
import com.vncode.app.integration.ozon.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MarketplaceRequestBudgetTest {
    @Test void stalledBackgroundReadLeavesARequestSlotForFbs() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var threads=Executors.newFixedThreadPool(3)) {
            var background=threads.submit(()->{
                try(var lane=MarketplaceRequestBudget.lane(true);var slot=MarketplaceRequestBudget.acquire()) {
                    entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;
                }
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var waiting=new CountDownLatch(1);
            var anotherCatalog=threads.submit(()->{
                try(var lane=MarketplaceRequestBudget.lane(true)) {
                    waiting.countDown();try(var slot=MarketplaceRequestBudget.acquire()){return null;}
                }
            });
            try {
                assertTrue(waiting.await(5,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->anotherCatalog.get(150,TimeUnit.MILLISECONDS));
                threads.submit(()->{try(var lane=MarketplaceRequestBudget.lane(false);var slot=MarketplaceRequestBudget.acquire()){
                    assertEquals(2,MarketplaceRequestBudget.activeCount());return null;
                }}).get(1,TimeUnit.SECONDS);
            } finally {release.countDown();}
            background.get(5,TimeUnit.SECONDS);anotherCatalog.get(5,TimeUnit.SECONDS);
        }
        assertEquals(0,MarketplaceRequestBudget.activeCount());
    }
    @Test void tenShopRequestsNeverExceedTwoInFlight() throws Exception {
        try(var server=new MockWebServer();var threads=Executors.newFixedThreadPool(10)) {
            var current=new AtomicInteger();var maximum=new AtomicInteger();var firstTwo=new CountDownLatch(2);var release=new CountDownLatch(1);
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){public MockResponse dispatch(RecordedRequest request)throws InterruptedException {int active=current.incrementAndGet();maximum.accumulateAndGet(active,Math::max);firstTwo.countDown();try{release.await(5,TimeUnit.SECONDS);return new MockResponse().setBody("{}");}finally{current.decrementAndGet();}}});server.start();
            var futures=new java.util.ArrayList<Future<?>>();
            try {
                for(int shop=1;shop<=10;shop++){var client=client(server,shop,1000);futures.add(threads.submit(()->{try{client.listProducts("",1);}catch(Exception failure){throw new RuntimeException(failure);}}));}
                assertTrue(firstTwo.await(5,TimeUnit.SECONDS));Thread.sleep(100);assertEquals(2,maximum.get());
            } finally {release.countDown();}
            for(var future:futures)future.get(10,TimeUnit.SECONDS);assertEquals(10,server.getRequestCount());assertEquals(2,maximum.get());
        }
    }
    @Test void interruptedWaitReleasesNoForeignPermit() throws Exception {
        try(var first=MarketplaceRequestBudget.acquire();var second=MarketplaceRequestBudget.acquire()) {
            var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
            var waiter=new Thread(()->{entered.countDown();try(var slot=MarketplaceRequestBudget.acquire()){fail("No permit should be available");}catch(java.io.InterruptedIOException expected){interrupted.countDown();}});waiter.start();assertTrue(entered.await(5,TimeUnit.SECONDS));waiter.interrupt();assertTrue(interrupted.await(5,TimeUnit.SECONDS));waiter.join(1000);
            assertEquals(2,MarketplaceRequestBudget.activeCount());
        }
        assertEquals(0,MarketplaceRequestBudget.activeCount());
    }
    @Test void timeoutReleasesSlotForNextShop() throws Exception {
        try(var server=new MockWebServer()) {server.start();server.enqueue(new MockResponse().setBody("{}").setBodyDelay(500,TimeUnit.MILLISECONDS));server.enqueue(new MockResponse().setBody("{}"));
            assertThrows(OzonApiException.class,()->client(server,1,100).ship(new JsonObject()));
            assertEquals(0,MarketplaceRequestBudget.activeCount());assertNotNull(client(server,2,1000).listProducts("",1));assertEquals(0,MarketplaceRequestBudget.activeCount());assertEquals(2,server.getRequestCount());
        }
    }
    private static OzonApiClient client(MockWebServer server,int shop,int timeout) {return new OzonApiClient(shop,new OzonCredentials("client-"+shop,"fixture"),server.url("/"),new OkHttpClient.Builder().callTimeout(timeout,TimeUnit.MILLISECONDS).readTimeout(timeout,TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1)));}
}
