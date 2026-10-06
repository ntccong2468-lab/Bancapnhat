package com.tuandev.fbsbarcode.features.dashboard;

import com.tuandev.fbsbarcode.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class DashboardRepository {
    public DashboardKpis loadKpis(int shopId) {
        try (Connection conn = Database.getConnection()) {
            return new DashboardKpis(
                    count(conn, "SELECT COUNT(*) FROM wb_product_cards WHERE shop_id = ?", shopId),
                    count(conn, """
                            SELECT COUNT(*)
                            FROM wb_orders
                            WHERE shop_id = ?
                              AND supplier_status = 'new'
                              AND (supply_id IS NULL OR TRIM(supply_id) = '')
                            """, shopId),
                    count(conn, "SELECT COUNT(*) FROM wb_supplies WHERE shop_id = ? AND COALESCE(done, 0) = 0", shopId)
            );
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private long count(Connection conn, String sql, int shopId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }
}
