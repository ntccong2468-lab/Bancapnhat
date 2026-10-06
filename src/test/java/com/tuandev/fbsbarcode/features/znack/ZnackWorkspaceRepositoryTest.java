package com.tuandev.fbsbarcode.features.znack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.config.Database;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZnackWorkspaceRepositoryTest {
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
    void pagesAndFiltersActiveAndDeletedProductsWithoutTechnicalOrCrossShopRows() throws Exception {
        product(1, GTIN_A, "100% Cotton", "Shoes", "6403", null);
        product(1, GTIN_B, "Boot B", "Shoes", "6403", null);
        product(1, GTIN_C, "Coat C", "Clothes", "6201", "2026-07-18T00:00:00Z");
        product(1, "02900699308808", "Technical", "Shoes", "", null);
        product(2, "04601234567893", "Other shop", "Shoes", "", null);
        ZnackWorkspaceRepository repository = new ZnackWorkspaceRepository();

        assertEquals(List.of("Shoes"), repository.findCategories(1, false));
        assertEquals(List.of("Clothes"), repository.findCategories(1, true));
        assertEquals(List.of(GTIN_A, GTIN_B), repository.findProductsPage(
                        1, "", List.of("Shoes"), false, 10, 0)
                .stream().map(ZnackWorkspaceRepository.ProductSummary::gtin).toList());
        assertEquals(List.of(GTIN_A), repository.findProductsPage(
                        1, "%", List.of(), false, 10, 0)
                .stream().map(ZnackWorkspaceRepository.ProductSummary::gtin).toList());
        ZnackWorkspaceRepository.ProductSummary deleted = repository.findProductsPage(
                1, "coat", List.of(), true, 10, 0).getFirst();
        assertEquals(GTIN_C, deleted.gtin());
        assertTrue(deleted.deleted());
        assertThrows(IllegalArgumentException.class,
                () -> repository.findProductsPage(1, "", List.of(), false, 102, 0));
    }

    @Test
    void visibilityBatchAndAuditAreAtomicAndShopScoped() throws Exception {
        product(1, GTIN_A, "A", "Shoes", "", null);
        product(1, GTIN_B, "B", "Shoes", "", null);
        product(2, GTIN_C, "C", "Shoes", "", null);
        execute("""
                INSERT INTO znack_gtin_mapping_rules(
                    shop_id,gtin,subject_name,gender_value,wildcard_gender,created_at,updated_at)
                VALUES(1,'%s','Shoes','',1,'2026-08-28T00:00:00Z','2026-08-28T00:00:00Z'),
                      (2,'%s','Shoes','',1,'2026-08-28T00:00:00Z','2026-08-28T00:00:00Z')
                """.formatted(GTIN_A, GTIN_C));
        execute("""
                INSERT INTO ozon_product_gtin_mappings(shop_id,sku,gtin,created_at,updated_at)
                VALUES(1,'sku-a','%s','2026-08-28T00:00:00Z','2026-08-28T00:00:00Z')
                """.formatted(GTIN_A));
        execute("""
                INSERT INTO ozon_article_gtin_mappings(
                    shop_id,article_key,article,gtin,created_at,updated_at)
                VALUES(1,'article-a','article-a','%s','2026-08-28T00:00:00Z','2026-08-28T00:00:00Z')
                """.formatted(GTIN_A));
        ZnackWorkspaceRepository repository = new ZnackWorkspaceRepository();

        repository.setProductVisibility(1, "Shop A", List.of(GTIN_A, GTIN_B), true);
        assertTrue(deleted(1, GTIN_A));
        assertTrue(deleted(1, GTIN_B));
        assertEquals(0, count("znack_gtin_mapping_rules", "shop_id=1 AND gtin='" + GTIN_A + "'"));
        assertEquals(0, count("ozon_product_gtin_mappings", "shop_id=1 AND gtin='" + GTIN_A + "'"));
        assertEquals(0, count("ozon_article_gtin_mappings", "shop_id=1 AND gtin='" + GTIN_A + "'"));
        assertEquals(1, count("znack_gtin_mapping_rules", "shop_id=2 AND gtin='" + GTIN_C + "'"));
        assertEquals(2, count("znack_operation_logs", "shop_id=1 AND message='HIDDEN'"));

        assertThrows(ZnackWorkspaceRepository.VisibilityConflictException.class,
                () -> repository.setProductVisibility(1, "Shop A", List.of(GTIN_A, GTIN_C), false));
        assertTrue(deleted(1, GTIN_A));
        assertFalse(deleted(2, GTIN_C));
        assertEquals(0, count("znack_operation_logs", "shop_id=1 AND message='RESTORED'"));

        repository.setProductVisibility(1, "Shop A", List.of(GTIN_A, GTIN_B), false);
        assertFalse(deleted(1, GTIN_A));
        assertFalse(deleted(1, GTIN_B));
        assertEquals(0, count("znack_gtin_mapping_rules", "shop_id=1 AND gtin='" + GTIN_A + "'"));
        assertEquals(2, count("znack_operation_logs", "shop_id=1 AND message='RESTORED'"));
    }

    @Test
    void purgeHiddenProductAtomicallyRemovesOnlyTheSelectedShopsLocalGraph() throws Exception {
        product(1, GTIN_A, "A", "Shoes", "", "2026-07-18T00:00:00Z");
        product(2, GTIN_A, "Other shop A", "Shoes", "", "2026-07-18T00:00:00Z");
        execute("""
                INSERT INTO znack_gtin_mapping_rules(
                    id,shop_id,gtin,subject_name,gender_value,wildcard_gender,created_at,updated_at)
                VALUES(11,1,'%s','Shoes','Male',0,'2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                """.formatted(GTIN_A));
        execute("""
                INSERT INTO ozon_product_gtin_mappings(shop_id,sku,gtin,created_at,updated_at)
                VALUES(1,'sku-a','%s','2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                """.formatted(GTIN_A));
        insertOrderGraph(1, 101, 201, 301, GTIN_A, "COMPLETED");

        new ZnackWorkspaceRepository().purgeHiddenProduct(1, "Shop A", GTIN_A);

        assertEquals(0, count("znack_products", "shop_id=1 AND gtin='" + GTIN_A + "'"));
        assertEquals(0, count("znack_gtin_mapping_rules", "shop_id=1 AND gtin='" + GTIN_A + "'"));
        assertEquals(0, count("ozon_product_gtin_mappings", "shop_id=1 AND gtin='" + GTIN_A + "'"));
        assertEquals(0, count("znack_purchase_pipelines", "shop_id=1 AND gtin='" + GTIN_A + "'"));
        assertEquals(0, count("kiz_orders", "shop_id=1 AND gtin='" + GTIN_A + "'"));
        assertEquals(0, count("znack_documents", "shop_id=1"));
        assertEquals(0, count("kiz_codes", "shop_id=1 AND gtin='" + GTIN_A + "'"));
        assertEquals(1, count("znack_products", "shop_id=2 AND gtin='" + GTIN_A + "'"));
        assertEquals(1, count("znack_operation_logs",
                "shop_id=1 AND action='GTIN_PURGE' AND entity_reference='" + GTIN_A
                        + "' AND message='PURGED'"));
    }

    @Test
    void purgeFailsClosedWhenProductIsNotHiddenOrPurchaseIsActive() throws Exception {
        product(1, GTIN_A, "A", "Shoes", "", null);
        product(1, GTIN_B, "B", "Shoes", "", "2026-07-18T00:00:00Z");
        execute("""
                INSERT INTO znack_purchase_pipelines(
                    id,shop_id,gtin,quantity,stage,created_at,updated_at)
                VALUES(302,1,'%s',1,'ORDER_CREATED','2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                """.formatted(GTIN_B));
        ZnackWorkspaceRepository repository = new ZnackWorkspaceRepository();

        ZnackWorkspaceRepository.PurgeConflictException visible = assertThrows(
                ZnackWorkspaceRepository.PurgeConflictException.class,
                () -> repository.purgeHiddenProduct(1, "Shop A", GTIN_A));
        assertEquals(ZnackWorkspaceRepository.PurgeConflictKind.PRODUCT_CHANGED, visible.kind());
        ZnackWorkspaceRepository.PurgeConflictException active = assertThrows(
                ZnackWorkspaceRepository.PurgeConflictException.class,
                () -> repository.purgeHiddenProduct(1, "Shop A", GTIN_B));
        assertEquals(ZnackWorkspaceRepository.PurgeConflictKind.ACTIVE_PURCHASE, active.kind());

        assertEquals(1, count("znack_products", "shop_id=1 AND gtin='" + GTIN_A + "'"));
        assertEquals(1, count("znack_products", "shop_id=1 AND gtin='" + GTIN_B + "'"));
        assertEquals(1, count("znack_purchase_pipelines", "id=302"));
        assertEquals(0, count("znack_operation_logs", "action='GTIN_PURGE'"));
    }

    @Test
    void purgeFailsClosedWhenAProductKizIsLinkedToOzon() throws Exception {
        product(1, GTIN_C, "C", "Clothes", "", "2026-07-18T00:00:00Z");
        insertOrderGraph(1, 103, 203, 303, GTIN_C, "COMPLETED");
        insertOzonExemplar(1, 203);

        ZnackWorkspaceRepository.PurgeConflictException linked = assertThrows(
                ZnackWorkspaceRepository.PurgeConflictException.class,
                () -> new ZnackWorkspaceRepository().purgeHiddenProduct(1, "Shop A", GTIN_C));

        assertEquals(ZnackWorkspaceRepository.PurgeConflictKind.OZON_KIZ_LINKED, linked.kind());
        assertEquals(1, count("znack_products", "shop_id=1 AND gtin='" + GTIN_C + "'"));
        assertEquals(1, count("kiz_codes", "id=203"));
        assertEquals(1, count("ozon_exemplars", "kiz_id=203"));
        assertEquals(0, count("znack_operation_logs", "action='GTIN_PURGE'"));
    }

    private void product(
            int shopId, String gtin, String name, String category, String tnVed, String deletedAt)
            throws Exception {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO znack_products(
                            shop_id,gtin,product_name,category,tn_ved,cis_type,
                            good_mark_flag,good_turn_flag,readiness_checked_at,deleted_at,synced_at)
                        VALUES(?,?,?,?,?,'UNIT',1,0,'2026-07-18T00:00:00Z',?,'2026-07-18T00:00:00Z')
                        """)) {
            statement.setInt(1, shopId);
            statement.setString(2, gtin);
            statement.setString(3, name);
            statement.setString(4, category);
            statement.setString(5, tnVed);
            statement.setString(6, deletedAt);
            statement.executeUpdate();
        }
    }

    private boolean deleted(int shopId, String gtin) throws Exception {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT deleted_at IS NOT NULL FROM znack_products WHERE shop_id=? AND gtin=?")) {
            statement.setInt(1, shopId);
            statement.setString(2, gtin);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) != 0;
            }
        }
    }

    private void insertOrderGraph(
            int shopId, int orderId, int codeId, int pipelineId, String gtin, String pipelineStage)
            throws Exception {
        execute("""
                INSERT INTO kiz_orders(
                    id,shop_id,external_order_id,gtin,quantity,remote_status,local_status,created_at,updated_at)
                VALUES(%d,%d,'order-%d','%s',1,'READY','READY','2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                """.formatted(orderId, shopId, orderId, gtin));
        execute("""
                INSERT INTO znack_documents(
                    id,shop_id,order_id,document_type,payload_json,status,created_at,updated_at)
                VALUES(%d,%d,%d,'KIZ','{}','READY','2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                """.formatted(orderId, shopId, orderId));
        execute("""
                INSERT INTO kiz_codes(
                    id,shop_id,order_id,raw_code,display_code,gtin,document_id,status,created_at,updated_at)
                VALUES(%d,%d,%d,'raw-%d','display-%d','%s',%d,'AVAILABLE','2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                """.formatted(codeId, shopId, orderId, codeId, codeId, gtin, orderId));
        execute("""
                INSERT INTO znack_purchase_pipelines(
                    id,shop_id,gtin,quantity,order_id,stage,created_at,updated_at)
                VALUES(%d,%d,'%s',1,%d,'%s','2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                """.formatted(pipelineId, shopId, gtin, orderId, pipelineStage));
    }

    private void insertOzonExemplar(int shopId, int codeId) throws Exception {
        execute("""
                INSERT INTO ozon_postings(
                    shop_id,posting_number,status,synced_at)
                VALUES(%d,'posting-1','awaiting_packaging','2026-07-18T00:00:00Z')
                """.formatted(shopId));
        execute("""
                INSERT INTO ozon_posting_items(
                    shop_id,posting_number,item_index,product_id,sku,name,quantity)
                VALUES(%d,'posting-1',0,'product-1','sku-1','Product',1)
                """.formatted(shopId));
        execute("""
                INSERT INTO ozon_exemplar_jobs(
                    id,shop_id,posting_number,stage,created_at,updated_at)
                VALUES(401,%d,'posting-1','ACCEPTED','2026-07-18T00:00:00Z','2026-07-18T00:00:00Z')
                """.formatted(shopId));
        execute("""
                INSERT INTO ozon_exemplars(
                    job_id,shop_id,posting_number,item_index,product_id,exemplar_index,kiz_id,check_status,updated_at)
                VALUES(401,%d,'posting-1',0,'product-1',0,%d,'passed','2026-07-18T00:00:00Z')
                """.formatted(shopId, codeId));
    }

    private int count(String table, String predicate) throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
