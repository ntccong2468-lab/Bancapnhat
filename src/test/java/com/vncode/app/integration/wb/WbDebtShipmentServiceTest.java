package com.vncode.app.integration.wb;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WbDebtShipmentServiceTest {
    @TempDir Path data;
    private final Shop shop = new Shop(1, "WB", Marketplace.WILDBERRIES, null, "token");
    private final Gson gson = new Gson();

    @BeforeEach void init() throws Exception {
        System.setProperty("vncode.appdata.dir", data.toString());
        Database.initDatabase();
        try (Connection c = Database.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'WB','WILDBERRIES','token')");
            s.execute("INSERT INTO wb_supplies(shop_id,supply_id,done,order_count,name,synced_at) VALUES (1,'WB-GI-A',1,2,'Shipment A','2026-01-01'),(1,'WB-GI-B',1,1,'Shipment B','2026-01-01')");
        }
    }
    @AfterEach void cleanup() { System.clearProperty("vncode.appdata.dir"); }

    @Test void membershipComesOnlyFromReshipmentAndMissingDetailsRemainVisible() throws Exception {
        var calls = new AtomicInteger();
        WbApiClient api = new WbApiClient() {
            @Override public WbReshipmentResponse getReshipments(String key) {
                return gson.fromJson("{\"orders\":[{\"supplyID\":\"WB-GI-A\",\"orderID\":101},{\"supplyID\":\"WB-GI-B\",\"orderID\":202}]}", WbReshipmentResponse.class);
            }
            @Override public WbOrdersResponse getOrders(String key, long next, int limit, Long from, Long to) {
                calls.incrementAndGet();
                return gson.fromJson("{\"orders\":[],\"next\":0}", WbOrdersResponse.class);
            }
            @Override public WbArchiveOrdersResponse getArchiveOrders(String key, int year, int month, long next, int limit) throws IOException { throw new IOException("fixture archive unavailable"); }
            @Override public WbOrderStatusesResponse getOrderStatuses(String key, List<Long> ids) {
                return gson.fromJson("{\"orders\":[{\"id\":101,\"supplierStatus\":\"complete\",\"wbStatus\":\"waiting\"},{\"id\":202,\"supplierStatus\":\"complete\",\"wbStatus\":\"sorted\"}]}", WbOrderStatusesResponse.class);
            }
        };
        var snapshot = service(api).load(shop);
        assertEquals(2, snapshot.shipments().size());
        assertEquals(List.of(101L), snapshot.shipments().get(0).orders().stream().map(o -> o.getId()).toList());
        assertEquals(List.of(202L), snapshot.shipments().get(1).orders().stream().map(o -> o.getId()).toList());
        assertEquals("waiting", snapshot.shipments().get(0).orders().getFirst().getWbStatus());
        assertEquals("sorted", snapshot.shipments().get(1).orders().getFirst().getWbStatus());
        assertFalse(snapshot.complete());
        assertTrue(snapshot.warning().contains("Chưa đọc đủ chi tiết 2 đơn"));
        assertEquals(3, calls.get());
    }

    @Test void missingOrMalformedMembershipIsAnErrorNotEmpty() {
        var api = new WbApiClient() {
            @Override public WbReshipmentResponse getReshipments(String key) {
                return gson.fromJson("{\"orders\":[{\"supplyID\":\"WB-GI-A\",\"orderID\":101},{\"supplyID\":\"WB-GI-A\",\"orderID\":101}]}", WbReshipmentResponse.class);
            }
        };
        assertThrows(IOException.class, () -> service(api).load(shop));
    }

    @Test void failureToReadStatusesDoesNotDisplayCachedStatusesAsCurrent() throws Exception {
        try (Connection c = Database.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO wb_orders(shop_id,order_id,article,supplier_status,wb_status,synced_at) VALUES(1,101,'example','complete','waiting','2026-01-01')");
        }
        var api = new WbApiClient() {
            @Override public WbReshipmentResponse getReshipments(String key) {
                return gson.fromJson("{\"orders\":[{\"supplyID\":\"WB-GI-A\",\"orderID\":101}]}", WbReshipmentResponse.class);
            }
            @Override public WbOrderStatusesResponse getOrderStatuses(String key, List<Long> ids) throws IOException {
                throw new IOException("HTTP 429");
            }
        };
        var result = service(api).load(shop);
        assertFalse(result.complete());
        assertNull(result.shipments().getFirst().orders().getFirst().getWbStatus());
        assertTrue(result.warning().contains("Chưa đọc đủ trạng thái"));
    }

    @Test void loadsMatchingOrderFromLaterPageWithinThirtyDayWindow() throws Exception {
        var calls = new AtomicInteger();
        var api = new WbApiClient() {
            @Override public WbReshipmentResponse getReshipments(String key) {
                return gson.fromJson("{\"orders\":[{\"supplyID\":\"WB-GI-A\",\"orderID\":101}]}", WbReshipmentResponse.class);
            }
            @Override public WbOrdersResponse getOrders(String key, long next, int limit, Long from, Long to) {
                assertTrue(to - from <= 30L * 24 * 3600);
                calls.incrementAndGet();
                if (next == 0) {
                    StringBuilder b = new StringBuilder("{\"next\":1000,\"orders\":[");
                    for (int i = 0; i < 1000; i++) { if (i > 0) b.append(','); b.append("{\"id\":").append(1000 + i).append('}'); }
                    return gson.fromJson(b.append("]}").toString(), WbOrdersResponse.class);
                }
                assertEquals(1000, next);
                return gson.fromJson("{\"orders\":[{\"id\":101,\"article\":\"abc\"}],\"next\":0}", WbOrdersResponse.class);
            }
            @Override public WbOrderStatusesResponse getOrderStatuses(String key, List<Long> ids) {
                return gson.fromJson("{\"orders\":[{\"id\":101,\"supplierStatus\":\"complete\",\"wbStatus\":\"waiting\"}]}", WbOrderStatusesResponse.class);
            }
        };
        var result = service(api).load(shop);
        assertEquals(2, calls.get());
        assertEquals("abc", result.shipments().getFirst().orders().getFirst().getArticle());
        assertFalse(result.complete()); // product details not present in the local product catalog
        assertTrue(result.warning().contains("Chưa đủ ảnh hoặc thông tin"));
    }

    @Test void loadsNestedArchiveOrderByExactIdWithoutInventingTime() throws Exception {
        var archiveCalls = new AtomicInteger();
        var api = new WbApiClient() {
            @Override public WbReshipmentResponse getReshipments(String key) {
                return gson.fromJson("{\"orders\":[{\"supplyID\":\"WB-GI-A\",\"orderID\":101}]}", WbReshipmentResponse.class);
            }
            @Override public WbOrdersResponse getOrders(String key, long next, int limit, Long from, Long to) {
                return gson.fromJson("{\"orders\":[],\"next\":0}", WbOrdersResponse.class);
            }
            @Override public WbArchiveOrdersResponse getArchiveOrders(String key, int year, int month, long next, int limit) {
                archiveCalls.incrementAndGet();
                return gson.fromJson("{\"orders\":[{\"id\":101,\"createdAt\":\"2025-05-04\",\"product\":{\"article\":\"old\",\"nmId\":55,\"chrtId\":66,\"skus\":[\"111\"]},\"priceInfo\":{\"price\":10100,\"convertedPrice\":10100,\"currencyCode\":643,\"convertedCurrencyCode\":643}}],\"next\":0}", WbArchiveOrdersResponse.class);
            }
            @Override public WbOrderStatusesResponse getOrderStatuses(String key, List<Long> ids) {
                return gson.fromJson("{\"orders\":[{\"id\":101,\"supplierStatus\":\"complete\",\"wbStatus\":\"waiting\"}]}", WbOrderStatusesResponse.class);
            }
        };
        var result = service(api).load(shop);
        assertEquals(1, archiveCalls.get());
        var order = result.shipments().getFirst().orders().getFirst();
        assertEquals(101L, order.getId());
        assertEquals("old", order.getArticle());
        assertEquals("2025-05-04", order.getCreatedAt());
        assertEquals("111", order.getBarcode());
    }

    @Test void readsStatusesInBatchesOfAtMostOneThousand() throws Exception {
        var sizes = new java.util.ArrayList<Integer>();
        var api = new WbApiClient() {
            @Override public WbReshipmentResponse getReshipments(String key) {
                StringBuilder b = new StringBuilder("{\"orders\":[");
                for (int i = 1; i <= 1001; i++) { if (i > 1) b.append(','); b.append("{\"supplyID\":\"WB-GI-A\",\"orderID\":").append(i).append('}'); }
                return gson.fromJson(b.append("]}").toString(), WbReshipmentResponse.class);
            }
            @Override public WbOrderStatusesResponse getOrderStatuses(String key, List<Long> ids) {
                sizes.add(ids.size());
                StringBuilder b = new StringBuilder("{\"orders\":[");
                for (int i = 0; i < ids.size(); i++) { if (i > 0) b.append(','); b.append("{\"id\":").append(ids.get(i)).append(",\"supplierStatus\":\"complete\",\"wbStatus\":\"waiting\"}"); }
                return gson.fromJson(b.append("]}").toString(), WbOrderStatusesResponse.class);
            }
        };
        var cache = new WbOrderRepository() {
            @Override public List<com.vncode.app.models.Order> getOrders(int shopId, List<Long> ids) {
                return ids.stream().map(id -> { var row = new com.vncode.app.models.Order(); row.setId(id); row.setName("name"); row.setArticle("article"); row.setSize("L"); row.setImageUrl("https://image.example/p.jpg"); row.setPrice(100); return row; }).toList();
            }
        };
        var result = new WbDebtShipmentService(api, cache, new WbSupplyRepository()).load(shop);
        assertEquals(List.of(1000, 1), sizes);
        assertEquals(1001, result.shipments().getFirst().orders().size());
        assertTrue(result.complete());
    }

    private WbDebtShipmentService service(WbApiClient api) {
        return new WbDebtShipmentService(api, new WbOrderRepository(), new WbSupplyRepository());
    }
}
