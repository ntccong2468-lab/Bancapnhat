package com.vncode.app.shared;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class RateLimitDeferralTest {
    @Test void nestedBackgroundReadsYieldLongCooldownsAndRestoreScope(){
        assertFalse(RateLimitDeferral.active());assertDoesNotThrow(()->RateLimitDeferral.waiting(Duration.ofMinutes(2).toNanos()));
        try(var outer=RateLimitDeferral.enter()){
            try(var inner=RateLimitDeferral.enter()){
                var deferred=assertThrows(RateLimitDeferral.Deferred.class,()->RateLimitDeferral.waiting(Duration.ofMinutes(2).toNanos()));
                assertEquals(Duration.ofMinutes(2),deferred.delay());
            }
            assertTrue(RateLimitDeferral.active());assertDoesNotThrow(()->RateLimitDeferral.waiting(Duration.ofMillis(120).toNanos()));
        }
        assertFalse(RateLimitDeferral.active());
    }
}
