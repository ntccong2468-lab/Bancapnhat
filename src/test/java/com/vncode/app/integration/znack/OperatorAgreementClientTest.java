package com.vncode.app.integration.znack;

import com.google.gson.JsonParser;
import com.vncode.app.features.license.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OperatorAgreementClientTest {
    @TempDir Path directory;
    private static final String PDF = "%PDF-1.7\nsynthetic approved agreement\n%%EOF";
    private OperatorAgreementClient client(MockWebServer server) {
        return new OperatorAgreementClient(new OkHttpClient.Builder().callTimeout(Duration.ofMillis(500)).build(), server.url("/api/v3/true-api/"));
    }
    private OperatorAgreement agreement() { return new OperatorAgreement("87", "Fixture", "fixture.pdf", "2026-11-17", 1, 1, null); }
    @Test void officialSplitRowsUseDocumentIdAndRequireSellerSignatureType() throws Exception {
        var list = OperatorAgreementClient.parse(JsonParser.parseString("""
                {"status":"200","response":[
                 {"guid":44,"document":87,"name":"Fixture","fileName":"fixture.pdf","createtime":"2026-11-17"},
                 {"status":1,"signtype":1},
                 {"document":88,"status":1,"signtype":3},
                 {"document":89,"status":1,"signtype":4}]}
                """));
        assertEquals("87", list.getFirst().id()); assertTrue(list.getFirst().awaitingSignature());
        assertFalse(list.get(1).awaitingSignature()); assertFalse(list.get(2).awaitingSignature());
        assertFalse(new OperatorAgreement("90", "", "", "", 2, 2, 2).operatorPending());
        assertTrue(new OperatorAgreement("90", "", "", "", 2, 1, 2).operatorPending());
    }
    @Test void malformedBusinessOrIncompleteRowsNeverBecomeAnEmptySuccessfulList() {
        for (String json : List.of("{}", "{\"status\":\"400\",\"response\":[]}", "{\"response\":[],\"errors\":[]}",
                "[{\"document\":87}]", "[{\"guid\":87,\"status\":1,\"signtype\":1}]",
                "[{\"document\":87,\"status\":1,\"signtype\":1},{\"document\":87,\"status\":2,\"signtype\":1}]",
                "[{\"status\":1,\"signtype\":1}]", "[{\"document\":87,\"status\":1,\"signtype\":9}]"))
            assertThrows(IOException.class, () -> OperatorAgreementClient.parse(JsonParser.parseString(json)), json);
    }
    @Test void sameOriginPdfLinkUsesExactDocumentAndBoundedBearerRequests() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody('"' + server.url("/print/87.pdf").toString() + '"'));
            server.enqueue(new MockResponse().setBody(PDF));
            assertArrayEquals(PDF.getBytes(java.nio.charset.StandardCharsets.UTF_8), client(server).pdf("synthetic-token", agreement()));
            var first = server.takeRequest(); assertEquals("/api/v3/true-api/elk/crm-api/document?guid=87", first.getPath());
            assertEquals("Bearer synthetic-token", first.getHeader("Authorization"));
            assertEquals("/print/87.pdf", server.takeRequest().getPath());
        }
    }
    @Test void foreignPdfLinksAndRedirectsCannotReceiveTheToken() throws Exception {
        try (var server = new MockWebServer(); var foreign = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody('"' + foreign.url("/steal").toString() + '"'));
            assertThrows(IOException.class, () -> client(server).pdf("synthetic-token", agreement()));
            assertEquals(0, foreign.getRequestCount());
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", foreign.url("/steal")));
            assertThrows(OperatorAgreementClient.HttpFailure.class, () -> client(server).pdf("synthetic-token", agreement()));
            assertEquals(0, foreign.getRequestCount()); assertEquals(2, server.getRequestCount());
        }
    }
    @Test void oversizedOrNonPdfBodyCannotBeApproved() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("<html>not a PDF</html>"));
            assertThrows(IOException.class, () -> client(server).pdf("synthetic-token", agreement()));
            server.enqueue(new MockResponse().setBody(new okio.Buffer().write(new byte[OperatorAgreementClient.MAX_PDF + 1])));
            assertThrows(IOException.class, () -> client(server).pdf("synthetic-token", agreement()));
        }
    }
    @Test void signaturePostIsPlainBase64AndRequiresAnAcknowledgedIdEvenForHttp200() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(202).setBody("{\"id\":\"fixture-edo-id\"}"));
            assertEquals("fixture-edo-id", client(server).submit("synthetic-token", "87", "c3ludGhldGlj"));
            var request = server.takeRequest(); assertEquals("POST", request.getMethod());
            assertEquals("/api/v3/true-api/elk/outgoing-documents/87/signature", request.getPath());
            assertEquals("c3ludGhldGlj", request.getBody().readUtf8()); assertTrue(request.getHeader("Content-Type").startsWith("text/plain"));
            server.enqueue(new MockResponse().setBody("{}"));
            assertThrows(IOException.class, () -> client(server).submit("synthetic-token", "87", "c3ludGhldGlj"));
            assertEquals(2, server.getRequestCount());
        }
    }
    @Test void disconnectedPostAnd401AreNeverReplayedByTransport() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
            var error = assertThrows(IOException.class, () -> client(server).submit("synthetic-token", "87", "c3ludGhldGlj"));
            assertFalse(error.toString().contains("synthetic-token")); assertEquals(1, server.getRequestCount());
            server.enqueue(new MockResponse().setResponseCode(401).setBody("sensitive upstream response"));
            var denied = assertThrows(OperatorAgreementClient.HttpFailure.class, () -> client(server).submit("synthetic-token", "87", "c3ludGhldGlj"));
            assertEquals(401, denied.status()); assertFalse(denied.toString().contains("sensitive")); assertEquals(2, server.getRequestCount());
        }
    }
    @Test void onlyPinnedTlsProductionAndExplicitLoopbackAreUsable() {
        for (String url : List.of("http://markirovka.crpt.ru/api/v3/true-api/", "https://example.invalid/api/v3/true-api/",
                "https://markirovka.crpt.ru.evil.invalid/api/v3/true-api/", "https://markirovka.crpt.ru:8443/api/v3/true-api/",
                "https://markirovka.crpt.ru/api/v3/true-api/?redirect=1"))
            assertThrows(IllegalArgumentException.class, () -> new OperatorAgreementClient(new OkHttpClient(), HttpUrl.get(url)));
    }
    @Test void deniedGs1PermissionStopsBeforeNetwork() throws Exception {
        var previous = LicenseRuntime.service();
        try (var denied = LicenseDomainFixture.denied(directory.resolve("license")); var server = new MockWebServer()) {
            LicenseRuntime.install(denied);
            assertThrows(LicenseGate.LicenseDeniedException.class, () -> client(server).list("synthetic-token"));
            assertEquals(0, server.getRequestCount());
        } finally { LicenseRuntime.install(previous); }
    }
    @Test void officialEdoDocumentIdResolvesCounterSignatureWithoutInventingACrmStatusField() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"status\":\"200\",\"response\":[{\"document\":87,\"status\":2,\"signtype\":1,\"edoDocumentId\":\"synthetic-edo-id\"}]}"));
            server.enqueue(new MockResponse().setBody("{\"count\":1,\"items\":[{\"id\":\"synthetic-edo-id\",\"status\":4}]}"));
            var agreement = client(server).list("synthetic-token").getFirst();
            assertEquals(4, agreement.edoStatus()); assertFalse(agreement.operatorPending());
            assertEquals("synthetic-edo-id", agreement.edoDocumentId());
            server.takeRequest(); assertEquals("/api/v3/true-api/elk/outgoing-documents?limit=10&offset=0", server.takeRequest().getPath());
        }
    }
    @Test void edoPaginationRequiresTheExactIdAndKeepsMissingIdsUnknown() throws Exception {
        try (var server = new MockWebServer()) {
            var items = new StringBuilder();
            for (int i = 0; i < 10; i++) { if (i > 0) items.append(','); items.append("{\"id\":\"unrelated-").append(i).append("\",\"status\":4}"); }
            server.enqueue(new MockResponse().setBody("{\"count\":11,\"items\":[" + items + "]}"));
            server.enqueue(new MockResponse().setBody("{\"count\":11,\"items\":[{\"id\":\"exact-edo\",\"status\":4}]}"));
            assertEquals(java.util.Map.of("exact-edo", 4), client(server).outgoingStatuses("synthetic-token", java.util.Set.of("exact-edo")));
            assertEquals("/api/v3/true-api/elk/outgoing-documents?limit=10&offset=0", server.takeRequest().getPath());
            assertEquals("/api/v3/true-api/elk/outgoing-documents?limit=10&offset=10", server.takeRequest().getPath());
            server.enqueue(new MockResponse().setBody("{\"count\":1,\"items\":[{\"id\":\"other\",\"status\":4}]}"));
            assertTrue(client(server).outgoingStatuses("synthetic-token", java.util.Set.of("missing")).isEmpty());
        }
    }
    @Test void inconsistentEdoPagesFailRatherThanCompletingAnotherDocument() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"count\":2,\"items\":[{\"id\":\"same\",\"status\":4},{\"id\":\"same\",\"status\":1}]}"));
            assertThrows(IOException.class, () -> client(server).outgoingStatuses("synthetic-token", java.util.Set.of("same")));
            server.enqueue(new MockResponse().setBody("{\"count\":0,\"items\":[{\"id\":\"same\",\"status\":4}]}"));
            assertThrows(IOException.class, () -> client(server).outgoingStatuses("synthetic-token", java.util.Set.of("same")));
        }
    }
    @Test void edoHistoryScanIsBoundedWhenTheRequestedIdIsNotReturned() throws Exception {
        try (var server = new MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    int offset = Integer.parseInt(request.getRequestUrl().queryParameter("offset")); var body = new StringBuilder();
                    for (int i = 0; i < 10; i++) { if (i > 0) body.append(','); body.append("{\"id\":\"old-").append(offset + i).append("\",\"status\":4}"); }
                    return new MockResponse().setBody("{\"count\":10000,\"items\":[" + body + "]}");
                }
            });
            assertTrue(client(server).outgoingStatuses("synthetic-token", java.util.Set.of("not-present")).isEmpty());
            assertEquals(20, server.getRequestCount());
        }
    }
    @Test void aScopeChangeDuringOneReadStopsBeforeAnyNextEdoPage() throws Exception {
        try (var server = new MockWebServer()) {
            var changed = new java.util.concurrent.atomic.AtomicBoolean();
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    changed.set(true); return new MockResponse().setBody("{\"count\":0,\"items\":[]}");
                }
            });
            var client = new OperatorAgreementClient(new OkHttpClient(), server.url("/api/v3/true-api/"), () -> {
                if (changed.get()) throw new IllegalStateException("synthetic account changed");
            });
            assertThrows(IllegalStateException.class, () -> client.outgoingStatuses("synthetic-token", java.util.Set.of("not-present")));
            assertEquals(1, server.getRequestCount());
        }
    }
}
