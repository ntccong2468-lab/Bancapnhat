package com.tuandev.fbsbarcode.integration.znack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/** Keeps marketplace mappings from outliving the active Znack GTIN that owns them. */
public final class ZnackMappingLifecycle {
    private static final List<String> MAPPING_TABLES = List.of(
            "znack_gtin_mapping_rules",
            "ozon_product_gtin_mappings",
            "ozon_article_gtin_mappings");

    private ZnackMappingLifecycle() {
    }

    /** Caller owns the transaction so product visibility and mapping removal commit atomically. */
    public static int removeForGtins(Connection connection, int shopId, List<String> gtins)
            throws SQLException {
        int removed = 0;
        for (String table : MAPPING_TABLES) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE shop_id=? AND gtin=?")) {
                for (String gtin : gtins) {
                    statement.setInt(1, shopId);
                    statement.setString(2, gtin);
                    statement.addBatch();
                }
                for (int count : statement.executeBatch()) {
                    if (count > 0) removed += count;
                }
            }
        }
        return removed;
    }

    /** Repairs stale rows created by versions that only soft-deleted the Znack product. */
    public static int removeInactiveMappings(Connection connection) throws SQLException {
        boolean ownsTransaction = connection.getAutoCommit();
        if (ownsTransaction) connection.setAutoCommit(false);
        try {
            int removed = 0;
            for (String table : MAPPING_TABLES) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM %s AS mapping
                        WHERE NOT EXISTS (
                          SELECT 1 FROM znack_products AS product
                          WHERE product.shop_id=mapping.shop_id AND product.gtin=mapping.gtin
                            AND product.deleted_at IS NULL
                            AND product.identity_archived_at IS NULL
                            AND product.gtin NOT LIKE '029%%'
                        )
                        """.formatted(table))) {
                    removed += statement.executeUpdate();
                }
            }
            if (ownsTransaction) connection.commit();
            return removed;
        } catch (SQLException error) {
            if (ownsTransaction) connection.rollback();
            throw error;
        } finally {
            if (ownsTransaction) connection.setAutoCommit(true);
        }
    }
}
