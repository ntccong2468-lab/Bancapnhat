package com.tuandev.fbsbarcode.integration.wb;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WbSyncRepositoryTest {
    private static final Gson GSON = new Gson();

    @Test
    void shouldUpsertProductAndOrderDataIntoNormalizedTables() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("CREATE TABLE shops(id INTEGER PRIMARY KEY, name TEXT NOT NULL, api_key TEXT NOT NULL)");
                st.execute("CREATE TABLE categories(id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
                st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop 1', 'token')");
            }
            WbSchemaSupport.initialize(conn);

            WbProductRepository productRepository = new WbProductRepository();
            WbOrderRepository orderRepository = new WbOrderRepository();
            WbProductCard card = GSON.fromJson("""
                    {
                      "nmID": 12345678,
                      "imtID": 123654789,
                      "nmUUID": "uuid-1",
                      "subjectID": 7771,
                      "subjectName": "AKF systems",
                      "vendorCode": "wb7f6mumjr1",
                      "kizMarked": true,
                      "needKiz": false,
                      "brand": "Test",
                      "title": "Product title",
                      "description": "Description",
                      "photos": [{"big":"big","c246x328":"c246","c516x688":"c516","square":"square","tm":"tm"}],
                      "wholesale": {"enabled": true, "quantum": 112},
                      "dimensions": {"length": 55, "width": 40, "height": 15, "weightBrutto": 6.24, "isValid": false},
                      "characteristics": [{"id": 14177449, "name": "Color", "value": ["red"]}],
                      "sizes": [{"chrtID": 316399238, "techSize": "0", "skus": ["987456321654"]}],
                      "tags": [{"id": 592569, "name": "Popular", "color": "D1CFD7"}],
                      "createdAt": "2023-12-06T11:17:00.96577Z",
                      "updatedAt": "2023-12-06T11:17:00.96577Z"
                    }
                    """, WbProductCard.class);
            WbOrderDto order = GSON.fromJson("""
                    {
                      "address": {"fullAddress":"Address 1","longitude":44.519068,"latitude":40.20192},
                      "requiredMeta":["uin"],
                      "optionalMeta":["sgtin"],
                      "deliveryType":"fbs",
                      "comment":"comment",
                      "scanPrice":1500,
                      "userId":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                      "orderUid":"uid-1",
                      "article":"one-ring-7548",
                      "colorCode":"RAL 3017",
                      "rid":"rid-1",
                      "createdAt":"2022-05-04T07:56:29Z",
                      "offices":["Kaluga"],
                      "skus":["6665956397512"],
                      "id":13833711,
                      "warehouseId":658434,
                      "officeId":123,
                      "nmId":12345678,
                      "chrtId":316399238,
                      "price":1014,
                      "finalPrice":1014,
                      "salePrice":504600,
                      "convertedPrice":28322,
                      "convertedFinalPrice":1014,
                      "currencyCode":933,
                      "convertedCurrencyCode":643,
                      "cargoType":1,
                      "crossBorderType":1,
                      "isZeroOrder":false,
                      "options":{"isB2B":true},
                      "supplyId":"WB-GI-92937123"
                    }
                    """, WbOrderDto.class);

            productRepository.saveProductBatch(conn, 1, List.of(card));
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO wb_supplies(shop_id, supply_id, synced_at) VALUES (1, 'WB-GI-92937123', '2026-05-09T00:00:00Z')");
            }
            orderRepository.saveOrders(conn, 1, List.of(order));

            assertEquals(1, count(conn, "SELECT COUNT(*) FROM wb_product_cards"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM wb_product_photos"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM wb_product_sizes"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM wb_product_size_skus"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM wb_orders"));
            assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    scalarString(conn, "SELECT user_id FROM wb_orders WHERE shop_id = 1 AND order_id = 13833711"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM wb_order_meta_requirements WHERE requirement_type = 'required'"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM wb_order_meta_requirements WHERE requirement_type = 'optional'"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM wb_supply_orders"));
        }
    }

    @Test
    void shouldCascadeDeleteProductAndOrderDataWhenShopIsDeleted() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("CREATE TABLE shops(id INTEGER PRIMARY KEY, name TEXT NOT NULL, api_key TEXT NOT NULL)");
                st.execute("CREATE TABLE categories(id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
                st.execute("INSERT INTO shops(id, name, api_key) VALUES (1, 'Shop 1', 'token')");
            }
            WbSchemaSupport.initialize(conn);

            WbProductRepository productRepository = new WbProductRepository();
            WbOrderRepository orderRepository = new WbOrderRepository();
            WbProductCard card = GSON.fromJson("""
                    {
                      "nmID": 12345678,
                      "imtID": 123654789,
                      "nmUUID": "uuid-1",
                      "subjectID": 7771,
                      "subjectName": "AKF systems",
                      "vendorCode": "wb7f6mumjr1",
                      "kizMarked": true,
                      "needKiz": false,
                      "brand": "Test",
                      "title": "Product title",
                      "description": "Description",
                      "photos": [{"big":"big","c246x328":"c246","c516x688":"c516","square":"square","tm":"tm"}],
                      "wholesale": {"enabled": true, "quantum": 112},
                      "dimensions": {"length": 55, "width": 40, "height": 15, "weightBrutto": 6.24, "isValid": false},
                      "characteristics": [{"id": 14177449, "name": "Color", "value": ["red"]}],
                      "sizes": [{"chrtID": 316399238, "techSize": "0", "skus": ["987456321654"]}],
                      "tags": [{"id": 592569, "name": "Popular", "color": "D1CFD7"}],
                      "createdAt": "2023-12-06T11:17:00.96577Z",
                      "updatedAt": "2023-12-06T11:17:00.96577Z"
                    }
                    """, WbProductCard.class);
            WbOrderDto order = GSON.fromJson("""
                    {
                      "address": {"fullAddress":"Address 1","longitude":44.519068,"latitude":40.20192},
                      "requiredMeta":["uin"],
                      "optionalMeta":["sgtin"],
                      "deliveryType":"fbs",
                      "comment":"comment",
                      "scanPrice":1500,
                      "orderUid":"uid-1",
                      "article":"one-ring-7548",
                      "colorCode":"RAL 3017",
                      "rid":"rid-1",
                      "createdAt":"2022-05-04T07:56:29Z",
                      "offices":["Kaluga"],
                      "skus":["6665956397512"],
                      "id":13833711,
                      "warehouseId":658434,
                      "officeId":123,
                      "nmId":12345678,
                      "chrtId":316399238,
                      "price":1014,
                      "finalPrice":1014,
                      "salePrice":504600,
                      "convertedPrice":28322,
                      "convertedFinalPrice":1014,
                      "currencyCode":933,
                      "convertedCurrencyCode":643,
                      "cargoType":1,
                      "crossBorderType":1,
                      "isZeroOrder":false,
                      "options":{"isB2B":true},
                      "supplyId":"WB-GI-92937123"
                    }
                    """, WbOrderDto.class);

            productRepository.saveProductBatch(conn, 1, List.of(card));
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO wb_supplies(shop_id, supply_id, synced_at) VALUES (1, 'WB-GI-92937123', '2026-05-09T00:00:00Z')");
            }
            orderRepository.saveOrders(conn, 1, List.of(order));

            // Verify they exist
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM wb_product_cards"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM wb_product_photos"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM wb_product_sizes"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM wb_product_size_skus"));
            assertEquals(1, count(conn, "SELECT COUNT(*) FROM wb_orders"));

            // Now delete the shop
            try (Statement st = conn.createStatement()) {
                st.execute("DELETE FROM shops WHERE id = 1");
            }

            // Verify they are deleted via cascade
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM wb_product_cards"));
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM wb_product_photos"));
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM wb_product_sizes"));
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM wb_product_size_skus"));
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM wb_orders"));
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM wb_supply_orders"));
        }
    }

    private int count(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private String scalarString(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
