package com.vncode.app.integration.wb;

import com.vncode.app.features.sync.SyncFailure;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class WbMarketplaceCooldownTest {
    @Test void schedulerUsesTheServersCooldownRatherThanAlwaysWaitingOneMinute() {
        var delay = WbMarketplaceApiRateLimiter.registerRateLimit("fixture-3-second", Headers.of("Retry-After", "3"));
        var failure = SyncFailure.of(new WbApiException("limited", 429, "", delay), 0);
        assertEquals("WB_RATE_LIMIT", failure.code());
        assertEquals(Duration.ofSeconds(3), failure.delay());
        assertEquals(Duration.ofMinutes(1), SyncFailure.of(new WbApiException("limited", 429, ""), 0).delay());
    }

    @Test void laterOfBothRateLimitHeadersIsRespected() {
        var delay = WbMarketplaceApiRateLimiter.registerRateLimit("fixture-two-headers", Headers.of("Retry-After", "8", "X-Ratelimit-Reset", "5"));
        assertEquals(Duration.ofSeconds(8), delay);
    }
}
