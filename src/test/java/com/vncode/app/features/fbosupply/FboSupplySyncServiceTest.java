package com.vncode.app.features.fbosupply;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.wb.fbw.*;
import com.vncode.app.models.Shop;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class FboSupplySyncServiceTest {
    @TempDir Path directory;
    MockWebServer server; FboSupplyRepository repository; FboSupplySyncService service;
    @BeforeEach void init() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString()); Database.initDatabase();
        server = new MockWebServer(); server.start(); repository = new FboSupplyRepository();
        var http = new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(3)).build();
        service = new FboSupplySyncService(repository,
                shop -> new WbFbwApiClient(shop.getId(), shop.getApiKey(), server.url("/"), http, new WbFbwRateLimiter(Duration.ZERO)),
                shop -> new OzonApiClient(shop.getId(), new OzonCredentials(shop.getClientId(), shop.getApiKey()), server.url("/"), http,
                        new OzonApiRateLimiter(Duration.ZERO), new OzonRetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1))));
    }
    @AfterEach void clear() throws Exception { try { server.shutdown(); } finally { System.clearProperty("vncode.appdata.dir"); } }
    void respond(String body) { server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body)); }
    static JsonArray summaries(int start, int count) {
        var rows = new JsonArray();
        for (int id = start; id < start + count; id++) { var row = new JsonObject(); row.addProperty("preorderID", id); row.addProperty("statusID", 2); rows.add(row); }
        return rows;
    }
    void ozonPage(int start, int count, String next) {
        var listed = new JsonObject(); var ids = new JsonArray();
        for (int id = start; id < start + count; id++) ids.add(id);
        listed.add("order_ids", ids); if (next != null) listed.addProperty("last_id", next); respond(listed.toString());
        for (int from = start; from < start + count; from += 50) {
            var orders = new JsonArray();
            for (int id = from; id < Math.min(start + count, from + 50); id++) {
                var row = new JsonObject(); row.addProperty("order_id", id); row.addProperty("state", "READY_TO_SUPPLY"); orders.add(row);
            }
            var details = new JsonObject(); details.add("orders", orders); respond(details.toString());
        }
    }
    Shop ozonWithCachedItem() throws Exception {
        var shop = GtinWriteTestFixture.shop(Marketplace.OZON);
        repository.upsertOzonDetails(shop.getId(), JsonParser.parseString("""
                {"orders":[{"order_id":101,"state":"READY_TO_SUPPLY","supplies":[{"supply_id":202,"bundle_id":"bundle-202"}]}]}
                """).getAsJsonObject());
        repository.replaceOzonItems(shop.getId(), "101", "202", JsonParser.parseString("""
                [{"sku":300,"product_id":301,"offer_id":"A","name":"Keep existing","quantity":6}]
                """).getAsJsonArray());
        return shop;
    }
    @Test void wbReadsBeyondFirstThousandSuppliesBeforeReportingCompletion() throws Exception {
        var shop = GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        respond(summaries(1, 1000).toString()); respond(summaries(1001, 1).toString());
        assertEquals(1001, service.syncOrders(shop).size()); assertNotNull(service.lastSyncedAt(shop));
        assertTrue(server.takeRequest().getPath().contains("offset=0")); assertTrue(server.takeRequest().getPath().contains("offset=1000"));
    }
    @Test void wbRepeatedPageCannotProduceACompletedSnapshot() throws Exception {
        var shop = GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        respond(summaries(1, 1000).toString()); respond(summaries(1, 1000).toString());
        assertThrows(IOException.class, () -> service.syncOrders(shop)); assertNull(service.lastSyncedAt(shop));
        assertEquals(1000, service.cachedOrders(shop).size());
    }
    @Test void ozonBoundedPassKeepsItsCursorUntilHistoryReallyEnds() throws Exception {
        var shop = GtinWriteTestFixture.shop(Marketplace.OZON);
        for (int page = 0; page < 5; page++) ozonPage(1 + page * 100, 100, "cursor-" + page);
        assertEquals(500, service.syncOrders(shop).size()); assertEquals("cursor-4", service.pendingOrdersCursor(shop));
        ozonPage(1, 100, "cursor-0"); ozonPage(501, 1, "");
        assertEquals(501, service.syncOrders(shop).size()); assertEquals("", service.pendingOrdersCursor(shop));
    }
    @Test void missingOzonOrderDetailsCannotReportSuccessfulEmptyHistory() throws Exception {
        var shop = GtinWriteTestFixture.shop(Marketplace.OZON);
        respond("{\"order_ids\":[101],\"last_id\":\"\"}"); respond("{\"orders\":[]}");
        assertEquals("FBO_INCOMPLETE_ORDER_DETAILS", assertThrows(IOException.class, () -> service.syncOrders(shop)).getMessage());
        assertNull(service.lastSyncedAt(shop)); assertTrue(service.cachedOrders(shop).isEmpty());
    }
    @Test void fullOzonPageWithoutNextCursorCannotClaimHistoryIsComplete() throws Exception {
        var shop = GtinWriteTestFixture.shop(Marketplace.OZON); ozonPage(1, 100, null);
        assertEquals("FBO_INVALID_CURSOR", assertThrows(IOException.class, () -> service.syncOrders(shop)).getMessage());
        assertNull(service.lastSyncedAt(shop));
    }
    @Test void malformedOzonBundleCannotDeleteExistingSupplyItems() throws Exception {
        var shop = ozonWithCachedItem(); respond("{}");
        var order = service.cachedOrders(shop).getFirst();
        assertEquals("FBO_INVALID_RESPONSE", assertThrows(IOException.class, () -> service.syncItems(shop, order)).getMessage());
        assertEquals(6, service.cachedItems(shop, "101").getFirst().quantity());
    }
    @Test void repeatedOzonBundleCursorCannotReplaceExistingSupplyWithPartialData() throws Exception {
        var shop = ozonWithCachedItem();
        respond("{\"items\":[{\"sku\":401,\"quantity\":1}],\"has_next\":true,\"last_id\":\"again\"}");
        respond("{\"items\":[{\"sku\":402,\"quantity\":1}],\"has_next\":true,\"last_id\":\"again\"}");
        assertEquals("FBO_INVALID_CURSOR", assertThrows(IOException.class, () -> service.syncItems(shop, service.cachedOrders(shop).getFirst())).getMessage());
        assertEquals(1, service.cachedItems(shop, "101").size()); assertEquals(6, service.cachedItems(shop, "101").getFirst().quantity());
    }
    @Test void completeOzonBundleReplacesItemsOnlyAfterAllPagesWereRead() throws Exception {
        var shop = ozonWithCachedItem();
        respond("{\"items\":[{\"sku\":401,\"quantity\":2}],\"has_next\":true,\"last_id\":\"next\"}");
        respond("{\"items\":[{\"sku\":402,\"quantity\":3}],\"has_next\":false}");
        var items = service.syncItems(shop, service.cachedOrders(shop).getFirst());
        assertEquals(2, items.size()); assertEquals(5, items.stream().mapToInt(FboSupplyItem::quantity).sum());
    }
    @Test void aForeignMarketplaceOrderCannotBeRefreshedThroughTheSelectedShop()throws Exception{
        var shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        var foreign=new FboSupplyOrder(shop.getId(),Marketplace.OZON,"101","202","","",FboSupplyStatusGroup.UNKNOWN,"","","",0,0,"");
        assertThrows(IllegalArgumentException.class,()->service.syncItems(shop,foreign));assertEquals(0,server.getRequestCount());
    }
}
