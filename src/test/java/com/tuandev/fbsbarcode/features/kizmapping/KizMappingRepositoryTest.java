package com.tuandev.fbsbarcode.features.kizmapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventorySummary;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KizMappingRepositoryTest {
    private static final String GTIN_A = "04601234567890";
    private static final String GTIN_B = "04601234567891";
    private static final String GTIN_C = "04601234567892";

    @TempDir
    Path appData;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wcode.appdata.dir", appData.toString());
        Database.initDatabase();
        execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Shop A','secret-a')");
        execute("INSERT INTO shops(id,name,api_key) VALUES(2,'Shop B','secret-b')");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void pagesAndFiltersSafeInventoryAggregatesWithoutTechnicalOrDeletedProducts() throws Exception {
        product(1, GTIN_A, "100% Cotton", "Shoes", null);
        product(1, GTIN_B, "Boot B", "Shoes", null);
        product(1, GTIN_C, "Coat C", "Clothes", null);
        product(1, "02900699308808", "Technical", "Shoes", null);
        product(1, "04601234567893", "Deleted", "Shoes", "2026-07-18T00:00:00Z");
        product(2, "04601234567894", "Other shop", "Shoes", null);
        orderAndPipeline();
        codes();
        KizMappingRepository repository = new KizMappingRepository();

        assertEquals(List.of("Clothes", "Shoes"), repository.findGtinCategories(1));
        List<ZnackGtinInventorySummary> shoes = repository.findGtinSummariesPage(
                1, "", List.of("Shoes"), 2, 0);
        assertEquals(List.of(GTIN_A, GTIN_B), shoes.stream().map(ZnackGtinInventorySummary::gtin).toList());
        assertEquals(1, shoes.getFirst().available());
        assertEquals(1, shoes.getFirst().reserved());
        assertEquals(1, shoes.getFirst().consumed());
        assertEquals(1, shoes.getFirst().discardable());
        assertEquals("CODES_READY", shoes.getFirst().latestOrderStatus());
        assertEquals("COMPLETED", shoes.getFirst().latestPipelineStage());
        assertEquals("safe error", shoes.getFirst().latestError());

        List<ZnackGtinInventorySummary> literalPercent = repository.findGtinSummariesPage(
                1, "%", List.of(), 10, 0);
        assertEquals(List.of(GTIN_A), literalPercent.stream().map(ZnackGtinInventorySummary::gtin).toList());
        assertEquals(List.of(GTIN_C), repository.findGtinSummariesPage(
                        1, "coat", List.of(), 1, 0)
                .stream().map(ZnackGtinInventorySummary::gtin).toList());
        assertThrows(IllegalArgumentException.class,
                () -> repository.findGtinSummariesPage(1, "", List.of(), 102, 0));
    }

    @Test
    void conflictingReplacementRollsBackAndKeepsTheExistingOwner() throws Exception {
        product(1, GTIN_A, "A", "Shoes", null);
        product(1, GTIN_B, "B", "Shoes", null);
        card(1, 101, "Jackets", "Female");
        card(1, 102, "Jackets", "Male");
        KizMappingRepository repository = new KizMappingRepository();
        repository.replaceRulesForGtin(
                1, GTIN_A, List.of(new ZnackGtinMappingSelection("Jackets", null, true)));

        assertThrows(IllegalStateException.class, () -> repository.replaceRulesForGtin(
                1, GTIN_B, List.of(new ZnackGtinMappingSelection("Jackets", "Female", false))));

        assertEquals(1, repository.findRulesForGtin(1, GTIN_A).size());
        assertTrue(repository.findRulesForGtin(1, GTIN_B).isEmpty());
        assertEquals(GTIN_A, repository.findMappings(1, List.of(101L, 102L)).get(101L));
        assertTrue(repository.hasGtinProduct(1, GTIN_A));
        assertFalse(repository.hasGtinProduct(2, GTIN_A));
    }

    @Test
    void startupRepairsCategoryMappingLeftByAnOlderVersionForATrashedGtin() throws Exception {
        product(1, GTIN_A, "Old", "Shoes", null);
        product(1, GTIN_B, "New", "Shoes", null);
        card(1, 101, "Shoes", "Female");
        KizMappingRepository repository = new KizMappingRepository();
        repository.replaceRulesForGtin(
                1, GTIN_A, List.of(new ZnackGtinMappingSelection("Shoes", null, true)));
        execute("UPDATE znack_products SET deleted_at='2026-08-28T00:00:00Z' "
                + "WHERE shop_id=1 AND gtin='" + GTIN_A + "'");
        assertEquals(1, mappingRuleCount(GTIN_A));

        Database.initDatabase();

        assertEquals(0, mappingRuleCount(GTIN_A));
        repository.replaceRulesForGtin(
                1, GTIN_B, List.of(new ZnackGtinMappingSelection("Shoes", null, true)));
        assertEquals(GTIN_B, repository.findMappings(1, List.of(101L)).get(101L));
    }

    private void orderAndPipeline() throws Exception {
        execute("""
                INSERT INTO kiz_orders(shop_id,gtin,quantity,local_status,error_message,created_at,updated_at)
                VALUES(1,'%s',4,'CODES_READY','order error','2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                """.formatted(GTIN_A));
        execute("""
                INSERT INTO znack_purchase_pipelines(
                    shop_id,gtin,quantity,stage,order_id,error_message,created_at,updated_at)
                VALUES(1,'%s',4,'INTRODUCTION_FAILED',1,'introduction failed',
                       '2026-07-17T00:00:00Z','2026-07-17T00:00:00Z')
                """.formatted(GTIN_A));
        execute("""
                INSERT INTO znack_purchase_pipelines(shop_id,gtin,quantity,stage,error_message,created_at,updated_at)
                VALUES(1,'%s',4,'COMPLETED','safe error','2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                """.formatted(GTIN_A));
    }

    private void codes() throws Exception {
        try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO kiz_codes(
                    shop_id,order_id,raw_code,display_code,gtin,status,legal_status,created_at,updated_at)
                VALUES(1,1,?,?,?,?,?, '2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                """)) {
            int index = 0;
            for (String[] state : List.of(
                    new String[]{"AVAILABLE", "IN_CIRCULATION"},
                    new String[]{"AVAILABLE", "RECEIVED"},
                    new String[]{"RESERVED", "IN_CIRCULATION"},
                    new String[]{"CONSUMED", "IN_CIRCULATION"})) {
                String status = state[0];
                String code = "code-" + status + "-" + index++;
                statement.setString(1, code);
                statement.setString(2, code);
                statement.setString(3, GTIN_A);
                statement.setString(4, status);
                statement.setString(5, state[1]);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void product(int shopId, String gtin, String name, String category, String deletedAt)
            throws Exception {
        try (Connection connection = Database.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO znack_products(shop_id,gtin,product_name,category,deleted_at,synced_at)
                VALUES(?,?,?,?,?,'2026-07-18T00:00:00Z')
                """)) {
            statement.setInt(1, shopId);
            statement.setString(2, gtin);
            statement.setString(3, name);
            statement.setString(4, category);
            statement.setString(5, deletedAt);
            statement.executeUpdate();
        }
    }

    private void card(int shopId, long nmId, String subject, String gender) throws Exception {
        execute("""
                INSERT INTO wb_product_cards(shop_id,nm_id,subject_name,need_kiz,synced_at)
                VALUES(%d,%d,'%s',1,'2026-07-18T00:00:00Z')
                """.formatted(shopId, nmId, subject));
        execute("""
                INSERT INTO wb_product_characteristics(
                    shop_id,nm_id,characteristic_id,name,value_json)
                VALUES(%d,%d,%d,'Gender','["%s"]')
                """.formatted(shopId, nmId, KizMappingRepository.GENDER_CHARACTERISTIC_ID, gender));
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int mappingRuleCount(String gtin) throws Exception {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM znack_gtin_mapping_rules WHERE shop_id=1 AND gtin=?")) {
            statement.setString(1, gtin);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }
}
