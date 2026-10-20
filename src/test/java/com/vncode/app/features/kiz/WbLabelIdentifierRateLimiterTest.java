package com.vncode.app.features.kiz;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WbLabelIdentifierRateLimiterTest {
    @Test void readsAndDeletesStayWithinThreeHundredPerMinute()throws Exception {
        var time=new AtomicLong();var gate=new WbLabelIdentifierRateLimiter.Gate(Duration.ofMillis(200),time::get,time::addAndGet);for(int i=0;i<301;i++)gate.await();assertEquals(Duration.ofMinutes(1).toNanos(),time.get());
    }
    @Test void cooldownReceivedWhileWaitingIsHonoredWithoutHoldingTheGateMonitor()throws Exception {
        var time=new AtomicLong();var first=new AtomicBoolean(true);var gate=new WbLabelIdentifierRateLimiter.Gate[1];gate[0]=new WbLabelIdentifierRateLimiter.Gate(Duration.ofMillis(200),time::get,n->{assertFalse(Thread.holdsLock(gate[0]));if(first.getAndSet(false))gate[0].cooldown(Duration.ofSeconds(5));time.addAndGet(n);});gate[0].await();gate[0].await();assertEquals(Duration.ofSeconds(5).toNanos(),time.get());
    }
    @Test void longReadCooldownCanYieldTheExistingFbsWorkerImmediately()throws Exception {
        var time=new AtomicLong();var gate=new WbLabelIdentifierRateLimiter.Gate(Duration.ofMillis(200),time::get,n->{throw new AssertionError("FBS should yield, not sleep");});gate.cooldown(Duration.ofMinutes(1));try(var deferral=com.vncode.app.shared.RateLimitDeferral.enter()){assertThrows(com.vncode.app.shared.RateLimitDeferral.Deferred.class,gate::await);}
    }
}
