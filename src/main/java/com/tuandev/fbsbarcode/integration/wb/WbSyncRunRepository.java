package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

public class WbSyncRunRepository {
    public long startSyncRun(int shopId, String resourceType) {
        String sql = """
                INSERT INTO wb_sync_runs (shop_id, resource_type, started_at)
                VALUES (?, ?, ?)
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, shopId);
            ps.setString(2, resourceType);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void finishSyncRun(long runId, boolean success, int itemsRead, int itemsWritten, String errorCode, String errorMessage) {
        String sql = """
                UPDATE wb_sync_runs
                SET finished_at = ?, success = ?, items_read = ?, items_written = ?, error_code = ?, error_message = ?
                WHERE id = ?
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setInt(2, success ? 1 : 0);
            ps.setInt(3, itemsRead);
            ps.setInt(4, itemsWritten);
            ps.setString(5, errorCode);
            ps.setString(6, errorMessage);
            ps.setLong(7, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
