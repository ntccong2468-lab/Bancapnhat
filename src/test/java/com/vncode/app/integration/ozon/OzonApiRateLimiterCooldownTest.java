package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OzonApiRateLimiterCooldownTest {
    @Test void waitingReadRechecksNew429CooldownBeforeIssuingItsOldTicket() throws Exception {
        OzonApiRateLimiter limiter = new OzonApiRateLimiter(Duration.ofMillis(150));
        limiter.awaitTurn(1, "posting");
        CompletableFuture<Long> finished = new CompletableFuture<>();
        Thread waiting = new Thread(() -> {
            try { limiter.awaitTurn(1, "posting"); finished.complete(System.nanoTime()); }
            catch (InterruptedException interrupted) { finished.completeExceptionally(interrupted); }
        });
        waiting.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (waiting.getState() != Thread.State.TIMED_WAITING && !finished.isDone()
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertFalse(finished.isDone(), "The second request must be waiting on its original spacing ticket");
            assertEquals(Thread.State.TIMED_WAITING, waiting.getState());
            long limitedAt = System.nanoTime();
            limiter.registerRateLimit(1, "posting", Duration.ofMillis(500));
            long returnedAt = finished.get(3, TimeUnit.SECONDS);
            assertTrue(returnedAt - limitedAt >= Duration.ofMillis(450).toNanos(),
                    "An already queued read must honor a newly received 429 Retry-After");
        } finally { waiting.interrupt(); waiting.join(1000); }
    }

    @Test void cooldownDoesNotPauseAnotherShopOrEndpointFamily() {
        OzonApiRateLimiter limiter = new OzonApiRateLimiter(Duration.ZERO);
        limiter.registerRateLimit(1, "posting", Duration.ofMinutes(1));
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            limiter.awaitTurn(2, "posting");
            limiter.awaitTurn(1, "products");
        });
    }
}
