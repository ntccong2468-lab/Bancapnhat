package com.tuandev.fbsbarcode.integration.wb;

import com.google.gson.Gson;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WbOrderRepositorySupplyDetailsTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @AfterEach
    void clearAppDataOverride() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void shouldResolveSupplyProductInfoFromSkuWhenOrderMetadataIsMissing() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("INSERT INTO wb_supplies(shop_id, supply_id, synced_at) VALUES (1, 'WB-GI-1', 'now')");
            st.execute("""
                    INSERT INTO wb_product_cards(shop_id, nm_id, vendor_code, subject_name, brand, title, synced_at)
                    VALUES (1, 1001, 'ART-1', 'Shoes', 'Brand A', 'Product A', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_product_sizes(shop_id, chrt_id, nm_id, tech_size, wb_size)
                    VALUES (1, 2001, 1001, 'EU-42', '42')
                    """);
            st.execute("""
                    INSERT INTO wb_product_size_skus(shop_id, chrt_id, sku)
                    VALUES (1, 2001, 'SKU-1')
                    """);
            st.execute("""
                    INSERT INTO wb_product_photos(shop_id, nm_id, photo_index, c246x328_url)
                    VALUES (1, 1001, 0, 'image-url')
                    """);
            st.execute("""
                    INSERT INTO wb_product_characteristics(shop_id, nm_id, characteristic_id, name, value_json)
                    VALUES (1, 1001, 14177449, 'Color', '["Black"]')
                    """);
            st.execute("""
                    INSERT INTO wb_orders(shop_id, order_id, article, created_at, synced_at)
                    VALUES (1, 5001, 'ART-1', '2026-06-04T00:00:00Z', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_order_skus(shop_id, order_id, sku)
                    VALUES (1, 5001, 'SKU-1')
                    """);
            st.execute("""
                    INSERT INTO wb_supply_orders(shop_id, supply_id, order_id)
                    VALUES (1, 'WB-GI-1', 5001)
                    """);
            st.execute("""
                    INSERT INTO wb_order_meta_requirements(shop_id, order_id, meta_key, requirement_type)
                    VALUES (1, 5001, 'sgtin', 'optional')
                    """);
        }

        List<Order> orders = new WbOrderRepository().getOrdersForSupply(1, "WB-GI-1");

        assertEquals(1, orders.size());
        Order order = orders.getFirst();
        assertEquals(1001L, order.getNmId());
        assertEquals("Brand A", order.getBrand());
        assertEquals("Product A", order.getName());
        assertEquals("Shoes", order.getSubjectName());
        assertEquals("EU-42", order.getSize());
        assertEquals("42", order.getRuSize());
        assertEquals("Black", order.getColor());
        assertEquals("ART-1", order.getArticle());
        assertEquals("SKU-1", order.getBarcode());
        assertEquals("image-url", order.getImageUrl());
        assertTrue(order.isRequiresKiz());
    }

    @Test
    void shouldResolveSupplyProductInfoFromArticleWhenSkuIsMissing() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("INSERT INTO wb_supplies(shop_id, supply_id, synced_at) VALUES (1, 'WB-GI-2', 'now')");
            st.execute("""
                    INSERT INTO wb_product_cards(shop_id, nm_id, vendor_code, subject_name, brand, title, synced_at)
                    VALUES (1, 1002, 'ART-2', 'Bags', 'Brand B', 'Product B', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_product_sizes(shop_id, chrt_id, nm_id, tech_size, wb_size)
                    VALUES (1, 2002, 1002, '0', 'ONE SIZE')
                    """);
            st.execute("""
                    INSERT INTO wb_product_size_skus(shop_id, chrt_id, sku)
                    VALUES (1, 2002, 'SKU-2')
                    """);
            st.execute("""
                    INSERT INTO wb_orders(shop_id, order_id, article, created_at, synced_at)
                    VALUES (1, 5002, 'ART-2', '2026-06-04T00:00:00Z', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_supply_orders(shop_id, supply_id, order_id)
                    VALUES (1, 'WB-GI-2', 5002)
                    """);
            st.execute("""
                    INSERT INTO wb_order_meta_requirements(shop_id, order_id, meta_key, requirement_type)
                    VALUES (1, 5002, 'uin', 'required')
                    """);
        }

        List<Order> orders = new WbOrderRepository().getOrdersForSupply(1, "WB-GI-2");

        assertEquals(1, orders.size());
        Order order = orders.getFirst();
        assertEquals(1002L, order.getNmId());
        assertEquals("Brand B", order.getBrand());
        assertEquals("Product B", order.getName());
        assertEquals("Bags", order.getSubjectName());
        assertEquals("0", order.getSize());
        assertEquals("ONE SIZE", order.getRuSize());
        assertEquals("ART-2", order.getArticle());
        assertEquals("SKU-2", order.getBarcode());
        assertFalse(order.isRequiresKiz());
    }

    @Test
    void shouldResolveSupplyProductInfoFromSkuWhenOrderMetadataDoesNotMatchLocalProduct() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("INSERT INTO wb_supplies(shop_id, supply_id, synced_at) VALUES (1, 'WB-GI-3', 'now')");
            st.execute("""
                    INSERT INTO wb_product_cards(shop_id, nm_id, vendor_code, subject_name, brand, title, synced_at)
                    VALUES (1, 1003, 'ART-3', 'Clothes', 'Brand C', 'Product C', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_product_sizes(shop_id, chrt_id, nm_id, tech_size, wb_size)
                    VALUES (1, 2003, 1003, 'M', '44-46')
                    """);
            st.execute("""
                    INSERT INTO wb_product_size_skus(shop_id, chrt_id, sku)
                    VALUES (1, 2003, 'SKU-3')
                    """);
            st.execute("""
                    INSERT INTO wb_orders(shop_id, order_id, nm_id, chrt_id, article, created_at, synced_at)
                    VALUES (1, 5003, 9999, 9999, 'ART-3', '2026-06-04T00:00:00Z', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_order_skus(shop_id, order_id, sku)
                    VALUES (1, 5003, 'SKU-3')
                    """);
            st.execute("""
                    INSERT INTO wb_supply_orders(shop_id, supply_id, order_id)
                    VALUES (1, 'WB-GI-3', 5003)
                    """);
        }

        List<Order> orders = new WbOrderRepository().getOrdersForSupply(1, "WB-GI-3");

        assertEquals(1, orders.size());
        Order order = orders.getFirst();
        assertEquals(1003L, order.getNmId());
        assertEquals("Brand C", order.getBrand());
        assertEquals("Product C", order.getName());
        assertEquals("Clothes", order.getSubjectName());
        assertEquals("M", order.getSize());
        assertEquals("44-46", order.getRuSize());
        assertEquals("ART-3", order.getArticle());
        assertEquals("SKU-3", order.getBarcode());
    }

    @Test
    void shouldLinkOrderToSupplyWhenOrderArrivesBeforeSupplyRow() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
        }

        WbOrderDto order = GSON.fromJson("""
                {
                  "id": 7001,
                  "article": "ART-7",
                  "createdAt": "2026-06-04T00:00:00Z",
                  "options": {"isB2B": true},
                  "supplyId": "WB-GI-LATE"
                }
                """, WbOrderDto.class);
        new WbOrderRepository().saveOrders(1, List.of(order));

        WbSupplyDto supply = GSON.fromJson("""
                {
                  "id": "WB-GI-LATE",
                  "isB2b": true,
                  "done": false,
                  "createdAt": "2026-06-05T00:00:00Z"
                }
                """, WbSupplyDto.class);
        new WbSupplyRepository().saveSupplies(1, List.of(supply));

        List<Long> orderIds = new WbOrderRepository().getOrderIdsForSupply(1, "WB-GI-LATE");

        assertEquals(List.of(7001L), orderIds);
    }

    @Test
    void shouldUseSupplyOrderCountWhenNoLocalOrderLinksExist() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("""
                    INSERT INTO wb_supplies(shop_id, supply_id, done, order_count, created_at, synced_at)
                    VALUES (1, 'WB-GI-COUNT', 0, 3, '2026-06-04T00:00:00Z', 'now')
                    """);
        }

        List<WbSupplySummary> supplies = new WbSupplyRepository().getSupplySummaries(1);

        assertEquals(1, supplies.size());
        assertEquals(3, supplies.getFirst().getItemCount());
    }

    @Test
    void shouldUseLocalSupplyOrderLinksBeforeSupplyOrderCount() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("""
                    INSERT INTO wb_supplies(shop_id, supply_id, done, order_count, created_at, synced_at)
                    VALUES (1, 'WB-GI-LINKS', 0, 3, '2026-06-04T00:00:00Z', 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_orders(shop_id, order_id, synced_at)
                    VALUES (1, 8001, 'now'), (1, 8002, 'now')
                    """);
            st.execute("""
                    INSERT INTO wb_supply_orders(shop_id, supply_id, order_id)
                    VALUES (1, 'WB-GI-LINKS', 8001), (1, 'WB-GI-LINKS', 8002)
                    """);
        }

        List<WbSupplySummary> supplies = new WbSupplyRepository().getSupplySummaries(1);

        assertEquals(1, supplies.size());
        assertEquals(2, supplies.getFirst().getItemCount());
    }

    @Test
    void shouldUseZeroSupplyCountWhenNoOrderCountOrLocalLinksExist() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("""
                    INSERT INTO wb_supplies(shop_id, supply_id, done, created_at, synced_at)
                    VALUES (1, 'WB-GI-EMPTY', 0, '2026-06-04T00:00:00Z', 'now')
                    """);
        }

        List<WbSupplySummary> supplies = new WbSupplyRepository().getSupplySummaries(1);

        assertEquals(1, supplies.size());
        assertEquals(0, supplies.getFirst().getItemCount());
    }

    @Test
    void shouldPageAndCountSuppliesWithDeterministicStatusOrdering() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("""
                    INSERT INTO wb_supplies(shop_id, supply_id, name, done, order_count, created_at, synced_at)
                    VALUES
                      (1, 'WB-OPEN-NEW', 'Alpha new', 0, 2, '2026-07-03T00:00:00Z', 'now'),
                      (1, 'WB-OPEN-OLD', 'Other', 0, 1, '2026-07-01T00:00:00Z', 'now'),
                      (1, 'WB-CLOSED', 'Alpha closed', 1, 4, '2026-07-04T00:00:00Z', 'now')
                    """);
        }

        WbSupplyRepository.SupplyPage first =
                new WbSupplyRepository().findSupplyPage(1, "alpha", null, 1, 0);
        WbSupplyRepository.SupplyPage second =
                new WbSupplyRepository().findSupplyPage(1, "alpha", null, 1, 1);
        WbSupplyRepository.SupplyPage open =
                new WbSupplyRepository().findSupplyPage(1, "", false, 25, 0);

        assertEquals(2, first.totalItems());
        assertEquals(1, first.openItems());
        assertEquals(1, first.closedItems());
        assertEquals(List.of("WB-OPEN-NEW"), first.items().stream().map(WbSupplySummary::getSupplyId).toList());
        assertEquals(List.of("WB-CLOSED"), second.items().stream().map(WbSupplySummary::getSupplyId).toList());
        assertEquals(2, open.totalItems());
        assertTrue(open.items().stream().noneMatch(WbSupplySummary::isDone));
    }

    @Test
    void shouldTreatSupplySearchWildcardsAsLiteralText() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop', 'token')");
            st.execute("""
                    INSERT INTO wb_supplies(shop_id, supply_id, name, done, created_at, synced_at)
                    VALUES
                      (1, 'WB-LITERAL', 'Ready 100%_today', 0, '2026-07-03T00:00:00Z', 'now'),
                      (1, 'WB-WILDCARD', 'Ready 100XXtoday', 0, '2026-07-02T00:00:00Z', 'now')
                    """);
        }

        WbSupplyRepository.SupplyPage result =
                new WbSupplyRepository().findSupplyPage(1, "%_", null, 25, 0);

        assertEquals(1, result.totalItems());
        assertEquals("WB-LITERAL", result.items().getFirst().getSupplyId());
    }

    @Test
    void shouldFindOneSupplyOnlyWithinTheRequestedShop() throws Exception {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'One', 'token'), (2, 'Two', 'token')");
            st.execute("""
                    INSERT INTO wb_supplies(shop_id, supply_id, name, done, order_count, created_at, synced_at)
                    VALUES
                      (1, 'WB-ONE', 'First', 0, 3, '2026-07-03T00:00:00Z', 'now'),
                      (2, 'WB-TWO', 'Second', 1, 4, '2026-07-04T00:00:00Z', 'now')
                    """);
        }

        WbSupplyRepository repository = new WbSupplyRepository();

        assertEquals("First", repository.findSupplySummary(1, "WB-ONE").getName());
        assertEquals(3, repository.findSupplySummary(1, "WB-ONE").getItemCount());
        assertNull(repository.findSupplySummary(1, "WB-TWO"));
    }
}
