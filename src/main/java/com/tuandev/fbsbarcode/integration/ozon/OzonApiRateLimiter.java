package com.tuandev.fbsbarcode.integration.ozon;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Small per-shop/per-endpoint-family limiter; Ozon traffic never shares a WB limiter. */
public final class OzonApiRateLimiter {
    private static final long MAX_COOLDOWN_NANOS = Duration.ofMinutes(10).toNanos();
    private final ConcurrentMap<Key, Long> nextAllowedNanos = new ConcurrentHashMap<>();
    private final long spacingNanos;
    private final long fboSpacingNanos;

    public OzonApiRateLimiter() {
        this(Duration.ofMillis(120), Duration.ofSeconds(1));
    }

    public OzonApiRateLimiter(Duration minimumSpacing) {
        this(minimumSpacing, minimumSpacing);
    }

    private OzonApiRateLimiter(Duration minimumSpacing, Duration fboMinimumSpacing) {
        if (minimumSpacing == null || minimumSpacing.isNegative()
                || fboMinimumSpacing == null || fboMinimumSpacing.isNegative()) {
            throw new IllegalArgumentException("minimumSpacing must not be negative");
        }
        spacingNanos = minimumSpacing.toNanos();
        fboSpacingNanos = fboMinimumSpacing.toNanos();
    }

    public void awaitTurn(int shopId, String family) throws InterruptedException {
        if (shopId <= 0 || family == null || !family.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("A valid shop and endpoint family are required");
        }
        Key key = new Key(shopId, family);
        long effectiveSpacing = "fbo-supplies".equals(family) ? fboSpacingNanos : spacingNanos;
        while (true) {
            long now = System.nanoTime();
            long reserved = nextAllowedNanos.compute(key, (ignored, existing) -> {
                long start = existing == null ? now : Math.max(now, existing);
                return start + effectiveSpacing;
            }) - effectiveSpacing;
            long wait = reserved - now;
            if (wait <= 0) {
                return;
            }
            long millis = wait / 1_000_000L;
            int nanos = (int) (wait % 1_000_000L);
            Thread.sleep(millis, nanos);
            return;
        }
    }

    public void registerRateLimit(int shopId, String family, Duration retryAfter) {
        if (shopId <= 0 || family == null || family.isBlank()) return;
        Duration safe = retryAfter == null || retryAfter.isZero() || retryAfter.isNegative()
                ? Duration.ofSeconds(60) : retryAfter;
        long cooldown = safe.compareTo(Duration.ofMinutes(10)) > 0
                ? MAX_COOLDOWN_NANOS : safe.toNanos();
        Key key = new Key(shopId, family);
        long until = System.nanoTime() + cooldown;
        nextAllowedNanos.compute(key, (ignored, existing) -> existing == null ? until : Math.max(existing, until));
    }

    private record Key(int shopId, String family) {
        private Key {
            Objects.requireNonNull(family, "family");
        }
    }
}
