package com.vncode.app.features.license;

import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LicenseClockTest {
    @Test void vietnamAndRussiaTimeZonesUseTheSameLicenseInstant() {
        var instant=Instant.parse("2026-10-15T00:00:00Z");
        for(String zone:new String[]{"Asia/Ho_Chi_Minh","Europe/Moscow"}) {
            var clock=new LicenseClock(Clock.fixed(instant,ZoneId.of(zone)),()->0,instant.getEpochSecond());
            assertEquals(instant.getEpochSecond(),clock.now());
            assertFalse(clock.rollbackDetected());
        }
    }
    @Test void usesPersistedHighWaterAndDetectsClockRollbackAfterRestart() {
        var wall=new MutableClock(1000);var nanos=new AtomicLong();var clock=new LicenseClock(wall,nanos::get,1400);
        assertEquals(1400,clock.now());assertTrue(clock.rollbackDetected());
        wall.seconds=1200; assertFalse(clock.rollbackDetected(),"Small wall-clock drift has a five-minute tolerance");
        assertEquals(1400,clock.maxObservedEpoch());
    }
    @Test void monotonicElapsedTimePreventsWallClockRollbackExtendingLease() {
        var wall=new MutableClock(1000);var nanos=new AtomicLong(1_000_000_000L);var clock=new LicenseClock(wall,nanos::get,0);
        nanos.addAndGet(400_000_000_000L);wall.seconds=900;
        assertEquals(1400,clock.now());assertTrue(clock.rollbackDetected());
        nanos.addAndGet(30_000_000_000L);assertEquals(1430,clock.now());
        wall.seconds=1430;assertFalse(clock.rollbackDetected());
    }
    @Test void forwardWallJumpAndNegativeNanoDeltaNeverReduceObservedTime() {
        var wall=new MutableClock(1000);var nanos=new AtomicLong(1_000_000_000L);var clock=new LicenseClock(wall,nanos::get,0);
        wall.seconds=2000;assertEquals(2000,clock.now());
        wall.seconds=500;nanos.set(0);assertEquals(2000,clock.now());
        assertEquals(2000,clock.maxObservedEpoch());assertTrue(clock.rollbackDetected());
    }
    private static final class MutableClock extends Clock {
        long seconds;
        MutableClock(long seconds){this.seconds=seconds;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return Instant.ofEpochSecond(seconds);}
    }
}
