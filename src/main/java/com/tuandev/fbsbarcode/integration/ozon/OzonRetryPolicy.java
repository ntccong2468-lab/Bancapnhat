package com.tuandev.fbsbarcode.integration.ozon;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public final class OzonRetryPolicy {
    private final int maximumAttempts;
    private final Duration baseDelay;
    private final Duration maximumDelay;

    public OzonRetryPolicy() {
        this(4, Duration.ofMillis(300), Duration.ofSeconds(5));
    }

    public OzonRetryPolicy(int maximumAttempts, Duration baseDelay, Duration maximumDelay) {
        if (maximumAttempts < 1 || maximumAttempts > 10) {
            throw new IllegalArgumentException("maximumAttempts must be between 1 and 10");
        }
        this.maximumAttempts = maximumAttempts;
        this.baseDelay = requirePositive(baseDelay, "baseDelay");
        this.maximumDelay = requirePositive(maximumDelay, "maximumDelay");
    }

    public int maximumAttempts() {
        return maximumAttempts;
    }

    public boolean isRetryableStatus(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    public Duration delay(int completedAttempts, Duration retryAfter) {
        if (retryAfter != null && !retryAfter.isNegative() && !retryAfter.isZero()) {
            return retryAfter.compareTo(maximumDelay) > 0 ? maximumDelay : retryAfter;
        }
        int exponent = Math.max(0, Math.min(completedAttempts - 1, 10));
        long cap = Math.min(maximumDelay.toMillis(), baseDelay.toMillis() * (1L << exponent));
        long jittered = cap <= 1 ? cap : ThreadLocalRandom.current().nextLong(Math.max(1, cap / 2), cap + 1);
        return Duration.ofMillis(jittered);
    }

    public static Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.strip());
            return seconds > 0 ? Duration.ofSeconds(Math.min(seconds, 60)) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
