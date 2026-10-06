package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

public class WbActionLogRepository {
    public void record(int shopId,
                       String actionType,
                       String supplyId,
                       List<Long> orderIds,
                       String status,
                       String requestJson,
                       String responseJson,
                       String errorMessage) {
        String sql = """
                INSERT INTO wb_action_log(
                    shop_id, action_type, supply_id, order_ids, status, request_json, response_json, error_message, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setString(2, actionType);
            ps.setString(3, supplyId);
            ps.setString(4, orderIds == null ? null : orderIds.toString());
            ps.setString(5, status);
            ps.setString(6, requestJson);
            ps.setString(7, responseJson);
            ps.setString(8, errorMessage);
            ps.setString(9, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
