package com.tuandev.fbsbarcode.integration.wb.fbw;

import okhttp3.Headers;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class WbFbwRateLimiter {
    private static final long MAX_COOLDOWN_MILLIS = Duration.ofMinutes(10).toMillis();
    private final ConcurrentMap<Integer, Bucket> buckets = new ConcurrentHashMap<>();
    private final long spacingNanos;

    public WbFbwRateLimiter() {
        this(Duration.ofSeconds(2));
    }

    public WbFbwRateLimiter(Duration spacing) {
        if (spacing == null || spacing.isNegative()) {
            throw new IllegalArgumentException("spacing must not be negative");
        }
        spacingNanos = spacing.toNanos();
    }

    public void awaitTurn(int shopId) throws InterruptedException {
        if (shopId <= 0) throw new IllegalArgumentException("shopId must be positive");
        Bucket bucket = buckets.computeIfAbsent(shopId, ignored -> new Bucket());
        long waitNanos;
        synchronized (bucket) {
            long now = System.nanoTime();
            long start = Math.max(now, Math.max(bucket.nextAllowedNanos, bucket.cooldownUntilNanos));
            bucket.nextAllowedNanos = start + spacingNanos;
            waitNanos = start - now;
        }
        if (waitNanos > 0) {
            long millis = waitNanos / 1_000_000L;
            int nanos = (int) (waitNanos % 1_000_000L);
            Thread.sleep(millis, nanos);
        }
    }

    public void registerRateLimit(int shopId, Headers headers) {
        long seconds = positiveLong(headers == null ? null : headers.get("Retry-After"));
        long cooldownMillis = Math.min(MAX_COOLDOWN_MILLIS, Math.max(2_000L, seconds * 1_000L));
        Bucket bucket = buckets.computeIfAbsent(shopId, ignored -> new Bucket());
        synchronized (bucket) {
            bucket.cooldownUntilNanos = Math.max(
                    bucket.cooldownUntilNanos,
                    System.nanoTime() + Duration.ofMillis(cooldownMillis).toNanos());
        }
    }

    private static long positiveLong(String value) {
        try {
            return Math.max(1L, Long.parseLong(value == null ? "" : value.strip()));
        } catch (NumberFormatException ignored) {
            return 60L;
        }
    }

    private static final class Bucket {
        private long nextAllowedNanos;
        private long cooldownUntilNanos;
    }
}
