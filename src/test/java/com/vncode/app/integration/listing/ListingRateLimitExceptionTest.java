package com.vncode.app.integration.listing;

import com.vncode.app.features.sync.SyncFailure;
import java.time.*;
import java.time.format.DateTimeFormatter;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ListingRateLimitExceptionTest {
    @Test void usesTheLongestExplicitCooldownAndKeepsItInTheReport(){
        var rate=new ListingRateLimitException(new Headers.Builder().add("Retry-After","2.5").add("X-Ratelimit-Reset","19").build());
        assertEquals(Duration.ofSeconds(19),rate.retryAfter());var failure=SyncFailure.of(new java.io.IOException("wrapped",rate),0);
        assertEquals("API_COOLDOWN",failure.code());assertEquals(Duration.ofSeconds(19),failure.delay());assertFalse(failure.actionRequired());
    }
    @Test void handlesHttpDateWithoutExposingHeaders(){
        String date=ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(20).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        var rate=new ListingRateLimitException(new Headers.Builder().add("Retry-After",date).add("private","fixture-secret").build());
        assertTrue(rate.retryAfter().compareTo(Duration.ofSeconds(18))>=0);assertTrue(rate.retryAfter().compareTo(Duration.ofSeconds(20))<=0);
        assertFalse(rate.toString().contains("fixture-secret"));
    }
    @Test void invalidHeadersBackOffAndZeroIsBounded(){
        assertEquals(Duration.ofSeconds(60),ListingRateLimitException.delay(new Headers.Builder().add("Retry-After","invalid").build()));
        assertEquals(Duration.ofMillis(1),ListingRateLimitException.delay(new Headers.Builder().add("Retry-After","0").build()));
    }
}
