package com.vncode.app.features.license;

import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/** Spread automatic checks across clients, below the portal's 30 renewals/hour/account limit. */
final class LicenseRefreshCadence {
    private final IntSupplier jitter;
    private volatile long lastAttempt,delay;
    private volatile boolean attempted;
    private int failures;
    LicenseRefreshCadence(IntSupplier jitter){this.jitter=jitter;}
    boolean due(long now){return !attempted||now-lastAttempt>=delay;}
    synchronized void completed(long now,boolean successful){
        failures=successful?0:Math.min(3,failures+1);
        int extra=Math.floorMod(jitter.getAsInt(),31);
        long seconds=successful?150L+extra:Math.min(900L,180L<<(failures-1))+extra;
        delay=TimeUnit.SECONDS.toNanos(seconds);lastAttempt=now;attempted=true;
    }
    synchronized void reset(){attempted=false;failures=0;}
}
