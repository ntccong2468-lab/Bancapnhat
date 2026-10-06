package com.tuandev.fbsbarcode.features.kiz;

import okhttp3.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

class WbLabelIdentifierRateLimiter {
    private static final Logger LOGGER = LoggerFactory.getLogger(WbLabelIdentifierRateLimiter.class);
    private static final long MIN_INTERVAL_MS = 70L;
    private static final long CLIENT_ERROR_PENALTY_MS = 700L;
    private static final long DEFAULT_RATE_LIMIT_COOLDOWN_MS = Duration.ofMinutes(1).toMillis();
    private static final long MAX_RATE_LIMIT_COOLDOWN_MS = Duration.ofMinutes(10).toMillis();
    private static final ConcurrentHashMap<String, Bucket> BUCKETS = new ConcurrentHashMap<>();

    private WbLabelIdentifierRateLimiter() {
    }

    static void awaitTurn(String apiKey) throws IOException {
        Bucket bucket = bucketFor(apiKey);
        synchronized (bucket) {
            while (true) {
                long now = System.currentTimeMillis();
                long waitUntil = Math.max(bucket.nextAllowedAtMs, bucket.cooldownUntilMs);
                long waitMs = waitUntil - now;
                if (waitMs <= 0) {
                    bucket.nextAllowedAtMs = now + MIN_INTERVAL_MS;
                    return;
                }
                waitQuietly(waitMs);
            }
        }
    }

    static void registerResponse(String apiKey, int statusCode, Headers headers) {
        if (statusCode < 400) {
            return;
        }
        Bucket bucket = bucketFor(apiKey);
        synchronized (bucket) {
            long now = System.currentTimeMillis();
            if (statusCode == 429) {
                long cooldownMs = resolveCooldownMs(headers);
                long cooldownUntil = now + cooldownMs;
                bucket.cooldownUntilMs = Math.max(bucket.cooldownUntilMs, cooldownUntil);
                bucket.nextAllowedAtMs = Math.max(bucket.nextAllowedAtMs, cooldownUntil);
                LOGGER.warn("WB FBS label identifier cooldown set for {} ms", cooldownMs);
                return;
            }
            bucket.nextAllowedAtMs = Math.max(bucket.nextAllowedAtMs, now + CLIENT_ERROR_PENALTY_MS);
        }
    }

    private static Bucket bucketFor(String apiKey) {
        return BUCKETS.computeIfAbsent(fingerprint(apiKey), ignored -> new Bucket());
    }

    private static String fingerprint(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        return Integer.toHexString(apiKey.hashCode());
    }

    private static long resolveCooldownMs(Headers headers) {
        Long retryAfterSeconds = parsePositiveLong(firstHeader(headers, "Retry-After"));
        if (retryAfterSeconds != null) {
            return clamp(retryAfterSeconds * 1000L, MIN_INTERVAL_MS, MAX_RATE_LIMIT_COOLDOWN_MS);
        }
        Long resetSeconds = parsePositiveLong(firstHeader(headers, "X-Ratelimit-Reset", "X-RateLimit-Reset"));
        if (resetSeconds != null) {
            return clamp(resetSeconds * 1000L, MIN_INTERVAL_MS, MAX_RATE_LIMIT_COOLDOWN_MS);
        }
        return DEFAULT_RATE_LIMIT_COOLDOWN_MS;
    }

    private static String firstHeader(Headers headers, String... names) {
        if (headers == null) {
            return null;
        }
        for (String name : names) {
            String value = headers.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Long parsePositiveLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void waitQuietly(long waitMs) throws IOException {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for WB FBS label identifier rate limit", ex);
        }
    }

    private static class Bucket {
        private long nextAllowedAtMs;
        private long cooldownUntilMs;
    }
}
