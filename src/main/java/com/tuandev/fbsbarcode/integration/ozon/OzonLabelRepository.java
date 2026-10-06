package com.tuandev.fbsbarcode.integration.ozon;

import com.tuandev.fbsbarcode.config.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

public final class OzonLabelRepository {
    public LabelJob findOrCreate(int shopId, String postingNumber) {
        String now = Instant.now().toString();
        try (Connection connection = Database.getConnection();
                PreparedStatement insert = connection.prepareStatement("""
                        INSERT OR IGNORE INTO ozon_label_jobs(shop_id,posting_number,status,created_at,updated_at)
                        VALUES(?,?,'CREATED',?,?)
                        """)) {
            insert.setInt(1, shopId);
            insert.setString(2, OzonApiClient.requireExternalId(postingNumber, "posting number"));
            insert.setString(3, now);
            insert.setString(4, now);
            insert.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
        return find(shopId, postingNumber);
    }

    public LabelJob update(int shopId, String postingNumber, String taskId, String status, String path, String error) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE ozon_label_jobs SET task_id=COALESCE(?,task_id),status=?,output_path=?,
                            safe_error_code=?,updated_at=? WHERE shop_id=? AND posting_number=?
                        """)) {
            statement.setString(1, taskId);
            statement.setString(2, safeStatus(status));
            statement.setString(3, path);
            statement.setString(4, error == null ? null : safeStatus(error));
            statement.setString(5, Instant.now().toString());
            statement.setInt(6, shopId);
            statement.setString(7, postingNumber);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Ozon label job disappeared.");
            return find(shopId, postingNumber);
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public LabelJob find(int shopId, String postingNumber) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT id,shop_id,posting_number,task_id,status,output_path,safe_error_code,created_at,updated_at
                        FROM ozon_label_jobs WHERE shop_id=? AND posting_number=?
                        """)) {
            statement.setInt(1, shopId);
            statement.setString(2, postingNumber);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new LabelJob(
                        result.getLong(1), result.getInt(2), result.getString(3), result.getString(4),
                        result.getString(5), result.getString(6), result.getString(7), result.getString(8), result.getString(9)) : null;
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static String safeStatus(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_:-]{0,63}") ? value : "internal";
    }

    public record LabelJob(
            long id, int shopId, String postingNumber, String taskId, String status, String outputPath,
            String safeErrorCode, String createdAt, String updatedAt) {
    }
}
