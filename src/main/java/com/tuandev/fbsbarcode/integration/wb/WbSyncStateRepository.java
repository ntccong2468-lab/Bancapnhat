package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.SqlConsumer;
import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.getLong;
import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.setNullableLong;

public class WbSyncStateRepository {
    public WbShopSyncState getShopSyncState(int shopId) {
        String sql = """
                SELECT wb_products_cursor_updated_at, wb_products_cursor_nm_id, wb_products_last_synced_at,
                       wb_supplies_next, wb_supplies_last_synced_at,
                       wb_orders_next, wb_orders_last_synced_at, wb_orders_window_from, wb_orders_window_to,
                       wb_last_sync_error
                FROM shops WHERE id = ?
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                throw new IllegalArgumentException("Không tìm thấy shop " + shopId);
            }
            return new WbShopSyncState(
                    rs.getString("wb_products_cursor_updated_at"),
                    getLong(rs, "wb_products_cursor_nm_id"),
                    rs.getString("wb_products_last_synced_at"),
                    rs.getLong("wb_supplies_next"),
                    rs.getString("wb_supplies_last_synced_at"),
                    rs.getLong("wb_orders_next"),
                    rs.getString("wb_orders_last_synced_at"),
                    getLong(rs, "wb_orders_window_from"),
                    getLong(rs, "wb_orders_window_to"),
                    rs.getString("wb_last_sync_error")
            );
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void updateProductsCursor(int shopId, String updatedAt, Long nmId, String syncedAt) {
        updateShopSyncFields(shopId,
                "UPDATE shops SET wb_products_cursor_updated_at = ?, wb_products_cursor_nm_id = ?, wb_products_last_synced_at = ?, wb_last_sync_error = NULL WHERE id = ?",
                ps -> {
                    ps.setString(1, updatedAt);
                    setNullableLong(ps, 2, nmId);
                    ps.setString(3, syncedAt);
                    ps.setInt(4, shopId);
                });
    }

    public void updateSuppliesCursor(int shopId, long next, String syncedAt) {
        updateShopSyncFields(shopId,
                "UPDATE shops SET wb_supplies_next = ?, wb_supplies_last_synced_at = ?, wb_last_sync_error = NULL WHERE id = ?",
                ps -> {
                    ps.setLong(1, next);
                    ps.setString(2, syncedAt);
                    ps.setInt(3, shopId);
                });
    }

    public void updateOrdersCursor(int shopId, long next, Long windowFrom, Long windowTo, String syncedAt) {
        updateShopSyncFields(shopId,
                "UPDATE shops SET wb_orders_next = ?, wb_orders_window_from = ?, wb_orders_window_to = ?, wb_orders_last_synced_at = ?, wb_last_sync_error = NULL WHERE id = ?",
                ps -> {
                    ps.setLong(1, next);
                    setNullableLong(ps, 2, windowFrom);
                    setNullableLong(ps, 3, windowTo);
                    ps.setString(4, syncedAt);
                    ps.setInt(5, shopId);
                });
    }

    public void saveSyncError(int shopId, String message) {
        updateShopSyncFields(shopId, "UPDATE shops SET wb_last_sync_error = ? WHERE id = ?",
                ps -> {
                    ps.setString(1, message);
                    ps.setInt(2, shopId);
                });
    }

    private void updateShopSyncFields(int shopId, String sql, SqlConsumer<PreparedStatement> consumer) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            consumer.accept(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
