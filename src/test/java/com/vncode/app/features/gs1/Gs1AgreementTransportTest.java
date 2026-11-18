package com.vncode.app.features.gs1;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP plus disposable SQLite; no network account or native signing. */
class Gs1AgreementTransportTest {
    @TempDir Path directory; Shop shop;
    private static final byte[] PDF = "%PDF-1.7\nsynthetic approved bytes\n%%EOF".getBytes(StandardCharsets.UTF_8);
    @BeforeEach void init() throws Exception { System.setProperty("vncode.appdata.dir", directory.toString()); Database.initDatabase(); shop = GtinWriteTestFixture.shop(Marketplace.WILDBERRIES); }
    @AfterEach void clear() { System.clearProperty("vncode.appdata.dir"); }
    private Gs1AgreementService service(Path database, OperatorAgreementClient client, AtomicInteger signatures) {
        return new Gs1AgreementService(new Gs1Repository(database), (shop, settings, scope, guard) -> new Gs1AgreementService.Backend() {
            public List<OperatorAgreement> list() throws Exception { guard.run(); return client.list("synthetic-token"); }
            public byte[] pdf(OperatorAgreement agreement) throws Exception { guard.run(); return client.pdf("synthetic-token", agreement); }
            public String sign(byte[] bytes) { guard.run(); assertArrayEquals(PDF, bytes); signatures.incrementAndGet(); return "c3ludGhldGlj"; }
            public String submit(String id, String signature) throws Exception { guard.run(); return client.submit("synthetic-token", id, signature); }
        });
    }
    @Test void lostPostResponseSurvivesFreshServiceAndOnlyReadsUntilExactReadbackConfirms() throws Exception {
        try (var server = new MockWebServer()) {
            var posts = new AtomicInteger(); var signatures = new AtomicInteger(); var published = new AtomicBoolean();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getMethod().equals("POST")) {
                        posts.incrementAndGet(); assertEquals("/api/v3/true-api/elk/outgoing-documents/87/signature", request.getPath());
                        return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
                    }
                    if (request.getPath().contains("document?")) return new MockResponse().setBody(new okio.Buffer().write(PDF));
                    return new MockResponse().setBody("{\"status\":\"200\",\"response\":[{\"document\":87,\"name\":\"Fixture\",\"fileName\":\"fixture.pdf\",\"createtime\":\"2026-11-17\",\"status\":" + (published.get() ? 2 : 1) + ",\"signtype\":1,\"edoStatus\":" + (published.get() ? 4 : 0) + "}]}");
                }
            });
            var client = new OperatorAgreementClient(new OkHttpClient(), server.url("/api/v3/true-api/")); var db = directory.resolve("agreement.db");
            var first = service(db, client, signatures); String requestId;
            try (var prepared = first.prepare(shop, Gs1AgreementServiceTest.pending())) {
                requestId = prepared.requestId(); assertThrows(IOException.class, () -> first.sign(shop, prepared, true));
            }
            assertEquals(1, posts.get()); assertEquals(1, signatures.get());
            var restarted = service(db, client, signatures);
            try (var recovered = restarted.prepare(shop, Gs1AgreementServiceTest.pending())) {
                assertTrue(recovered.requiresReconciliation()); assertEquals(requestId, recovered.requestId());
                assertEquals(Gs1AgreementService.State.PENDING, restarted.sign(shop, recovered, true).state());
                assertEquals(1, posts.get()); assertEquals(1, signatures.get());
                published.set(true); assertEquals(Gs1AgreementService.State.CONFIRMED, restarted.reconcile(shop, requestId).state());
                assertEquals(1, posts.get());
            }
        }
    }
    @Test void accepted202CannotConfirmUntilTheSameDocumentReadbackChanges() throws Exception {
        try (var server = new MockWebServer()) {
            var posts = new AtomicInteger(); var complete = new AtomicBoolean();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getMethod().equals("POST")) { posts.incrementAndGet(); return new MockResponse().setResponseCode(202).setBody("{\"id\":\"synthetic-edo\"}"); }
                    if (request.getPath().contains("document?")) return new MockResponse().setBody(new okio.Buffer().write(PDF));
                    return new MockResponse().setBody("{\"response\":[{\"document\":87,\"name\":\"Fixture\",\"fileName\":\"fixture.pdf\",\"createtime\":\"2026-11-17\",\"status\":" + (complete.get() ? 2 : 1) + ",\"signtype\":1,\"edoStatus\":" + (complete.get() ? 4 : 0) + "}]}");
                }
            });
            var service = service(directory.resolve("pending.db"), new OperatorAgreementClient(new OkHttpClient(), server.url("/api/v3/true-api/")), new AtomicInteger());
            try (var prepared = service.prepare(shop, Gs1AgreementServiceTest.pending())) {
                assertEquals(Gs1AgreementService.State.PENDING, service.sign(shop, prepared, true).state()); assertEquals(1, posts.get());
                assertEquals(Gs1AgreementService.State.PENDING, service.sign(shop, prepared, true).state()); assertEquals(1, posts.get());
                complete.set(true); assertEquals(Gs1AgreementService.State.CONFIRMED, service.reconcile(shop, prepared.requestId()).state());
            }
        }
    }
}
