package com.vncode.app.integration.ozon;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OzonBarcodeQuotaTest {
    @Test void tenThousandPairsUseOneHundredRequestsPacedAtTwentyPerMinute()throws Exception {
        var clock=new AtomicLong();
        var limiter=new OzonApiRateLimiter(Duration.ofSeconds(3),Duration.ofSeconds(3),clock::get,clock::addAndGet);
        long previous=-Duration.ofSeconds(3).toNanos();
        for(int i=0;i<100;i++){
            limiter.awaitTurn("same-client-id","gtin-barcodes");
            assertTrue(clock.get()-previous>=Duration.ofSeconds(3).toNanos());previous=clock.get();
        }
        assertEquals(Duration.ofSeconds(297).toNanos(),clock.get());
        limiter.awaitTurn("different-client-id","gtin-barcodes");assertEquals(Duration.ofSeconds(297).toNanos(),clock.get());
        limiter.awaitTurn("same-client-id","gtin-barcodes");assertEquals(Duration.ofSeconds(300).toNanos(),clock.get());
    }
    @Test void explicitCooldownTakesPrecedenceOverAQueuedSlot()throws Exception {
        var clock=new AtomicLong();var limiter=new OzonApiRateLimiter[1];var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        limiter[0]=new OzonApiRateLimiter(Duration.ofSeconds(3),Duration.ofSeconds(3),clock::get,n->{
            if(first.getAndSet(false))limiter[0].registerRateLimit("client","gtin-barcodes",Duration.ofSeconds(90));clock.addAndGet(n);
        });
        limiter[0].awaitTurn("client","gtin-barcodes");limiter[0].awaitTurn("client","gtin-barcodes");
        assertEquals(Duration.ofSeconds(90).toNanos(),clock.get());
        limiter[0].awaitTurn("client","gtin-barcodes");assertEquals(Duration.ofSeconds(93).toNanos(),clock.get());
    }
}
