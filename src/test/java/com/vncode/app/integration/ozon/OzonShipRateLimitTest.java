package com.vncode.app.integration.ozon;

import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class OzonShipRateLimitTest {
    @Test void explicit429IsRejectedAndCarriesTheFullServerDelay() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("{}"));
            server.start(); var client = client(server);
            var error=assertThrows(OzonApiException.class,()->client.ship(new JsonObject()));
            assertEquals(429,error.statusCode()); assertFalse(error.ambiguousMutation());
            assertEquals(Duration.ofSeconds(120),error.retryAfter()); assertEquals(1,server.getRequestCount());
        }
    }
    @Test void shipServerErrorRemainsAmbiguousAndIsNotRetried() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("{}")); server.start();
            var error=assertThrows(OzonApiException.class,()->client(server).ship(new JsonObject()));
            assertTrue(error.ambiguousMutation()); assertEquals(1,server.getRequestCount());
        }
    }
    private OzonApiClient client(MockWebServer server) {
        return new OzonApiClient(1,new OzonCredentials("client","key"),server.url("/"),new OkHttpClient(),
                new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofSeconds(1)));
    }
}
