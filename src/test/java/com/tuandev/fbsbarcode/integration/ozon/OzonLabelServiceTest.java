package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonLabelServiceTest {
    @TempDir
    Path temporaryDirectory;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        System.setProperty("wcode.appdata.dir", temporaryDirectory.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,?,?,?,?)")) {
            statement.setString(1, "Ozon");
            statement.setString(2, Marketplace.OZON.name());
            statement.setString(3, "client-1");
            statement.setString(4, "secret");
            statement.executeUpdate();
        }
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ozon_postings(shop_id,posting_number,status,synced_at)
                        VALUES(1,'posting-1','awaiting_deliver','2026-08-18T00:00:00Z')
                        """)) {
            statement.executeUpdate();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("wcode.appdata.dir");
        server.shutdown();
    }

    @Test
    void ambiguousCreateWithoutTaskIdIsNeverSubmittedAgainBlindly() {
        OzonLabelRepository repository = new OzonLabelRepository();
        repository.findOrCreate(1, "posting-1");
        repository.update(1, "posting-1", null, "RECONCILE_REQUIRED", null, "transport");
        Shop shop = new Shop(1, "Ozon", Marketplace.OZON, "client-1", "secret");

        IOException failure = assertThrows(IOException.class, () -> new OzonLabelService()
                .downloadOfficialPdf(shop, "posting-1", temporaryDirectory.resolve("label.pdf").toFile()));

        assertEquals(
                "Ozon did not return a label task ID. WCode will not create another label job automatically.",
                failure.getMessage());
        assertEquals("RECONCILE_REQUIRED", repository.find(1, "posting-1").status());
    }

    @Test
    void downloadsOfficialBigLabelWithoutSendingApiCredentialToCdn() throws Exception {
        server.enqueue(json("{\"result\":{\"tasks\":["
                + "{\"task_id\":2002,\"task_type\":\"small_label\"},"
                + "{\"task_id\":2001,\"task_type\":\"big_label\"}]}}"));
        server.enqueue(json("{\"result\":{\"status\":\"completed\",\"file_url\":\""
                + server.url("/cdn/official.pdf") + "\"}}"));
        byte[] pdf = "%PDF-1.7\nofficial-ozon-label".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/pdf").setBody(new okio.Buffer().write(pdf)));
        Path target = temporaryDirectory.resolve("official-label.pdf");

        new OzonLabelService(new OzonLabelRepository(), this::client)
                .downloadOfficialPdf(
                        new Shop(1, "Ozon", Marketplace.OZON, "client-1", "secret"),
                        "posting-1",
                        target.toFile());

        assertTrue(java.util.Arrays.equals(pdf, java.nio.file.Files.readAllBytes(target)));
        RecordedRequest create = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest poll = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest download = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v2/posting/fbs/package-label/create", create.getPath());
        assertTrue(poll.getBody().readUtf8().contains("\"task_id\":\"2001\""));
        assertEquals("/cdn/official.pdf", download.getPath());
        assertNull(download.getHeader("Api-Key"));
        OzonLabelRepository.LabelJob persisted = new OzonLabelRepository().find(1, "posting-1");
        assertEquals("2001", persisted.taskId());
        assertNull(persisted.outputPath());
    }

    @Test
    void acceptsCurrentV2DirectTaskIdResponse() throws Exception {
        server.enqueue(json("{\"result\":{\"task_id\":3001}}"));
        server.enqueue(json("{\"result\":{\"status\":\"completed\",\"file_url\":\""
                + server.url("/cdn/direct-task.pdf") + "\"}}"));
        byte[] pdf = "%PDF-1.7\ndirect-task-label".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/pdf").setBody(new okio.Buffer().write(pdf)));

        new OzonLabelService(new OzonLabelRepository(), this::client)
                .downloadOfficialPdf(
                        new Shop(1, "Ozon", Marketplace.OZON, "client-1", "secret"),
                        "posting-1",
                        temporaryDirectory.resolve("direct-task.pdf").toFile());

        assertEquals("3001", new OzonLabelRepository().find(1, "posting-1").taskId());
    }

    private OzonApiClient client(int shopId, OzonCredentials credentials) {
        return new OzonApiClient(
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
                new OzonRetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1)));
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(body);
    }
}
