package com.vncode.app.integration.ozon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic local HTTP/SQLite only; no seller account or business mutation. */
class OzonCatalogPaginationTest {
    @TempDir Path root;
    private MockWebServer server;
    private OzonApiClient api;
    private final AtomicInteger lists = new AtomicInteger();
    private final AtomicInteger details = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        System.setProperty("vncode.appdata.dir", root.toString());
        Database.initDatabase();
        try (var c = Database.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Fixture','OZON','fixture-client','fixture-key')");
        }
        server = new MockWebServer();
        server.start();
        api = new OzonApiClient(1, new OzonCredentials("fixture-client", "fixture-key"), server.url("/"),
                new OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build(),
                new OzonApiRateLimiter(Duration.ZERO),
                new OzonRetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1)));
    }

    @AfterEach void cleanup() throws Exception {
        System.clearProperty("vncode.appdata.dir");
        server.shutdown();
    }

    @Test void totalItemsKeepsReadingShortPagesUntilAllProductsAreStored() throws Exception {
        serve((call, cursor) -> page(call, "\"total_items\":2,", "cursor-" + call));
        assertEquals(2, new OzonCatalogSyncService(1, api).syncFromStart());
        assertEquals(2, lists.get());
        assertEquals(2, stored());
        assertTrue(new OzonSyncStateRepository().productCatalogComplete(1));
    }

    @Test void unknownTotalMustReadTheEmptyTailRatherThanTreatingAShortPageAsComplete() throws Exception {
        serve((call, cursor) -> call <= 2 ? page(call, "", "cursor-" + call)
                : json("{\"result\":{\"items\":[],\"last_id\":\"cursor-2\"}}"));
        assertEquals(2, new OzonCatalogSyncService(1, api).syncFromStart());
        assertEquals(3, lists.get());
        assertEquals(2, stored());
        assertTrue(new OzonSyncStateRepository().productCatalogComplete(1));
    }

    @Test void anEmptyTailCannotHideProductsStillPromisedByTotalItems() throws Exception {
        serve((call, cursor) -> call == 1 ? page(1, "\"total_items\":2,", "cursor-1")
                : json("{\"result\":{\"total_items\":2,\"items\":[],\"last_id\":\"cursor-1\"}}"));
        assertThrows(IOException.class, () -> new OzonCatalogSyncService(1, api).syncFromStart());
        assertEquals(1, stored());
        assertFalse(new OzonSyncStateRepository().productCatalogComplete(1));
        assertEquals("cursor-1", new OzonSyncStateRepository().find(1).productsLastId());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aRepeatedCursorCannotBeMarkedAsACompletedCatalog(boolean knownTotal) throws Exception {
        serve((call, cursor) -> page(call, knownTotal ? "\"total\":2," : "", "repeated"));
        assertEquals("OZON_REPEATED_PRODUCT_CURSOR", assertThrows(IOException.class,
                () -> new OzonCatalogSyncService(1, api).syncFromStart()).getMessage());
        assertEquals(2, lists.get());
        assertEquals(1, stored());
        assertFalse(new OzonSyncStateRepository().productCatalogComplete(1));
    }

    @Test void aBlankFinalCursorIsAllowedOnlyAfterTheDeclaredTotalIsReached() throws Exception {
        serve((call, cursor) -> page(1, "\"total_items\":1,", ""));
        assertEquals(1, new OzonCatalogSyncService(1, api).syncFromStart());
        assertEquals(1, lists.get());
        assertEquals(1, details.get());
        assertEquals(1, stored());
        assertTrue(new OzonSyncStateRepository().productCatalogComplete(1));
        assertEquals("", new OzonSyncStateRepository().find(1).productsLastId());
    }

    @Test void aBlankIntermediateCursorCannotHideProductsStillMissing() throws Exception {
        serve((call, cursor) -> page(1, "\"total_items\":2,", ""));
        assertEquals("OZON_REPEATED_PRODUCT_CURSOR", assertThrows(IOException.class,
                () -> new OzonCatalogSyncService(1, api).syncFromStart()).getMessage());
        assertEquals(0, details.get());
        assertEquals(0, stored());
        assertFalse(new OzonSyncStateRepository().productCatalogComplete(1));
    }

    @Test void repeatedProductsWithANewCursorCannotSatisfyTheDeclaredTotal() throws Exception {
        serve((call, cursor) -> page(1, "\"total\":2,", "cursor-" + call));
        assertThrows(IOException.class, () -> new OzonCatalogSyncService(1, api).syncFromStart());
        assertEquals(2, lists.get());
        assertEquals(1, details.get());
        assertEquals(1, stored());
        assertFalse(new OzonSyncStateRepository().productCatalogComplete(1));
        assertEquals("cursor-1", new OzonSyncStateRepository().find(1).productsLastId());
    }

    @Test void anEmptyTailWithoutTotalMustStillHonorThePreviouslyDeclaredTotal() throws Exception {
        serve((call, cursor) -> call == 1 ? page(1, "\"total\":2,", "cursor-1")
                : json("{\"result\":{\"items\":[],\"last_id\":\"cursor-1\"}}"));
        assertThrows(IOException.class, () -> new OzonCatalogSyncService(1, api).syncFromStart());
        assertEquals(1, stored());
        assertFalse(new OzonSyncStateRepository().productCatalogComplete(1));
        assertEquals("cursor-1", new OzonSyncStateRepository().find(1).productsLastId());
    }

    @Test void aChangedTotalOnALaterPageCannotClaimACompleteCatalog() throws Exception {
        serve((call, cursor) -> page(call, call == 1 ? "\"total\":3," : "\"total\":2,", "cursor-" + call));
        assertThrows(IOException.class, () -> new OzonCatalogSyncService(1, api).syncFromStart());
        assertEquals(1, stored());
        assertEquals(1, details.get(), "The contradictory page must not replace stored product details");
        assertFalse(new OzonSyncStateRepository().productCatalogComplete(1));
        assertEquals("cursor-1", new OzonSyncStateRepository().find(1).productsLastId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"total\":1,\"total_items\":2,", "\"total_items\":-1,", "\"total_items\":1.5,", "\"total_items\":null,"})
    void invalidOrConflictingTotalsFailBeforeCommittingThePage(String totals) throws Exception {
        serve((call, cursor) -> page(1, totals, "cursor-1"));
        assertThrows(IOException.class, () -> new OzonCatalogSyncService(1, api).syncFromStart());
        assertEquals(0, details.get());
        assertEquals(0, stored());
        assertFalse(new OzonSyncStateRepository().productCatalogComplete(1));
    }

    @Test void theCanonicalTotalStillFinishesACompleteShortPage() throws Exception {
        serve((call, cursor) -> page(1, "\"total\":1,", "cursor-1"));
        assertEquals(1, new OzonCatalogSyncService(1, api).syncFromStart());
        assertEquals(1, lists.get());
        assertEquals(1, stored());
        assertTrue(new OzonSyncStateRepository().productCatalogComplete(1));
    }

    @FunctionalInterface private interface ProductPages { MockResponse read(int call, String cursor); }

    private void serve(ProductPages pages) {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
                String path = request.getPath();
                if (path.endsWith("/v3/product/list")) {
                    return pages.read(lists.incrementAndGet(), body.get("last_id").getAsString());
                }
                if (path.endsWith("/v3/product/info/list")) {
                    details.incrementAndGet();
                    String id = body.getAsJsonArray("product_id").get(0).getAsString();
                    return json("{\"items\":[{\"id\":\"" + id + "\",\"offer_id\":\"offer-" + id
                            + "\",\"name\":\"Fixture\",\"sku\":\"" + id + "\",\"barcodes\":[]}]}");
                }
                if (path.endsWith("/v4/product/info/attributes")) {
                    String id = body.getAsJsonObject("filter").getAsJsonArray("product_id").get(0).getAsString();
                    return json("{\"result\":[{\"id\":\"" + id + "\",\"offer_id\":\"offer-" + id
                            + "\",\"description_category_id\":1,\"type_id\":2,\"attributes\":[]}]}");
                }
                return json("{\"result\":[]}");
            }
        });
    }

    private static MockResponse page(int page, String totalFields, String cursor) {
        String id = Integer.toString(100 + page);
        return json("{\"result\":{" + totalFields + "\"items\":[{\"product_id\":\"" + id
                + "\",\"offer_id\":\"offer-" + id + "\"}],\"last_id\":\"" + cursor + "\"}}");
    }

    private int stored() throws Exception {
        try (var c = Database.getConnection(); var s = c.createStatement();
             var r = s.executeQuery("SELECT count(*) FROM ozon_products WHERE shop_id=1")) {
            assertTrue(r.next());
            return r.getInt(1);
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().addHeader("Content-Type", "application/json").setBody(body);
    }
}
