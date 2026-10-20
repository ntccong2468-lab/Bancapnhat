package com.vncode.app.shared;

import java.time.Duration;

/** Only paged background reads may yield a worker instead of sleeping through API cooldown. */
public final class RateLimitDeferral implements AutoCloseable {
    private static final ThreadLocal<Boolean> ACTIVE=ThreadLocal.withInitial(()->false);
    private final boolean prior;
    private RateLimitDeferral(){prior=ACTIVE.get();ACTIVE.set(true);}
    public static RateLimitDeferral enter(){return new RateLimitDeferral();}
    public static boolean active(){return ACTIVE.get();}
    public static void waiting(long nanos){if(active()&&nanos>Duration.ofSeconds(1).toNanos())throw new Deferred(Duration.ofNanos(nanos));}
    public static final class Deferred extends RuntimeException {
        private final Duration delay;
        private Deferred(Duration delay){super("API_COOLDOWN");this.delay=delay;}
        public Duration delay(){return delay;}
    }
    @Override public void close(){if(prior)ACTIVE.set(true);else ACTIVE.remove();}
}
