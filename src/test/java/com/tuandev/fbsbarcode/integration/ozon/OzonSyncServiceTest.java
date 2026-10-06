package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.Database;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonSyncServiceTest {
    @TempDir
    Path temporaryDirectory;

    private MockWebServer server;
    private OzonApiClient api;

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
        api = new OzonApiClient(
                1,
                new OzonCredentials("client-1", "secret"),
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

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("wcode.appdata.dir");
        server.shutdown();
    }

    @Test
    void catalogDoesNotAdvanceCursorWhenDetailFetchFails() throws Exception {
        server.enqueue(json("{\"result\":{\"items\":[{\"product_id\":\"90071992547409931234\","
                + "\"offer_id\":\"offer-1\"}],\"last_id\":\"cursor-2\"}}"));
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThrows(OzonApiException.class, () -> new OzonCatalogSyncService(1, api).sync());

        assertEquals(0, count("SELECT COUNT(*) FROM ozon_products"));
        assertEquals("", new OzonSyncStateRepository().find(1).productsLastId());
        assertEquals("upstream", new OzonSyncStateRepository().find(1).lastError());
    }

    @Test
    void postingPageCommitsButRollingCursorDoesNotAdvanceAfterLaterPageFailure() throws Exception {
        server.enqueue(json("{\"postings\":[" + posting()
                + "],\"cursor\":\"cursor-2\",\"has_next\":true}"));
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThrows(OzonApiException.class, () -> new OzonPostingSyncService(1, api).sync());

        assertEquals(1, count("SELECT COUNT(*) FROM ozon_postings"));
        assertEquals(1, count("SELECT COUNT(*) FROM ozon_posting_items"));
        assertEquals("", new OzonSyncStateRepository().find(1).postingsChangedSince());
        server.takeRequest(1, TimeUnit.SECONDS);
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8()
                .contains("\"cursor\":\"cursor-2\""));
    }

    @Test
    void invalidPostingCursorIsRecordedWithoutAdvancingRollingWindow() throws Exception {
        server.enqueue(json("{\"postings\":[" + posting() + "],\"cursor\":\"\",\"has_next\":true}"));

        assertThrows(IOException.class, () -> new OzonPostingSyncService(1, api).sync());

        OzonSyncState syncState = new OzonSyncStateRepository().find(1);
        assertEquals("", syncState.postingsChangedSince());
        assertEquals("invalid_response", syncState.lastError());
    }

    @Test
    void activePostingDetailsReplaceListRequirementsBeforeKizReadinessChecks() throws Exception {
        server.enqueue(json("{\"postings\":[" + posting()
                + "],\"cursor\":\"\",\"has_next\":false}"));
        server.enqueue(json("{\"result\":{\"posting_number\":\"POST-TEXT-1\","
                + "\"status\":\"awaiting_packaging\","
                + "\"products\":[{\"sku\":\"101\",\"offer_id\":\"offer-1\",\"quantity\":2}],"
                + "\"requirements\":{\"products_requiring_mandatory_mark\":[\"101\"]},"
                + "\"available_actions\":[]}}"));
        OzonPostingSyncService service = new OzonPostingSyncService(1, api);

        service.sync();
        assertEquals(1, service.refreshActiveDetails());

        OzonPostingDto stored = new OzonPostingRepository().find(1, "POST-TEXT-1");
        assertEquals(java.util.List.of("101"), stored.requirements().mandatoryMarkProductIds());
        server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v3/posting/fbs/get", server.takeRequest(1, TimeUnit.SECONDS).getPath());
    }

    @Test
    void unfulfilledQueueMovesPostingChangedOnOzonWebsiteIntoLocalPackingState() throws Exception {
        server.enqueue(json("{\"postings\":[" + posting()
                + "],\"cursor\":\"\",\"has_next\":false}"));
        OzonPostingSyncService service = new OzonPostingSyncService(1, api);
        service.sync();

        server.enqueue(json("{\"postings\":[{\"posting_number\":\"POST-TEXT-1\","
                + "\"status\":\"awaiting_deliver\","
                + "\"products\":[{\"sku\":\"101\",\"offer_id\":\"offer-1\",\"quantity\":2}],"
                + "\"requirements\":{},\"available_actions\":[]}],"
                + "\"cursor\":\"\",\"has_next\":false}"));

        assertEquals(1, service.syncUnfulfilled());

        OzonPostingDto stored = new OzonPostingRepository().find(1, "POST-TEXT-1");
        assertEquals("awaiting_deliver", stored.status());
        server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v4/posting/fbs/unfulfilled/list", server.takeRequest(1, TimeUnit.SECONDS).getPath());
    }

    @Test
    void successfulCatalogPageKeepsExternalIdsAsTextAndAdvancesCursorAtomically() throws Exception {
        String externalId = "90071992547409931234";
        server.enqueue(json("{\"result\":{\"items\":[{\"product_id\":\"" + externalId
                + "\",\"offer_id\":\"offer-1\"}],\"last_id\":\"cursor-2\"}}"));
        server.enqueue(json("{\"items\":[{\"id\":\"" + externalId
                + "\",\"offer_id\":\"offer-1\",\"sku\":\"sku-1\",\"name\":\"Item\","
                + "\"primary_image\":\"https://cdn.example/item.jpg\"}]}"));
        server.enqueue(json("{\"result\":[{\"id\":\"" + externalId
                + "\",\"offer_id\":\"offer-card\",\"description_category_id\":\"17000001\","
                + "\"type_id\":\"90001\",\"attributes\":["
                + "{\"id\":10096,\"values\":[{\"value\":\"deep black\"}]},"
                + "{\"id\":4295,\"values\":[{\"value\":\"176\"}]},"
                + "{\"id\":9163,\"values\":[{\"value\":\"Women\"}]},"
                + "{\"id\":9024,\"values\":[{\"value\":\"seller-article\"}]}]}]}"));
        server.enqueue(json("{\"result\":[{\"description_category_id\":17000001,"
                + "\"category_name\":\"Clothing\",\"children\":[{\"type_id\":90001,"
                + "\"type_name\":\"Sportswear\",\"children\":[]}]}]}"));
        server.enqueue(json("{\"result\":["
                + "{\"id\":10096,\"name\":\"Цвет товара\"},"
                + "{\"id\":4295,\"name\":\"Российский размер\"},"
                + "{\"id\":9163,\"name\":\"Пол\"},"
                + "{\"id\":9024,\"name\":\"Код продавца\"}]}"));

        assertEquals(1, new OzonCatalogSyncService(1, api).sync());

        assertEquals(externalId, scalar("SELECT product_id FROM ozon_products"));
        assertEquals("https://cdn.example/item.jpg", scalar("SELECT primary_image_url FROM ozon_products"));
        assertEquals("seller-article", scalar("SELECT article FROM ozon_products"));
        assertEquals("deep black", scalar("SELECT color FROM ozon_products"));
        assertEquals("176", scalar("SELECT size FROM ozon_products"));
        assertEquals("Sportswear", scalar("SELECT category FROM ozon_products"));
        assertEquals("Women", scalar("SELECT gender FROM ozon_products"));
        assertEquals("cursor-2", new OzonSyncStateRepository().find(1).productsLastId());
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath().endsWith("/v3/product/list"));
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath().endsWith("/v3/product/info/list"));
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath().endsWith("/v4/product/info/attributes"));
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath()
                .endsWith("/v1/description-category/tree"));
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath()
                .endsWith("/v1/description-category/attribute"));
    }

    @Test
    void catalogPageWithoutForwardCursorFailsClosedBeforePersistingProducts() throws Exception {
        server.enqueue(json("{\"result\":{\"items\":[{\"product_id\":\"101\","
                + "\"offer_id\":\"offer-1\"}],\"last_id\":\"\"}}"));
        server.enqueue(json("{\"items\":[{\"id\":\"101\",\"offer_id\":\"offer-1\"}]}"));

        assertThrows(IOException.class, () -> new OzonCatalogSyncService(1, api).sync());

        assertEquals(0, count("SELECT COUNT(*) FROM ozon_products"));
        OzonSyncState syncState = new OzonSyncStateRepository().find(1);
        assertEquals("", syncState.productsLastId());
        assertEquals("invalid_response", syncState.lastError());
    }

    private int count(String sql) throws Exception {
        try (Connection connection = Database.getConnection();
                ResultSet result = connection.createStatement().executeQuery(sql)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = Database.getConnection();
                ResultSet result = connection.createStatement().executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String posting() {
        return "{\"posting_number\":\"POST-TEXT-1\",\"status\":\"awaiting_packaging\","
                + "\"products\":[{\"sku\":\"101\",\"offer_id\":\"offer-1\",\"quantity\":2}],"
                + "\"requirements\":{},\"available_actions\":[]}";
    }
}
