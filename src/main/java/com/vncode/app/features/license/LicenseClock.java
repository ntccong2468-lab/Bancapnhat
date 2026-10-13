package com.vncode.app.features.license;

import java.time.Clock;
import java.util.function.LongSupplier;

public final class LicenseClock {
    private final Clock wall;
    private final LongSupplier nanos;
    private long highWater, anchorEpoch, anchorNanos;
    public LicenseClock(Clock wall, LongSupplier nanos, long persistedEpoch) {
        this.wall=wall;this.nanos=nanos;this.highWater=Math.max(persistedEpoch,wall.instant().getEpochSecond());this.anchorEpoch=highWater;this.anchorNanos=nanos.getAsLong();
    }
    public synchronized long now() {
        long elapsed=Math.max(0,(nanos.getAsLong()-anchorNanos)/1_000_000_000L);
        highWater=Math.max(highWater,Math.max(wall.instant().getEpochSecond(),anchorEpoch+elapsed));return highWater;
    }
    public synchronized boolean rollbackDetected() { return wall.instant().getEpochSecond() < now()-300; }
    public synchronized long maxObservedEpoch() { return now(); }
}
