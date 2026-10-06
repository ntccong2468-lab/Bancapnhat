package com.tuandev.fbsbarcode.features.fbosupply;

import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FboSupplyRepositoryTest {
    @TempDir
    Path appDataDir;
    private FboSupplyRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", appDataDir.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'WB','WILDBERRIES',NULL,'x')");
            statement.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(2,'Ozon','OZON','c','x')");
        }
        repository = new FboSupplyRepository();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void storesWbStatusAndGoodsWithoutPhoneOrRawJson() throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at)
                    VALUES(1,123,'ART-1','WB product','2026-08-01T00:00:00Z')
                    """);
            statement.execute("""
                    INSERT INTO wb_product_photos(shop_id,nm_id,photo_index,big_url)
                    VALUES(1,123,1,'https://example.test/wb-big.webp')
                    """);
        }
        repository.upsertWbSummaries(1, JsonParser.parseString("""
                [{"phone":"+7 secret","preorderID":77,"supplyID":88,"statusID":2,"boxTypeID":5,
                  "createDate":"2026-08-01T10:00:00+03:00","supplyDate":"2026-08-20T00:00:00+03:00"}]
                """).getAsJsonArray());
        repository.upsertWbDetail(1, "77", JsonParser.parseString("""
                {"statusID":5,"warehouseName":"Коледино","quantity":4,"acceptedQuantity":3,
                 "updatedDate":"2026-08-21T12:00:00+03:00"}
                """).getAsJsonObject(), JsonParser.parseString("""
                [{"barcode":"4601","vendorCode":"ART-1","nmID":123,"needKiz":true,"techSize":"M",
                  "color":"black","quantity":4,"acceptedQuantity":3}]
                """).getAsJsonArray());

        Shop shop = new Shop(1, "WB", Marketplace.WILDBERRIES, null, "x");
        assertEquals(1, repository.findOrders(shop).size());
        assertEquals(FboSupplyStatusGroup.COMPLETED, repository.findOrders(shop).getFirst().statusGroup());
        assertEquals(1, repository.findItems(shop, "77").size());
        assertEquals("ART-1", repository.findItems(shop, "77").getFirst().article());
        assertEquals("https://example.test/wb-big.webp", repository.findItems(shop, "77").getFirst().imageUrl());

        try (Connection connection = Database.getConnection()) {
            assertFalse(hasColumn(connection, "wb_fbw_orders", "phone"));
            assertFalse(hasColumn(connection, "wb_fbw_orders", "raw_json"));
        }
    }

    @Test
    void storesOzonV3OrdersSuppliesAndBundleItems() throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO ozon_products(
                        shop_id,product_id,offer_id,sku,name,primary_image_url,article,color,size,archived,synced_at)
                    VALUES(2,'301','OFFER-1','300','Catalog product','https://example.test/catalog.png',
                           'ARTICLE-1','Black','M',0,'2026-08-01T00:00:00Z')
                    """);
        }
        repository.upsertOzonDetails(2, JsonParser.parseString("""
                {"orders":[{"order_id":101,"order_number":"FBO-101","state":"READY_TO_SUPPLY",
                  "created_date":"2026-08-01T10:00:00Z","state_updated_date":"2026-08-02T10:00:00Z",
                  "drop_off_warehouse":{"warehouse_id":9,"name":"Drop-off"},
                  "timeslot":{"timeslot":{"from":"2026-08-25T09:00:00Z","to":"2026-08-25T10:00:00Z"}},
                  "supplies":[{"supply_id":202,"bundle_id":"bundle-202","state":"READY_TO_SUPPLY",
                    "storage_warehouse":{"warehouse_id":10,"name":"Storage"}}]}]}
                """).getAsJsonObject());
        repository.replaceOzonItems(2, "101", "202", JsonParser.parseString("""
                [{"sku":300,"product_id":301,"offer_id":"OFFER-1","barcode":"4602","name":"Product",
                  "icon_path":"https://example.test/image.png","quantity":6}]
                """).getAsJsonArray());

        Shop shop = new Shop(2, "Ozon", Marketplace.OZON, "c", "x");
        assertEquals(1, repository.findOrders(shop).size());
        assertEquals("Storage", repository.findOrders(shop).getFirst().warehouseName());
        assertEquals(6, repository.findOrders(shop).getFirst().quantity());
        assertEquals("ARTICLE-1", repository.findItems(shop, "101").getFirst().article());
        assertEquals("M", repository.findItems(shop, "101").getFirst().size());
        assertEquals("Black", repository.findItems(shop, "101").getFirst().color());
        assertEquals("https://example.test/catalog.png", repository.findItems(shop, "101").getFirst().imageUrl());
        assertEquals(1, repository.findOzonSupplyRefs(2, "101").size());
        assertEquals("bundle-202", repository.findOzonSupplyRefs(2, "101").getFirst().bundleId());
    }

    @Test
    void deletingShopCascadesNewTrackingData() throws Exception {
        repository.upsertWbSummaries(1, JsonParser.parseString(
                "[{\"preorderID\":77,\"statusID\":1}]").getAsJsonArray());
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM shops WHERE id=1");
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM wb_fbw_orders")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }

    @Test
    void deletingOzonShopCascadesOrdersSuppliesAndItems() throws Exception {
        repository.upsertOzonDetails(2, JsonParser.parseString("""
                {"orders":[{"order_id":101,"state":"READY_TO_SUPPLY",
                  "supplies":[{"supply_id":202,"bundle_id":"bundle-202"}]}]}
                """).getAsJsonObject());
        repository.replaceOzonItems(2, "101", "202", JsonParser.parseString(
                "[{\"sku\":300,\"product_id\":301,\"quantity\":2}]").getAsJsonArray());

        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM shops WHERE id=2");
            for (String table : new String[]{"ozon_fbo_orders", "ozon_fbo_supplies", "ozon_fbo_supply_items"}) {
                try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1), table);
                }
            }
        }
    }

    @Test
    void persistsOzonBackfillCursorAcrossRestarts() {
        repository.saveOzonCursor(2, "next-page-token");
        assertEquals("next-page-token", repository.findOzonCursor(2));
        repository.markSyncSuccess(2, Marketplace.OZON, "later-page-token");
        assertEquals("later-page-token", repository.findOzonCursor(2));
        assertTrue(repository.findLastSyncedAt(2, Marketplace.OZON) != null);
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) return true;
            }
            return false;
        }
    }
}
