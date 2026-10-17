package com.vncode.app.integration.ozon;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Small per-shop/per-endpoint-family limiter; Ozon traffic never shares a WB limiter. */
public final class OzonApiRateLimiter {
    private static final long MAX_COOLDOWN_NANOS = Duration.ofMinutes(10).toNanos();
    private final ConcurrentMap<Key, Long> nextAllowedNanos = new ConcurrentHashMap<>();
    private final ConcurrentMap<Key, Long> cooldownUntilNanos = new ConcurrentHashMap<>();
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

    public void awaitTurn(int shopId,String family)throws InterruptedException{if(shopId<=0)throw new IllegalArgumentException("Invalid shop");awaitTurn("shop:"+shopId,family);}
    public void awaitTurn(String scope, String family) throws InterruptedException {
        if (scope==null || scope.isBlank() || family == null || !family.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("A valid shop and endpoint family are required");
        }
        Key key = new Key(scope, family);
        long effectiveSpacing = "fbo-supplies".equals(family) ? fboSpacingNanos : spacingNanos;
        long now = System.nanoTime();
        long reserved = nextAllowedNanos.compute(key, (ignored, existing) -> {
            long start = existing == null ? now : Math.max(now, existing);
            return start + effectiveSpacing;
        }) - effectiveSpacing;
        while (true) {
            long currentTime = System.nanoTime();
            Long cooldown = cooldownUntilNanos.get(key);
            if (cooldown != null && cooldown > reserved && cooldown > currentTime) {
                // A 429 may arrive while this caller sleeps on an older slot. Reserve again
                // after that cooldown, preserving spacing among all waiting callers.
                long afterCooldown = Math.max(currentTime, cooldown);
                reserved = nextAllowedNanos.compute(key, (ignored, existing) ->
                        Math.max(afterCooldown, existing == null ? afterCooldown : existing) + effectiveSpacing)
                        - effectiveSpacing;
            }
            long wait = reserved - currentTime;
            if (wait <= 0) return;
            com.vncode.app.shared.RateLimitDeferral.waiting(wait);
            Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
        }
    }

    public void registerRateLimit(int shopId,String family,Duration retryAfter){if(shopId<=0)return;registerRateLimit("shop:"+shopId,family,retryAfter);}
    public void registerRateLimit(String scope, String family, Duration retryAfter) {
        if (scope==null || scope.isBlank() || family == null || family.isBlank()) return;
        Duration safe = retryAfter == null || retryAfter.isZero() || retryAfter.isNegative()
                ? Duration.ofSeconds(60) : retryAfter;
        long cooldown = safe.compareTo(Duration.ofMinutes(10)) > 0
                ? MAX_COOLDOWN_NANOS : safe.toNanos();
        Key key = new Key(scope, family);
        long until = System.nanoTime() + cooldown;
        cooldownUntilNanos.compute(key, (ignored, existing) -> existing == null ? until : Math.max(existing, until));
        nextAllowedNanos.compute(key, (ignored, existing) -> existing == null ? until : Math.max(existing, until));
    }

    private record Key(String scope, String family) {
        private Key {
            Objects.requireNonNull(family, "family");
        }
    }
}
