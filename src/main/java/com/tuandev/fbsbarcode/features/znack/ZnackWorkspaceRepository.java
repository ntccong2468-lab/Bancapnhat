package com.tuandev.fbsbarcode.features.znack;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.znack.GtinNormalizer;
import com.tuandev.fbsbarcode.integration.znack.ZnackMappingLifecycle;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Bounded local queries and atomic reversible product visibility changes for the Znack workspace. */
public final class ZnackWorkspaceRepository {
    private static final int MAX_CATEGORIES = 100;
    private static final int MAX_BATCH = 100;

    public List<String> findCategories(int shopId, boolean deleted) {
        String sql = """
                SELECT DISTINCT TRIM(category)
                FROM znack_products
                WHERE shop_id=? AND gtin NOT LIKE '029%%'
                  AND identity_archived_at IS NULL
                  AND %s
                  AND category IS NOT NULL AND TRIM(category)<>''
                ORDER BY TRIM(category) COLLATE NOCASE
                LIMIT ?
                """.formatted(deleted ? "deleted_at IS NOT NULL" : "deleted_at IS NULL");
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shopId);
            statement.setInt(2, MAX_CATEGORIES);
            try (ResultSet result = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (result.next()) values.add(result.getString(1));
                return values;
            }
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
    }

    public List<ProductSummary> findProductsPage(
            int shopId,
            String query,
            List<String> categories,
            boolean deleted,
            int limit,
            int offset) {
        if (shopId <= 0 || query == null || categories == null || categories.size() > 30
                || limit < 1 || limit > 101 || offset < 0 || offset > 10_000_000) {
            throw new IllegalArgumentException("Invalid Znack product page.");
        }
        String categoryClause = categories.isEmpty()
                ? ""
                : " AND TRIM(category) IN (" + String.join(",", Collections.nCopies(categories.size(), "?")) + ")";
        String sql = """
                SELECT gtin,product_name,category,tn_ved,cis_type,good_mark_flag,good_turn_flag,
                       readiness_checked_at,deleted_at
                FROM znack_products
                WHERE shop_id=? AND gtin NOT LIKE '029%%' AND identity_archived_at IS NULL AND %s
                  AND (?='' OR LOWER(gtin) LIKE ? ESCAPE '\\'
                       OR LOWER(COALESCE(product_name,'')) LIKE ? ESCAPE '\\'
                       OR LOWER(COALESCE(category,'')) LIKE ? ESCAPE '\\'
                       OR LOWER(COALESCE(tn_ved,'')) LIKE ? ESCAPE '\\')
                %s
                ORDER BY CASE WHEN category IS NULL OR TRIM(category)='' THEN 1 ELSE 0 END,
                         TRIM(category) COLLATE NOCASE,gtin
                LIMIT ? OFFSET ?
                """.formatted(deleted ? "deleted_at IS NOT NULL" : "deleted_at IS NULL", categoryClause);
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setInt(parameter++, shopId);
            String normalizedQuery = query.trim().toLowerCase(java.util.Locale.ROOT);
            String pattern = "%" + escapeLike(normalizedQuery) + "%";
            statement.setString(parameter++, normalizedQuery);
            statement.setString(parameter++, pattern);
            statement.setString(parameter++, pattern);
            statement.setString(parameter++, pattern);
            statement.setString(parameter++, pattern);
            for (String category : categories) statement.setString(parameter++, category.trim());
            statement.setInt(parameter++, limit);
            statement.setInt(parameter, offset);
            try (ResultSet result = statement.executeQuery()) {
                List<ProductSummary> products = new ArrayList<>();
                while (result.next()) products.add(product(result));
                return products;
            }
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
    }

    public void setProductVisibility(
            int shopId, String shopName, List<String> gtins, boolean deleted) {
        if (shopId <= 0 || shopName == null || gtins == null || gtins.isEmpty() || gtins.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Invalid Znack visibility batch.");
        }
        List<String> normalized = gtins.stream().map(GtinNormalizer::requireProductionOrderable).toList();
        if (new LinkedHashSet<>(normalized).size() != normalized.size()) {
            throw new IllegalArgumentException("Duplicate Znack visibility target.");
        }
        try (Connection connection = Database.getConnection(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                String expectedState = deleted ? "deleted_at IS NULL" : "deleted_at IS NOT NULL";
                String updateSql = "UPDATE znack_products SET deleted_at=? WHERE shop_id=? AND gtin=? AND " + expectedState;
                String logSql = """
                        INSERT INTO znack_operation_logs
                        (shop_id,shop_name,action,entity_reference,severity,message,http_status,created_at)
                        VALUES(?,?,?,?,?,?,NULL,?)
                        """;
                String now = Instant.now().toString();
                try (PreparedStatement update = connection.prepareStatement(updateSql);
                        PreparedStatement log = connection.prepareStatement(logSql)) {
                    int changed = 0;
                    for (String gtin : normalized) {
                        update.setString(1, deleted ? now : null);
                        update.setInt(2, shopId);
                        update.setString(3, gtin);
                        changed += update.executeUpdate();
                    }
                    if (changed != normalized.size()) throw new VisibilityConflictException();
                    if (deleted) ZnackMappingLifecycle.removeForGtins(connection, shopId, normalized);
                    for (String gtin : normalized) {
                        log.setInt(1, shopId);
                        log.setString(2, shopName);
                        log.setString(3, deleted ? "GTIN_DELETE" : "GTIN_RESTORE");
                        log.setString(4, gtin);
                        log.setString(5, "INFO");
                        log.setString(6, deleted ? "HIDDEN" : "RESTORED");
                        log.setString(7, now);
                        log.addBatch();
                    }
                    log.executeBatch();
                }
                transaction.execute("COMMIT");
            } catch (Exception error) {
                transaction.execute("ROLLBACK");
                throw error;
            }
        } catch (VisibilityConflictException error) {
            throw error;
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
    }

    /**
     * Permanently removes one already-hidden product and its local Znack graph.
     *
     * <p>The operation deliberately fails closed while a purchase pipeline is active or any matching
     * KIZ is referenced by an Ozon exemplar. Callers must serialize this transaction with shop-level
     * background work as an additional guard against starting new work concurrently.</p>
     */
    public void purgeHiddenProduct(int shopId, String shopName, String gtin) {
        if (shopId <= 0 || shopName == null || shopName.isBlank()) {
            throw new IllegalArgumentException("Invalid Znack purge target.");
        }
        String normalized = GtinNormalizer.requireProductionOrderable(gtin);
        try (Connection connection = Database.getConnection(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                requireHiddenProduct(connection, shopId, normalized);
                if (hasActivePurchase(connection, shopId, normalized)) {
                    throw new PurgeConflictException(PurgeConflictKind.ACTIVE_PURCHASE);
                }
                if (hasOzonKizLink(connection, shopId, normalized)) {
                    throw new PurgeConflictException(PurgeConflictKind.OZON_KIZ_LINKED);
                }
                deleteProductGraph(connection, shopId, normalized);
                insertPurgeLog(connection, shopId, shopName, normalized);
                transaction.execute("COMMIT");
            } catch (Exception error) {
                transaction.execute("ROLLBACK");
                throw error;
            }
        } catch (PurgeConflictException error) {
            throw error;
        } catch (SQLException error) {
            throw new RuntimeException(error);
        }
    }

    private static void requireHiddenProduct(Connection connection, int shopId, String gtin)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT deleted_at IS NOT NULL FROM znack_products WHERE shop_id=? AND gtin=?")) {
            statement.setInt(1, shopId);
            statement.setString(2, gtin);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) == 0) {
                    throw new PurgeConflictException(PurgeConflictKind.PRODUCT_CHANGED);
                }
            }
        }
    }

    private static boolean hasActivePurchase(Connection connection, int shopId, String gtin)
            throws SQLException {
        String sql = """
                SELECT 1 FROM znack_purchase_pipelines
                WHERE shop_id=? AND gtin=?
                  AND stage NOT IN ('COMPLETED','INTRODUCED','FAILED','INTRODUCTION_FAILED',
                                    'INTRODUCTION_SKIPPED_MISSING_DOCUMENTS',
                                    'INTRODUCTION_SKIPPED_MISSING_METADATA')
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shopId);
            statement.setString(2, gtin);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean hasOzonKizLink(Connection connection, int shopId, String gtin)
            throws SQLException {
        String sql = """
                SELECT 1
                FROM ozon_exemplars exemplar
                JOIN kiz_codes code ON code.id=exemplar.kiz_id AND code.shop_id=exemplar.shop_id
                WHERE exemplar.shop_id=?
                  AND (code.gtin=? OR code.order_id IN (
                      SELECT id FROM kiz_orders WHERE shop_id=? AND gtin=?))
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shopId);
            statement.setString(2, gtin);
            statement.setInt(3, shopId);
            statement.setString(4, gtin);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void deleteProductGraph(Connection connection, int shopId, String gtin)
            throws SQLException {
        try (PreparedStatement codesByOrder = connection.prepareStatement(
                        "DELETE FROM kiz_codes WHERE shop_id=? AND order_id IN "
                                + "(SELECT id FROM kiz_orders WHERE shop_id=? AND gtin=?)");
                PreparedStatement codesByGtin = connection.prepareStatement(
                        "DELETE FROM kiz_codes WHERE shop_id=? AND gtin=?");
                PreparedStatement documents = connection.prepareStatement(
                        "DELETE FROM znack_documents WHERE shop_id=? AND order_id IN "
                                + "(SELECT id FROM kiz_orders WHERE shop_id=? AND gtin=?)");
                PreparedStatement pipelines = connection.prepareStatement(
                        "DELETE FROM znack_purchase_pipelines WHERE shop_id=? AND gtin=?");
                PreparedStatement orders = connection.prepareStatement(
                        "DELETE FROM kiz_orders WHERE shop_id=? AND gtin=?");
                PreparedStatement product = connection.prepareStatement(
                        "DELETE FROM znack_products WHERE shop_id=? AND gtin=? AND deleted_at IS NOT NULL")) {
            codesByOrder.setInt(1, shopId);
            codesByOrder.setInt(2, shopId);
            codesByOrder.setString(3, gtin);
            codesByOrder.executeUpdate();
            codesByGtin.setInt(1, shopId);
            codesByGtin.setString(2, gtin);
            codesByGtin.executeUpdate();
            documents.setInt(1, shopId);
            documents.setInt(2, shopId);
            documents.setString(3, gtin);
            documents.executeUpdate();
            pipelines.setInt(1, shopId);
            pipelines.setString(2, gtin);
            pipelines.executeUpdate();
            orders.setInt(1, shopId);
            orders.setString(2, gtin);
            orders.executeUpdate();
            product.setInt(1, shopId);
            product.setString(2, gtin);
            if (product.executeUpdate() != 1) {
                throw new PurgeConflictException(PurgeConflictKind.PRODUCT_CHANGED);
            }
        }
    }

    private static void insertPurgeLog(Connection connection, int shopId, String shopName, String gtin)
            throws SQLException {
        try (PreparedStatement log = connection.prepareStatement("""
                INSERT INTO znack_operation_logs
                (shop_id,shop_name,action,entity_reference,severity,message,http_status,created_at)
                VALUES(?,?, 'GTIN_PURGE', ?, 'INFO', 'PURGED', NULL, ?)
                """)) {
            log.setInt(1, shopId);
            log.setString(2, shopName);
            log.setString(3, gtin);
            log.setString(4, Instant.now().toString());
            log.executeUpdate();
        }
    }

    private static ProductSummary product(ResultSet result) throws SQLException {
        return new ProductSummary(
                result.getString("gtin"),
                result.getString("product_name"),
                result.getString("category"),
                result.getString("tn_ved"),
                result.getString("cis_type"),
                nullableBoolean(result, "good_mark_flag"),
                nullableBoolean(result, "good_turn_flag"),
                instant(result.getString("readiness_checked_at")),
                result.getString("deleted_at") != null);
    }

    private static Boolean nullableBoolean(ResultSet result, String column) throws SQLException {
        boolean value = result.getInt(column) != 0;
        return result.wasNull() ? null : value;
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public record ProductSummary(
            String gtin,
            String productName,
            String category,
            String tnVed,
            String cisType,
            Boolean goodMark,
            Boolean goodTurn,
            Instant readinessCheckedAt,
            boolean deleted) {
    }

    public static final class VisibilityConflictException extends RuntimeException {
        public VisibilityConflictException() {
            super("Znack product visibility changed concurrently.");
        }
    }

    public enum PurgeConflictKind {
        PRODUCT_CHANGED,
        ACTIVE_PURCHASE,
        OZON_KIZ_LINKED
    }

    public static final class PurgeConflictException extends RuntimeException {
        private final PurgeConflictKind kind;

        public PurgeConflictException(PurgeConflictKind kind) {
            super("Znack product cannot be purged: " + kind.name().toLowerCase(java.util.Locale.ROOT));
            this.kind = kind;
        }

        public PurgeConflictKind kind() {
            return kind;
        }
    }
}
