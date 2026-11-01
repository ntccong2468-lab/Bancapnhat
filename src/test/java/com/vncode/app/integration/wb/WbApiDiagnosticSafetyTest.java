package com.vncode.app.integration.wb;

import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class WbApiDiagnosticSafetyTest {
    private static final String TEST_KEY = "synthetic-fixture-secret-047";

    @Test void upstreamCredentialNeverReachesExceptionOrResponseBody() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(400).setBody(
                    "{\"message\":\"Rejected key " + TEST_KEY + "\",\"debug\":\"" + TEST_KEY + "\"}"));
            server.start();
            WbApiException failure;
            var captured = new ByteArrayOutputStream();
            var previous = System.err;
            try (var stream = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
                System.setErr(stream);
                failure = assertThrows(WbApiException.class, () -> execute(server, Void.class));
            } finally { System.setErr(previous); }
            String log = captured.toString(StandardCharsets.UTF_8);
            assertAll(
                    () -> assertEquals(400, failure.getStatusCode()),
                    () -> assertTrue(failure.getMessage().contains("Rejected key")),
                    () -> assertFalse(failure.getMessage().contains(TEST_KEY), "upstream message leaked the fixture key"),
                    () -> assertFalse(failure.getResponseBody().contains(TEST_KEY), "raw response body leaked the fixture key"),
                    () -> assertTrue(log.contains("WB API request failed"), "expected a useful diagnostic log"),
                    () -> assertFalse(log.contains(TEST_KEY), "upstream message leaked the fixture key to logs"),
                    () -> assertFalse(log.contains("private=fixture"), "request query leaked to logs"),
                    () -> assertEquals(1, server.getRequestCount()));
        }
    }

    @Test void keepsStructuredValidationDetailsAndRateLimitStatus() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(409).setBody(
                    "{\"code\":\"MetaValidationFail\",\"message\":\"Bad metadata\","
                    + "\"metaDetails\":[{\"key\":\"size\",\"error\":\"required\"}]}"));
            server.start();
            var failure = assertThrows(WbApiException.class, () -> execute(server, Void.class));
            assertEquals(409, failure.getStatusCode());
            assertTrue(failure.getMessage().contains("size=required"));
            assertTrue(failure.getMessage().contains("Bad metadata"));
            assertEquals("", failure.getResponseBody(), "raw upstream body must not be retained");
        }
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"code\":\"RATE_LIMIT\"}"));
            server.start();
            var failure = assertThrows(WbApiException.class, () -> execute(server, Void.class));
            assertTrue(failure.isRateLimited());
            assertEquals(429, failure.getStatusCode());
            assertTrue(failure.getMessage().contains("RATE_LIMIT"));
        }
        var deferred = new WbApiException("safe", 429, "", Duration.ofSeconds(17));
        assertEquals(Duration.ofSeconds(17), deferred.getRetryAfter());
    }

    @Test void masksLabeledCredentialAndDiscardsUnstructuredUpstreamBody() throws Exception {
        String otherSecret = "synthetic-secondary-012";
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(400)
                    .setBody("{\"message\":\"Rejected token=" + otherSecret + "\"}"));
            server.enqueue(new MockResponse().setResponseCode(400)
                    .setBody("unstructured upstream " + otherSecret));
            server.start();
            var structured = assertThrows(WbApiException.class, () -> execute(server, Void.class));
            assertTrue(structured.getMessage().contains("Rejected"));
            assertFalse(structured.getMessage().contains(otherSecret));
            var raw = assertThrows(WbApiException.class, () -> execute(server, Void.class));
            assertEquals("WB API request failed", raw.getMessage());
            assertEquals("", raw.getResponseBody());
        }
    }

    @Test void malformedJsonDoesNotExposeItsSourceOrCause() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"unexpected\":\"" + TEST_KEY));
            server.start();
            var failure = assertThrows(com.google.gson.JsonSyntaxException.class,
                    () -> execute(server, WbOrdersResponse.class));
            assertFalse(String.valueOf(failure.getMessage()).contains(TEST_KEY));
            assertNull(failure.getCause(), "untrusted response parser cause must not be logged downstream");
        }
    }

    private static void execute(MockWebServer server, Class<?> type) throws Exception {
        var request = new Request.Builder().url(server.url("/test?private=fixture")).build();
        Method method = WbApiClient.class.getDeclaredMethod("executeOnce", String.class, Request.class, Class.class);
        method.setAccessible(true);
        try {
            method.invoke(new WbApiClient(), TEST_KEY, request, type);
        } catch (InvocationTargetException invocation) {
            if (invocation.getCause() instanceof Exception cause) throw cause;
            throw invocation;
        }
    }
}
