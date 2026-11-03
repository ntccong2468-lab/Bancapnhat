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

class OzonMappingAtomicIndependentTest {
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

    @Test void changedSkuMappingAfterBatchSnapshotMustNotReserveOldKiz() throws Exception {
        var batch=service().openPrintBatch(shop);
        try(var c=Database.getConnection();var st=c.createStatement()){st.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(1,'00000000000024','Other','2026-08-18T00:00:00Z')");}
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE ozon_product_gtin_mappings SET gtin=? WHERE shop_id=1 AND sku='101'")){
            p.setString(1,"00000000000024");org.junit.jupiter.api.Assertions.assertEquals(1,p.executeUpdate());
        }
        server.enqueue(json(posting()));
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,()->batch.stageForPrint("POST-1"),"stale GTIN snapshot must not reserve previous GTIN stock");
        assertEquals("AVAILABLE",scalar("SELECT status FROM kiz_codes WHERE id=1"));
    }

    @Test void writerCannotChangeMappingWhileReservationGuardRuns() throws Exception {
        new OzonPostingRepository().upsertDetail(1, OzonJson.requirePostingDetail(com.google.gson.JsonParser.parseString(posting()).getAsJsonObject(), "POST-1"));
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var plan = new OzonRequirementGuard.PreparationPlan("POST-1", java.util.List.of(
                new OzonRequirementGuard.RequiredItem(0, "101", "101", "04600000000001", 1, true)));
        var reservation = CompletableFuture.supplyAsync(() -> new OzonExemplarJobRepository().stageLocalForPrint(
                1, plan, null, () -> {}, () -> {
                    entered.countDown();
                    try {
                        if (!proceed.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    OzonRequirementGuard.requireCurrentPurchasedMappings(1, plan, null);
                }));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var writerStarted = new CountDownLatch(1);
        var writer = CompletableFuture.runAsync(() -> {
            try (var c = Database.getConnection(); var p = c.prepareStatement("DELETE FROM ozon_product_gtin_mappings WHERE shop_id=1 AND sku='101'")) {
                writerStarted.countDown();
                p.executeUpdate();
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        assertTrue(writerStarted.await(3, TimeUnit.SECONDS));
        Thread.sleep(100);
        org.junit.jupiter.api.Assertions.assertFalse(writer.isDone(), "mapping writer must wait until reservation ends");
        proceed.countDown();
        assertTrue(reservation.get(5, TimeUnit.SECONDS).ready());
        writer.get(5, TimeUnit.SECONDS);
        assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes WHERE id=1"));
    }

    @Test void failingGuardRollsBackReservation() throws Exception {
        var plan = new OzonRequirementGuard.PreparationPlan("POST-1", java.util.List.of(
                new OzonRequirementGuard.RequiredItem(0, "101", "101", "04600000000001", 1, true)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new OzonExemplarJobRepository().stageLocalForPrint(1, plan, null, () -> {},
                        () -> { throw new IllegalStateException("changed"); }));
        assertEquals("AVAILABLE", scalar("SELECT status FROM kiz_codes WHERE id=1"));
        assertEquals(0, count("SELECT COUNT(*) FROM ozon_exemplar_jobs"));
    }

    @Test void changedShopIdentityBeforeStageDoesNotReserve() throws Exception {
        var batch = service().openPrintBatch(shop);
        try (var c = Database.getConnection(); var p = c.prepareStatement("UPDATE shops SET client_id=? WHERE id=1")) {
            p.setString(1, "other-client");
            assertEquals(1, p.executeUpdate());
        }
        server.enqueue(json(posting()));
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> batch.stageForPrint("POST-1"));
        assertEquals("AVAILABLE", scalar("SELECT status FROM kiz_codes WHERE id=1"));
        assertEquals(0, count("SELECT COUNT(*) FROM ozon_exemplar_jobs"));
    }

    @Test void mappingChangedAfterReservationPreventsPdfPublication() throws Exception {
        server.enqueue(json(posting()));
        assertEquals("VALIDATED", service().stageForPrint(shop, "POST-1").stage());
        var jobs = new OzonExemplarJobRepository();
        var job = jobs.find(1, "POST-1");
        var proof = OzonExemplarJobRepository.printProof(job, jobs.bindings(job.id()));
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(1,'00000000000024','Other','2026-08-18T00:00:00Z')");
            s.execute("UPDATE ozon_product_gtin_mappings SET gtin='00000000000024' WHERE shop_id=1 AND sku='101'");
        }
        var published = new java.util.concurrent.atomic.AtomicBoolean();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> jobs.requireCurrentBindingsBeforeSend(1, "POST-1", job.id()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> jobs.consumePrintedKizBatch(1, java.util.Map.of("POST-1", proof), () -> published.set(true)));
        org.junit.jupiter.api.Assertions.assertFalse(published.get());
        assertEquals("RESERVED", scalar("SELECT status FROM kiz_codes WHERE id=1"));
        assertEquals("VALIDATED", jobs.find(1, "POST-1").stage().name());
    }

    @Test void mappingWriterWaitsUntilPdfPublicationCommits() throws Exception {
        server.enqueue(json(posting()));
        assertEquals("VALIDATED", service().stageForPrint(shop, "POST-1").stage());
        var jobs = new OzonExemplarJobRepository();
        var job = jobs.find(1, "POST-1");
        var proof = OzonExemplarJobRepository.printProof(job, jobs.bindings(job.id()));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var publish = CompletableFuture.runAsync(() -> jobs.consumePrintedKizBatch(1, java.util.Map.of("POST-1", proof), () -> {
            entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var started = new CountDownLatch(1);
        var writer = CompletableFuture.runAsync(() -> {
            try (var c = Database.getConnection(); var p = c.prepareStatement("DELETE FROM ozon_product_gtin_mappings WHERE shop_id=1 AND sku='101'")) {
                started.countDown(); p.executeUpdate();
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        assertTrue(started.await(3, TimeUnit.SECONDS)); Thread.sleep(100);
        org.junit.jupiter.api.Assertions.assertFalse(writer.isDone());
        release.countDown(); publish.get(5, TimeUnit.SECONDS); writer.get(5, TimeUnit.SECONDS);
        assertEquals("CONSUMED", scalar("SELECT status FROM kiz_codes WHERE id=1"));
    }

    @Test void acceptedHistoricalReprintDoesNotRequireCurrentMapping() throws Exception {
        server.enqueue(json(posting()));
        assertEquals("VALIDATED", service().stageForPrint(shop, "POST-1").stage());
        var jobs = new OzonExemplarJobRepository();
        var job = jobs.find(1, "POST-1");
        var proof = OzonExemplarJobRepository.printProof(job, jobs.bindings(job.id()));
        jobs.consumePrintedKizBatch(1, java.util.Map.of("POST-1", proof));
        try (var c = Database.getConnection(); var p = c.prepareStatement("DELETE FROM ozon_product_gtin_mappings WHERE shop_id=1 AND sku='101'")) { p.executeUpdate(); }
        var published = new java.util.concurrent.atomic.AtomicBoolean();
        jobs.consumePrintedKizBatch(1, java.util.Map.of("POST-1", proof), () -> published.set(true));
        assertTrue(published.get());
        assertEquals("CONSUMED", scalar("SELECT status FROM kiz_codes WHERE id=1"));
    }
}
