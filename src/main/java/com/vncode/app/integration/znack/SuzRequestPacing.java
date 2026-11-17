package com.vncode.app.integration.znack;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/** Admission pacing shared by SUZ calls of one remote OMS account, not by all shops. */
final class SuzRequestPacing {
    private static final long INTERVAL_MILLIS = 500;
    private final LongSupplier clock;
    private final Map<String, Long> deadlines = new HashMap<>();

    SuzRequestPacing() { this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime())); }
    SuzRequestPacing(LongSupplier clock) { this.clock = java.util.Objects.requireNonNull(clock); }

    /** Raised only while awaiting admission, before a transport call has been created. */
    public static final class DeferredException extends IOException {
        private final Duration retryAfter;
        private final Instant retryAt;
        DeferredException(long delayMillis,IOException cause) {
            super("SUZ_REQUEST_PACING_DEFERRED",cause);
            retryAfter=Duration.ofMillis(Math.max(1,delayMillis));
            Instant deadline;
            try { deadline=Instant.now().plus(retryAfter); }
            catch(java.time.DateTimeException|ArithmeticException overflow) { deadline=Instant.MAX; }
            retryAt=deadline;
        }
        public Duration retryAfter() { return retryAfter; }
        public Instant retryAt() { return retryAt; }
    }

    synchronized long reserve(String account) {
        long now = clock.getAsLong();
        Long deadline = deadlines.get(account);
        if (deadline != null && deadline > now) return distance(deadline, now);
        deadlines.values().removeIf(value -> value <= now);
        deadlines.put(account, add(now, INTERVAL_MILLIS));
        return 0;
    }

    synchronized void defer(String account, long delayMillis) {
        long until = add(clock.getAsLong(), Math.max(INTERVAL_MILLIS, delayMillis));
        deadlines.merge(account, until, Math::max);
    }

    private static long add(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }
    private static long distance(long deadline, long now) {
        try { return Math.subtractExact(deadline, now); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }
}
