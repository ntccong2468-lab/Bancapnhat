package com.vncode.app.integration.wb;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WbPriceApiRateLimiterTest {
    private static String token(int type){return "header."+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"acc\":"+type+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8))+".signature";}
    @Test void onlyPersonalAndServiceTokensAllowGuardedPriceWrites(){
        assertTrue(WbPriceApiRateLimiter.supportsGuardedPriceWrite(token(3)));
        assertTrue(WbPriceApiRateLimiter.supportsGuardedPriceWrite(token(4)));
        assertFalse(WbPriceApiRateLimiter.supportsGuardedPriceWrite(token(1)));
        assertFalse(WbPriceApiRateLimiter.supportsGuardedPriceWrite("unrecognised"));
    }
    @Test void priceReadsAndWritesShareBurstAndRefill()throws Exception {
        var clock=new AtomicLong();var gates=new WbPriceApiRateLimiter.Buckets(clock::get,clock::addAndGet);
        for(int i=0;i<5;i++)gates.awaitTurn(token(3));
        assertEquals(0,clock.get());gates.awaitTurn(token(3));
        assertEquals(Duration.ofMillis(600).toNanos(),clock.get());
        gates.awaitTurn(token(4));assertEquals(Duration.ofMillis(600).toNanos(),clock.get());
    }
    @Test void explicitRateLimitIsRespectedWithoutShorteningFifteenMinuteWait()throws Exception {
        var clock=new AtomicLong();var gates=new WbPriceApiRateLimiter.Buckets(clock::get,clock::addAndGet);
        gates.registerRateLimit(token(3),new Headers.Builder().add("Retry-After","900").build());
        gates.awaitTurn(token(4));assertEquals(0,clock.get());
        gates.awaitTurn(token(3));assertEquals(Duration.ofMinutes(15).toNanos(),clock.get());
        gates.awaitTurn(token(3));assertEquals(Duration.ofMinutes(15).plusMillis(600).toNanos(),clock.get());
    }
    @Test void baseAndUnknownTokensUseConservativeFifteenMinuteQuota()throws Exception {
        var clock=new AtomicLong();var gates=new WbPriceApiRateLimiter.Buckets(clock::get,clock::addAndGet);
        gates.awaitTurn(token(1));gates.awaitTurn(token(1));assertEquals(Duration.ofMinutes(15).toNanos(),clock.get());
        gates.awaitTurn("unknown-token");gates.awaitTurn("unknown-token");assertEquals(Duration.ofMinutes(30).toNanos(),clock.get());
    }
    @Test void deferredSharedCooldownDoesNotSpendBaseQuota()throws Exception {
        var clock=new AtomicLong();var gates=new WbPriceApiRateLimiter.Buckets(clock::get,clock::addAndGet);
        String sid="12345678-1234-1234-1234-123456789abc";
        String fast="h."+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"acc\":3,\"sid\":\""+sid+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8))+".s";
        String base="h."+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"acc\":1,\"sid\":\""+sid+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8))+".s";
        gates.registerRateLimit(fast,new Headers.Builder().add("Retry-After","10").build());
        try(var deferral=com.vncode.app.shared.RateLimitDeferral.enter()){assertThrows(com.vncode.app.shared.RateLimitDeferral.Deferred.class,()->gates.awaitTurn(base));}
        clock.set(Duration.ofSeconds(10).toNanos());gates.awaitTurn(base);assertEquals(Duration.ofSeconds(10).toNanos(),clock.get());
    }
    @Test void differentTokensForTheSameSellerShareTheQuotaAnd429Cooldown()throws Exception {
        var clock=new AtomicLong();var gates=new WbPriceApiRateLimiter.Buckets(clock::get,clock::addAndGet);
        String payload=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"acc\":3,\"sid\":\"12345678-1234-1234-1234-123456789abc\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String first="header."+payload+".one",second="header."+payload+".two";
        for(int i=0;i<5;i++)gates.awaitTurn(i%2==0?first:second);gates.awaitTurn(second);
        assertEquals(Duration.ofMillis(600).toNanos(),clock.get());
        gates.registerRateLimit(first,new Headers.Builder().add("Retry-After","10").build());gates.awaitTurn(second);
        assertEquals(Duration.ofSeconds(10).plusMillis(600).toNanos(),clock.get());
    }
}
