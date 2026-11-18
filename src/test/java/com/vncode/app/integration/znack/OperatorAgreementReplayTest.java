package com.vncode.app.integration.znack;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class OperatorAgreementReplayTest {
    @Test void retryAfterZeroCannotReplayTheSignaturePost() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503).addHeader("Retry-After", "0"));
            server.enqueue(new MockResponse().setBody("{\"id\":\"must-not-reach-second-attempt\"}"));
            var client = new OperatorAgreementClient(new OkHttpClient(), server.url("/api/v3/true-api/"));
            IOException caught = null;
            try { client.submit("synthetic-token", "87", "c3ludGhldGlj"); } catch (IOException failure) { caught = failure; }
            assertEquals(1, server.getRequestCount(), "An operator signature must never be sent a second time by OkHttp");
            assertNotNull(caught); assertFalse(caught.toString().contains("synthetic-token"));
        }
    }
    @Test void misdirected421RemainsAnUnconfirmedSinglePost() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(421));
            server.enqueue(new MockResponse().setBody("{\"id\":\"must-not-reach-second-attempt\"}"));
            var client = new OperatorAgreementClient(new OkHttpClient(), server.url("/api/v3/true-api/"));
            assertThrows(IOException.class, () -> client.submit("synthetic-token", "87", "c3ludGhldGlj"));
            assertEquals(1, server.getRequestCount());
        }
    }
}
