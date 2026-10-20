package com.vncode.app.integration.znack.registration;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationCatalogCooldownTest {
    static final class TestClock extends Clock {
        Instant now=Instant.parse("2026-10-20T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    @Test void retainsTheFullServerDelayWithoutShorteningItAndExpiresWithoutSleeping(){
        var clock=new TestClock();var cooldown=new RegistrationCatalogCooldown(clock);var profile=Path.of("fixture");
        cooldown.defer(profile,"seller-a",Duration.ofSeconds(301));
        assertEquals(Duration.ofSeconds(301),cooldown.pending(profile,"seller-a").delay());
        clock.now=clock.now.plusSeconds(20);cooldown.defer(profile,"seller-a",Duration.ofSeconds(1));
        assertEquals(Duration.ofSeconds(281),cooldown.pending(profile,"seller-a").delay());
        clock.now=clock.now.plusSeconds(281);assertNull(cooldown.pending(profile,"seller-a"));
    }
    @Test void isolatesProfilesAndExactAccountCredentials(){
        var clock=new TestClock();var cooldown=new RegistrationCatalogCooldown(clock);
        cooldown.defer(Path.of("first"),"seller-a",Duration.ofSeconds(60));
        assertNull(cooldown.pending(Path.of("first"),"seller-b"));
        assertNull(cooldown.pending(Path.of("second"),"seller-a"));
        assertEquals("ZNACK_RATE_LIMIT",cooldown.pending(Path.of("first"),"seller-a").code());
    }
    @Test void handsTheActual429RetryAfterToTheMonitorWithoutPausingForUnrelatedErrors(){
        var clock=new TestClock();var cooldown=new RegistrationCatalogCooldown(clock);var profile=Path.of("first");
        var limited=new com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException("fixture",429,"{}","POST","https://example.invalid/v3/product-sign",Duration.ofSeconds(301));
        cooldown.recordFailure(profile,"seller-a",new IllegalStateException("asynchronous signing failed",limited));
        assertEquals(Duration.ofSeconds(301),cooldown.pending(profile,"seller-a").delay());
        cooldown.recordFailure(profile,"seller-b",new java.io.IOException("offline"));
        assertNull(cooldown.pending(profile,"seller-b"));
    }
}
