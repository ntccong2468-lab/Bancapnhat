package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OzonApiClientTest {
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
    void retriesBoundedReadsAndSendsCredentialsOnlyInOzonHeaders() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody("{\"result\":{\"items\":[]}}"));
        OzonApiClient client = client(2);

        client.listProducts("", 10);

        var first = server.takeRequest(1, TimeUnit.SECONDS);
        var second = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v3/product/list", first.getPath());
        assertEquals("client-42", first.getHeader("Client-Id"));
        assertEquals("ozon-secret", first.getHeader("Api-Key"));
        assertEquals("/v3/product/list", second.getPath());
    }

    @Test
    void neverBlindlyRetriesAmbiguousMutationAndDoesNotLeakCredential() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        OzonApiClient client = client(4);

        OzonApiException failure = assertThrows(OzonApiException.class,
                () -> client.setExemplars(new JsonObject()));

        assertTrue(failure.ambiguousMutation());
        assertEquals(1, server.getRequestCount());
        assertFalse(failure.toString().contains("ozon-secret"));
        assertFalse(new OzonCredentials("client-42", "ozon-secret").toString().contains("ozon-secret"));
    }

    @Test
    void createsAsynchronousLabelJobThroughCurrentVersionTwoEndpoint() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"result\":{\"tasks\":[{\"task_id\":123,\"task_type\":\"big_label\"}]}}"));
        OzonApiClient client = client(1);

        client.createLabelJob(new JsonObject());

        assertEquals("/v2/posting/fbs/package-label/create", server.takeRequest().getPath());
    }

    @Test
    void listsPostingsThroughCursorBasedVersionFourContract() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"postings\":[],\"cursor\":\"next\",\"has_next\":false}"));

        client(1).listPostings("2026-08-01T00:00:00Z", "2026-08-18T00:00:00Z", "cursor-1", 100);

        var request = server.takeRequest();
        assertEquals("/v4/posting/fbs/list", request.getPath());
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"cursor\":\"cursor-1\""));
        assertTrue(body.contains("\"sort_dir\":\"asc\""));
    }

    @Test
    void listsCurrentUnfulfilledPostingsThroughVersionFourCutoffContract() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"postings\":[],\"cursor\":\"next\",\"has_next\":false}"));

        client(1).listUnfulfilledPostings(
                "2026-05-01T00:00:00Z", "2026-11-01T00:00:00Z", "cursor-1", 100);

        var request = server.takeRequest();
        assertEquals("/v4/posting/fbs/unfulfilled/list", request.getPath());
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"cutoff_from\":\"2026-05-01T00:00:00Z\""));
        assertTrue(body.contains("\"cutoff_to\":\"2026-11-01T00:00:00Z\""));
        assertTrue(body.contains("\"cursor\":\"cursor-1\""));
        assertTrue(body.contains("\"sort_dir\":\"asc\""));
    }

    @Test
    void malformedSuccessResponseAfterMutationIsTreatedAsAmbiguous() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(""));

        OzonApiException failure = assertThrows(
                OzonApiException.class, () -> client(1).setExemplars(new JsonObject()));

        assertTrue(failure.ambiguousMutation());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void retainsOnlyAllowlistedUpstreamErrorCodeAndDropsResponseMessage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"INVALID_ARGUMENT\",\"message\":\"private-detail-123\"}"));

        OzonApiException failure = assertThrows(
                OzonApiException.class, () -> client(1).setExemplars(new JsonObject()));

        assertEquals("invalid_request", failure.kind());
        assertEquals("INVALID_ARGUMENT", failure.upstreamCode());
        assertEquals("INVALID_ARGUMENT", failure.safeErrorCode());
        assertFalse(failure.toString().contains("private-detail-123"));
    }

    @Test
    void classifiesNumericUpstreamErrorsWithAllowlistedDiagnosticTags() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":3,\"message\":\"invalid multi_box_qty for exemplar private-detail-123\"}"));

        OzonApiException failure = assertThrows(
                OzonApiException.class, () -> client(1).setExemplars(new JsonObject()));

        assertEquals("ERR_MULTIBOX_EXEMPLAR_INVALID", failure.upstreamCode());
        assertFalse(failure.toString().contains("private-detail-123"));
    }

    @Test
    void rejectsUntrustedOfficialDocumentUrlBeforeSendingAnyRequest() {
        OzonApiException failure = assertThrows(
                OzonApiException.class,
                () -> client(1).downloadOfficialDocument("https://example.com/label.pdf"));

        assertEquals("invalid_response", failure.kind());
        assertEquals(0, server.getRequestCount());
    }

    private OzonApiClient client(int attempts) {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(200, TimeUnit.MILLISECONDS)
                .readTimeout(200, TimeUnit.MILLISECONDS)
                .writeTimeout(200, TimeUnit.MILLISECONDS)
                .callTimeout(500, TimeUnit.MILLISECONDS)
                .build();
        return new OzonApiClient(42, new OzonCredentials("client-42", "ozon-secret"), server.url("/"), http,
                new OzonApiRateLimiter(Duration.ZERO),
                new OzonRetryPolicy(attempts, Duration.ofMillis(1), Duration.ofMillis(2)));
    }
}
