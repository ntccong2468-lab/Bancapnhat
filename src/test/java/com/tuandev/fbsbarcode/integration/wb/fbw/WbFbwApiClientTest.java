package com.tuandev.fbsbarcode.integration.wb.fbw;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WbFbwApiClientTest {
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
    void readsListDetailAndGoodsWithoutSendingMarketplaceMutations() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"statusID\":2}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));
        WbFbwApiClient client = new WbFbwApiClient(
                7, "wb-secret", server.url("/"), new OkHttpClient(), new WbFbwRateLimiter(Duration.ZERO));

        client.listSupplies(1000, 0);
        client.getSupply("123", true);
        client.getGoods("123", true, 1000, 0);

        var list = server.takeRequest(1, TimeUnit.SECONDS);
        var detail = server.takeRequest(1, TimeUnit.SECONDS);
        var goods = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/api/v1/supplies?limit=1000&offset=0", list.getPath());
        assertEquals("POST", list.getMethod());
        assertEquals("{}", list.getBody().readUtf8());
        assertEquals("/api/v1/supplies/123?isPreorderID=true", detail.getPath());
        assertEquals("GET", detail.getMethod());
        assertTrue(goods.getPath().contains("/api/v1/supplies/123/goods"));
        assertEquals("wb-secret", goods.getHeader("Authorization"));
    }

    @Test
    void stopsPassOnRateLimitWithoutAnImmediateRetry() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "60"));
        WbFbwApiClient client = new WbFbwApiClient(
                7, "wb-secret", server.url("/"),
                new OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                new WbFbwRateLimiter(Duration.ZERO));

        assertThrows(com.tuandev.fbsbarcode.integration.wb.WbApiException.class,
                () -> client.listSupplies(1000, 0));

        assertEquals(1, server.getRequestCount());
    }
}
