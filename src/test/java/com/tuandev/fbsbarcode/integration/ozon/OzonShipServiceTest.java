package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonShipServiceTest {
    @TempDir
    Path temporaryDirectory;

    private MockWebServer server;
    private Shop shop;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        System.setProperty("wcode.appdata.dir", temporaryDirectory.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) "
                    + "VALUES(1,'Ozon','OZON','client-1','secret')");
        }
        shop = new Shop(1, "Ozon", Marketplace.OZON, "client-1", "secret");
        new OzonProductKizPolicyRepository().setRequired(1, "101", false);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("wcode.appdata.dir");
        server.shutdown();
    }

    @Test
    void requiresExplicitConfirmationBeforeAnyApiRequest() {
        assertThrows(IllegalArgumentException.class, () -> service().ship(shop, "POST-1", false));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void markedPostingShipsWithoutUploadingOrCheckingKizOnOzon() throws Exception {
        server.enqueue(json(markedPosting("awaiting_packaging", true)));
        server.enqueue(json("{}"));
        server.enqueue(json(markedPosting("awaiting_deliver", false)));

        OzonShipResult result = service().ship(shop, "POST-1", true);

        assertEquals("awaiting_deliver", result.status());
        assertEquals("/v3/posting/fbs/get", server.takeRequest(1, TimeUnit.SECONDS).getPath());
        assertEquals("/v4/posting/fbs/ship", server.takeRequest(1, TimeUnit.SECONDS).getPath());
        assertEquals("/v3/posting/fbs/get", server.takeRequest(1, TimeUnit.SECONDS).getPath());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void timeoutAfterShipUsesPostingReadbackAndNeverSubmitsShipTwice() throws Exception {
        server.enqueue(json(posting("awaiting_packaging", true)));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(json(posting("awaiting_deliver", false)));

        OzonShipResult result = service().ship(shop, "POST-1", true);

        assertTrue(result.reconciledAfterAmbiguousResponse());
        assertEquals("awaiting_deliver", result.status());
        RecordedRequest preflight = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest mutation = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest readback = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v3/posting/fbs/get", preflight.getPath());
        assertEquals("/v4/posting/fbs/ship", mutation.getPath());
        assertEquals("/v3/posting/fbs/get", readback.getPath());
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_action_log WHERE action_type='ship' AND status='pending'"));
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_action_log WHERE action_type='ship' AND status='reconciled'"));
        String request = mutation.getBody().readUtf8();
        assertTrue(request.contains("\"packages\""));
        assertTrue(request.contains("\"product_id\":101"));
        assertTrue(request.contains("\"quantity\":2"));
        assertFalse(request.contains("exemplar"));
    }

    @Test
    void deterministicRejectionIsLoggedAndAllowsCorrectedRetry() throws Exception {
        server.enqueue(json(posting("awaiting_packaging", true)));
        server.enqueue(new MockResponse().setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"HAS_INCORRECT_PRODUCT_QUANTITY\"}"));
        server.enqueue(json(posting("awaiting_packaging", true)));
        server.enqueue(json("{}"));
        server.enqueue(json(posting("awaiting_deliver", false)));
        OzonShipService service = service();

        OzonApiException failure = assertThrows(
                OzonApiException.class, () -> service.ship(shop, "POST-1", true));
        OzonShipResult retry = service.ship(shop, "POST-1", true);

        assertEquals("HAS_INCORRECT_PRODUCT_QUANTITY", failure.safeErrorCode());
        assertEquals("awaiting_deliver", retry.status());
        assertEquals(2, count("SELECT COUNT(*) FROM ozon_action_log WHERE action_type='ship' AND status='pending'"));
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_action_log WHERE action_type='ship' AND status='rejected' "
                + "AND safe_error_code='HAS_INCORRECT_PRODUCT_QUANTITY'"));
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_action_log WHERE action_type='ship' AND status='success'"));
    }

    @Test
    void unresolvedShipAttemptIsReadBackButNeverSubmittedAgain() throws Exception {
        server.enqueue(json(posting("awaiting_packaging", true)));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(json(posting("awaiting_packaging", true)));
        server.enqueue(json(posting("awaiting_packaging", true)));
        OzonShipService service = service();

        OzonApiException first = assertThrows(
                OzonApiException.class, () -> service.ship(shop, "POST-1", true));
        OzonApiException second = assertThrows(
                OzonApiException.class, () -> service.ship(shop, "POST-1", true));

        assertEquals("reconcile_required", first.kind());
        assertEquals("reconcile_required", second.kind());
        assertEquals(4, server.getRequestCount());
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_action_log WHERE action_type='ship' AND status='pending'"));
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_action_log WHERE action_type='ship' AND status='ambiguous'"));
    }

    @Test
    void confirmedShipResponseCannotBeSubmittedAgainWhenRefreshFails() throws Exception {
        server.enqueue(json(posting("awaiting_packaging", true)));
        server.enqueue(json("{}"));
        // A real HTTP failure is deterministic here; DISCONNECT_AT_START may be retried
        // transparently by OkHttp before the client's explicit retry policy sees it.
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(json(posting("awaiting_packaging", true)));
        OzonShipService service = service();

        assertThrows(OzonApiException.class, () -> service.ship(shop, "POST-1", true));
        OzonApiException retry = assertThrows(
                OzonApiException.class, () -> service.ship(shop, "POST-1", true));

        assertEquals("reconcile_required", retry.kind(),
                () -> "requests observed before retry result: " + server.getRequestCount());
        assertEquals(4, server.getRequestCount());
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_action_log WHERE action_type='ship' AND status='success'"));
    }

    private OzonShipService service() {
        return new OzonShipService(
                new OzonPostingRepository(),
                new OzonProductGtinMappingRepository(),
                new OzonExemplarJobRepository(),
                (shopId, credentials) -> new OzonApiClient(
                        shopId,
                        credentials,
                        server.url("/"),
                        new OkHttpClient.Builder()
                                .connectTimeout(200, TimeUnit.MILLISECONDS)
                                .readTimeout(200, TimeUnit.MILLISECONDS)
                                .writeTimeout(200, TimeUnit.MILLISECONDS)
                                .callTimeout(500, TimeUnit.MILLISECONDS)
                                .build(),
                        new OzonApiRateLimiter(Duration.ZERO),
                        new OzonRetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1))));
    }

    private int count(String sql) throws Exception {
        try (Connection connection = Database.getConnection();
                ResultSet result = connection.createStatement().executeQuery(sql)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String posting(String status, boolean canShip) {
        return "{\"result\":{\"posting_number\":\"POST-1\",\"status\":\"" + status
                + "\",\"products\":[{\"sku\":101,\"offer_id\":\"sku-a\",\"name\":\"Item\",\"quantity\":2}],"
                + "\"requirements\":{},\"available_actions\":"
                + (canShip ? "[\"ship\"]" : "[]") + ",\"ship_available\":" + canShip + "}}";
    }

    private static String markedPosting(String status, boolean canShip) {
        return "{\"result\":{\"posting_number\":\"POST-1\",\"status\":\"" + status
                + "\",\"products\":[{\"sku\":101,\"offer_id\":\"sku-a\",\"name\":\"Item\",\"quantity\":2}],"
                + "\"requirements\":{\"products_requiring_mandatory_mark\":[\"101\"]},\"available_actions\":"
                + (canShip ? "[\"ship\"]" : "[]") + ",\"ship_available\":" + canShip + "}}";
    }
}
