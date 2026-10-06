package com.tuandev.fbsbarcode.integration.ozon;

import com.tuandev.fbsbarcode.config.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

public final class OzonSyncStateRepository {
    public OzonSyncState find(int shopId) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT shop_id,products_last_id,products_last_synced_at,postings_changed_since,
                               postings_last_synced_at,last_error
                        FROM ozon_sync_state WHERE shop_id=?
                        """)) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return OzonSyncState.empty(shopId);
                return new OzonSyncState(
                        result.getInt(1), safe(result.getString(2)), safe(result.getString(3)),
                        safe(result.getString(4)), safe(result.getString(5)), safe(result.getString(6)));
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public void advanceProducts(Connection connection, int shopId, String lastId) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ozon_sync_state(shop_id,products_last_id,products_last_synced_at,last_error)
                VALUES(?,?,?,NULL)
                ON CONFLICT(shop_id) DO UPDATE SET products_last_id=excluded.products_last_id,
                    products_last_synced_at=excluded.products_last_synced_at,last_error=NULL
                """)) {
            statement.setInt(1, shopId);
            statement.setString(2, safe(lastId));
            statement.setString(3, now);
            statement.executeUpdate();
        }
    }

    public void advancePostings(int shopId, String changedSince) {
        String now = Instant.now().toString();
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ozon_sync_state(shop_id,postings_changed_since,postings_last_synced_at,last_error)
                        VALUES(?,?,?,NULL)
                        ON CONFLICT(shop_id) DO UPDATE SET postings_changed_since=excluded.postings_changed_since,
                            postings_last_synced_at=excluded.postings_last_synced_at,last_error=NULL
                        """)) {
            statement.setInt(1, shopId);
            statement.setString(2, safe(changedSince));
            statement.setString(3, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public void recordSafeError(int shopId, String errorKind) {
        String safeError = errorKind != null && errorKind.matches("[a-z][a-z0-9_]{0,31}") ? errorKind : "internal";
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ozon_sync_state(shop_id,last_error) VALUES(?,?)
                        ON CONFLICT(shop_id) DO UPDATE SET last_error=excluded.last_error
                        """)) {
            statement.setInt(1, shopId);
            statement.setString(2, safeError);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
