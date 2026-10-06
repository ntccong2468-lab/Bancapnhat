package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonExemplarWorkflowTest {
    private static final String RAW_KIZ = "010460000000000121ABC";

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
            statement.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) "
                    + "VALUES(1,'04600000000001','Marked item','2026-08-18T00:00:00Z')");
            statement.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at) "
                    + "VALUES(1,1,'04600000000001',1,'COMPLETED','2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
            statement.execute("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) "
                    + "VALUES(1,1,1,'" + RAW_KIZ + "','" + RAW_KIZ
                    + "','04600000000001','AVAILABLE','IN_CIRCULATION',"
                    + "'2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
            statement.execute("INSERT INTO ozon_product_gtin_mappings(shop_id,sku,gtin,created_at,updated_at) "
                    + "VALUES(1,'101','04600000000001','2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
        }
        shop = new Shop(1, "Ozon", Marketplace.OZON, "client-1", "secret");
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("wcode.appdata.dir");
        server.shutdown();
    }

    @Test
    void reservesKizLocallyWithoutCallingAnyOzonExemplarEndpoint() throws Exception {
        server.enqueue(json(posting()));

        OzonPreparationResult result = service().prepare(shop, "POST-1");

        assertEquals("VALIDATED", result.stage());
        assertTrue(result.shipReady());
        assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes WHERE id=1"));
        assertEquals("ozon:1", scalar("SELECT reservation_token FROM kiz_codes WHERE id=1"));
        assertNull(scalar("SELECT exemplar_id FROM ozon_exemplars WHERE id=1"));
        assertEquals(1, server.getRequestCount());
        assertEquals("/v3/posting/fbs/get", takeRequest().getPath());
    }

    @Test
    void duplicatePreparationReusesTheSameLocalReservation() throws Exception {
        server.enqueue(json(posting()));
        server.enqueue(json(posting()));
        OzonExemplarService service = service();

        assertEquals("VALIDATED", service.stageForPrint(shop, "POST-1").stage());
        assertEquals("VALIDATED", service.prepare(shop, "POST-1").stage());

        assertEquals(2, server.getRequestCount());
        assertEquals("/v3/posting/fbs/get", takeRequest().getPath());
        assertEquals("/v3/posting/fbs/get", takeRequest().getPath());
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_exemplar_jobs"));
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_exemplars"));
        assertEquals(1, count("SELECT COUNT(*) FROM kiz_codes WHERE status='RESERVED'"));
    }

    @Test
    void receivedKizIsSkippedInFavorOfAnInCirculationCode() throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE kiz_codes SET legal_status='RECEIVED' WHERE id=1");
            statement.execute("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at) "
                    + "VALUES(2,1,1,'010460000000000121READY','READY','04600000000001','AVAILABLE','IN_CIRCULATION',"
                    + "'2026-08-18T00:00:00Z','2026-08-18T00:00:00Z')");
        }
        server.enqueue(json(posting()));

        OzonPreparationResult result = service().prepare(shop, "POST-1");

        assertEquals("VALIDATED", result.stage());
        assertEquals("AVAILABLE", scalar("SELECT status FROM kiz_codes WHERE id=1"));
        assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes WHERE id=2"));
        assertEquals("2", scalar("SELECT kiz_id FROM ozon_exemplars WHERE id=1"));
    }

    @Test
    void missingKizKeepsOneRetryableLocalJobAndMakesNoMutationRequest() throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM kiz_codes");
        }
        server.enqueue(json(posting()));

        OzonPreparationResult result = service().prepare(shop, "POST-1");

        assertEquals("CREATED", result.stage());
        assertEquals("kiz_unavailable", result.safeErrorCode());
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_exemplar_jobs"));
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_exemplars"));
        assertEquals(1, server.getRequestCount());
        assertEquals("/v3/posting/fbs/get", takeRequest().getPath());
    }

    @Test
    void concurrentDoubleClickIsSerializedAndReservesOnlyOneKiz() throws Exception {
        server.enqueue(json(posting()));
        server.enqueue(json(posting()));
        OzonExemplarService service = service();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<OzonPreparationResult> first = prepareAsync(service, ready, start);
        CompletableFuture<OzonPreparationResult> second = prepareAsync(service, ready, start);
        assertTrue(ready.await(1, TimeUnit.SECONDS));
        start.countDown();

        assertEquals("VALIDATED", first.join().stage());
        assertEquals("VALIDATED", second.join().stage());
        assertEquals(2, server.getRequestCount());
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_exemplars"));
        assertEquals(1, count("SELECT COUNT(*) FROM kiz_codes WHERE status='RESERVED'"));
        assertEquals(0, count("SELECT COUNT(*) FROM kiz_codes WHERE status='CONSUMED'"));
    }

    private OzonExemplarService service() {
        return new OzonExemplarService(
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

    private CompletableFuture<OzonPreparationResult> prepareAsync(
            OzonExemplarService service, CountDownLatch ready, CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                assertTrue(start.await(1, TimeUnit.SECONDS));
                return service.prepare(shop, "POST-1");
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private RecordedRequest takeRequest() throws Exception {
        return server.takeRequest(1, TimeUnit.SECONDS);
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = Database.getConnection();
                ResultSet result = connection.createStatement().executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
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

    private static String posting() {
        return "{\"result\":{\"posting_number\":\"POST-1\",\"status\":\"awaiting_packaging\","
                + "\"products\":[{\"sku\":101,\"offer_id\":\"sku-a\",\"name\":\"Item\",\"quantity\":1}],"
                + "\"requirements\":{\"products_requiring_mandatory_mark\":[\"101\"]},"
                + "\"available_actions\":[\"ship\"]}}";
    }
}
