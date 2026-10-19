package com.vncode.app.integration.wb;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WbContentApiRateLimiterTest {
    @Test void updateBucketAllowsOfficialBurstThenRefillsAtTenPerMinute()throws Exception {
        var clock=new AtomicLong();
        var gate=new WbContentApiRateLimiter.Gate(Duration.ofSeconds(6),5,clock::get,clock::addAndGet);
        for(int i=0;i<5;i++)gate.await();
        assertEquals(0,clock.get(),"10,000 small cards need four requests, within the official burst");
        gate.await();assertEquals(Duration.ofSeconds(6).toNanos(),clock.get());
        for(int i=0;i<9;i++)gate.await();
        assertEquals(Duration.ofSeconds(60).toNanos(),clock.get());
    }
    @Test void contentReadsHaveTheirOwnHundredPerMinuteBucket()throws Exception {
        var clock=new AtomicLong();
        var reads=new WbContentApiRateLimiter.Gate(Duration.ofMillis(600),5,clock::get,clock::addAndGet);
        var writes=new WbContentApiRateLimiter.Gate(Duration.ofSeconds(6),5,clock::get,clock::addAndGet);
        for(int i=0;i<105;i++)reads.await();
        assertEquals(Duration.ofMinutes(1).toNanos(),clock.get());
        for(int i=0;i<5;i++)writes.await();
        assertEquals(Duration.ofMinutes(1).toNanos(),clock.get());
    }
    @Test void newCooldownInterruptsAnAlreadyWaitingRequestAndDoesNotBurstOnRecovery()throws Exception {
        var clock=new AtomicLong();var gate=new WbContentApiRateLimiter.Gate[1];var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        gate[0]=new WbContentApiRateLimiter.Gate(Duration.ofSeconds(6),5,clock::get,n->{
            if(first.getAndSet(false))gate[0].cooldown(Duration.ofSeconds(30));clock.addAndGet(n);
        });
        for(int i=0;i<6;i++)gate[0].await();assertEquals(Duration.ofSeconds(30).toNanos(),clock.get());
        gate[0].await();assertEquals(Duration.ofSeconds(36).toNanos(),clock.get());
    }
    @Test void monotonicClockMayStartBelowZero()throws Exception {
        var clock=new AtomicLong(-Duration.ofHours(1).toNanos());long start=clock.get();
        var gate=new WbContentApiRateLimiter.Gate(Duration.ofSeconds(6),5,clock::get,clock::addAndGet);
        for(int i=0;i<5;i++)gate.await();assertEquals(start,clock.get());
        gate.await();assertEquals(start+Duration.ofSeconds(6).toNanos(),clock.get());
    }
}
