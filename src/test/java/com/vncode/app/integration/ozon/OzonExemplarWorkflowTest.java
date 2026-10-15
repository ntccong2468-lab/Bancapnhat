package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
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
        System.setProperty("vncode.appdata.dir", temporaryDirectory.toString());
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
        System.clearProperty("vncode.appdata.dir");
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
    @Test void productionPrintFlowUsesCategoryStockWithDifferentGtinAndReusesItsDurableProof()throws Exception{
        new OzonCatalogRepository().upsertPage(1,java.util.List.of(new OzonProductDto("9001","offer-1","101","Synthetic","",false,"",java.util.List.of())),"");
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("DELETE FROM ozon_product_gtin_mappings");}
        com.vncode.app.testsupport.CategoryStockFixture.add(shop,"9001",temporaryDirectory,1);
        server.enqueue(json(posting()));server.enqueue(json(posting()));var service=service();
        assertTrue(service.stageForPrint(shop,"POST-1").shipReady());assertTrue(service.stageForPrint(shop,"POST-1").shipReady());
        assertEquals(com.vncode.app.testsupport.CategoryStockFixture.GTIN,scalar("SELECT k.gtin FROM ozon_exemplars e JOIN kiz_codes k ON k.id=e.kiz_id"));
        assertEquals(1,count("SELECT count(*) FROM kiz_stock_category_reservations"));assertEquals(2,server.getRequestCount());
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

    @Test
    void hundredPostingBatchUsesOneClientBoundedFreshReadsAndOrderedExactReservations() throws Exception {
        try (Connection connection = Database.getConnection();
                var insert = connection.prepareStatement("""
                        INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at)
                        VALUES(?,1,1,?,?,'04600000000001','AVAILABLE','IN_CIRCULATION',?,?)
                        """)) {
            for (int index = 2; index <= 100; index++) {
                insert.setInt(1, index);
                insert.setString(2, "010460000000000121OFFLINE-" + index);
                insert.setString(3, "OFFLINE-" + index);
                insert.setString(4, "2026-10-13T00:00:00Z");
                insert.setString(5, "2026-10-13T00:00:00Z");
                insert.addBatch();
            }
            insert.executeBatch();
        }
        java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger clients = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch firstReads = new CountDownLatch(2);
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                assertEquals("/v3/posting/fbs/get", request.getPath());
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                    firstReads.countDown();
                    assertTrue(firstReads.await(3, TimeUnit.SECONDS));
                    String number = com.google.gson.JsonParser.parseString(request.getBody().readUtf8())
                            .getAsJsonObject().get("posting_number").getAsString();
                    return json(posting().replace("POST-1", number));
                } finally { active.decrementAndGet(); }
            }
        });
        var service = new OzonExemplarService(new OzonPostingRepository(), new OzonProductGtinMappingRepository(),
                new OzonExemplarJobRepository(), (shopId, credentials) -> {
                    clients.incrementAndGet();
                    return new OzonApiClient(shopId, credentials, server.url("/"),
                            new OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
                            new OzonApiRateLimiter(Duration.ZERO),
                            new OzonRetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1)));
                });
        java.util.List<String> numbers = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> "BATCH-" + index).toList();
        java.util.List<Integer> progress = new java.util.ArrayList<>();
        var result = service.stageForPrintBatch(shop, numbers, progress::add);
        assertEquals(100, result.completedCount());
        assertEquals(100, result.results().size());
        assertTrue(result.failed().isEmpty());
        assertEquals(numbers, result.results().stream().map(OzonPreparationResult::postingNumber).toList());
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 100).boxed().toList(), progress);
        assertEquals(1, clients.get());
        assertEquals(2, peak.get(), "Two read-only details, with no 100-thread or unbounded queue fan-out");
        assertEquals(100, server.getRequestCount());
        assertEquals(100, count("SELECT COUNT(DISTINCT kiz_id) FROM ozon_exemplars"));
        assertEquals(100, count("SELECT COUNT(*) FROM kiz_codes WHERE status='RESERVED'"));
        assertEquals("1", scalar("SELECT e.kiz_id FROM ozon_exemplars e JOIN ozon_exemplar_jobs j ON j.id=e.job_id WHERE j.posting_number='BATCH-0'"));
        assertEquals("100", scalar("SELECT e.kiz_id FROM ozon_exemplars e JOIN ozon_exemplar_jobs j ON j.id=e.job_id WHERE j.posting_number='BATCH-99'"));
        var resumed = service.stageForPrintBatch(shop, numbers, null);
        assertEquals(100, resumed.results().size());
        assertEquals(200, server.getRequestCount(), "Retry rechecks fresh details, never sends any KIZ/exemplar mutation");
        assertEquals(100, count("SELECT COUNT(*) FROM ozon_exemplars"));
        assertEquals(100, count("SELECT COUNT(*) FROM kiz_codes WHERE status='RESERVED'"));
        // The production PDF preparation boundary uses the same bounded two-detail window.
        var printOperation=OzonPrintBundleService.defaultPreparation(service).forBatch(shop);
        printOperation.prefetch(shop,numbers.subList(0,2));
        assertEquals(202,server.getRequestCount());
        assertEquals(numbers.getFirst(),printOperation.prepare(shop,numbers.getFirst()).postingNumber());
        assertEquals(numbers.get(1),printOperation.prepare(shop,numbers.get(1)).postingNumber());
        assertEquals(202,server.getRequestCount(),"PDF composition must consume the prepared window without duplicate detail reads");
        printOperation.prefetch(shop,numbers.subList(2,4));
        printOperation.prepare(shop,numbers.get(2));printOperation.prepare(shop,numbers.get(3));
        assertEquals(204,server.getRequestCount());
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
