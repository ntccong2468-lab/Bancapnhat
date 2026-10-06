package com.tuandev.fbsbarcode.integration.ozon;

import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OzonFboApiClientTest {
    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    @Test
    void usesCurrentV3ListAndGetContracts() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"order_ids\":[1],\"last_id\":\"next\"}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"orders\":[]}"));
        OzonApiClient client = client();

        client.listFboSupplyOrders(List.of("READY_TO_SUPPLY"), "", 100);
        client.getFboSupplyOrders(List.of("1"));

        var list = server.takeRequest(1, TimeUnit.SECONDS);
        var get = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v3/supply-order/list", list.getPath());
        assertEquals("/v3/supply-order/get", get.getPath());
        String body = list.getBody().readUtf8();
        assertTrue(body.contains("\"states\":[\"READY_TO_SUPPLY\"]"));
        assertTrue(body.contains("\"sort_by\":\"ORDER_STATE_UPDATED_AT\""));
        assertFalse(body.contains("ORDER_STATE_READY_TO_SUPPLY"));
    }

    @Test
    void usesV1BundleWithBoundedPagination() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"items\":[],\"has_next\":false,\"last_id\":\"\"}"));

        client().getFboSupplyBundle(List.of("bundle-1"), "", 100);

        var request = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v1/supply-order/bundle", request.getPath());
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"bundle_ids\":[\"bundle-1\"]"));
        assertTrue(body.contains("\"limit\":100"));
    }

    @Test
    void stopsFboPassOnRateLimitInsteadOfRetryingContinuously() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "60"));

        assertThrows(OzonApiException.class,
                () -> client(4).listFboSupplyOrders(List.of("READY_TO_SUPPLY"), "", 100));

        assertEquals(1, server.getRequestCount());
    }

    private OzonApiClient client() {
        return client(1);
    }

    private OzonApiClient client(int attempts) {
        return new OzonApiClient(
                42,
                new OzonCredentials("client-42", "secret"),
                server.url("/"),
                new OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                new OzonApiRateLimiter(Duration.ZERO),
                new OzonRetryPolicy(attempts, Duration.ofMillis(1), Duration.ofMillis(1)));
    }
}
