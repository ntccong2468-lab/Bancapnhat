package com.tuandev.fbsbarcode.integration.ozon;

import com.tuandev.fbsbarcode.config.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** User-owned exceptions to the default rule that every Ozon SKU requires KIZ. */
public final class OzonProductKizPolicyRepository {
    public Set<String> findExemptSkus(int shopId) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT sku FROM ozon_product_kiz_policies
                        WHERE shop_id=? AND requires_kiz=0 ORDER BY sku
                        """)) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                Set<String> skus = new LinkedHashSet<>();
                while (result.next()) skus.add(result.getString(1));
                return Set.copyOf(skus);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public boolean requiresKiz(int shopId, String sku) {
        String safeSku = OzonApiClient.requireExternalId(sku, "SKU");
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT requires_kiz FROM ozon_product_kiz_policies WHERE shop_id=? AND sku=?
                        """)) {
            statement.setInt(1, shopId);
            statement.setString(2, safeSku);
            try (ResultSet result = statement.executeQuery()) {
                return !result.next() || result.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public void setRequired(int shopId, String sku, boolean required) {
        replace(shopId, List.of(OzonApiClient.requireExternalId(sku, "SKU")), required);
    }

    /** Atomically replaces all SKU exemptions for one shop. Absence means KIZ is required. */
    public void replaceExemptSkus(int shopId, Set<String> exemptSkus) {
        if (shopId <= 0 || exemptSkus == null || exemptSkus.size() > 10_000) {
            throw new IllegalArgumentException("Invalid Ozon KIZ policy selection.");
        }
        List<String> safeSkus = exemptSkus.stream()
                .map(sku -> OzonApiClient.requireExternalId(sku, "SKU"))
                .distinct()
                .toList();
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM ozon_product_kiz_policies WHERE shop_id=?");
                    PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO ozon_product_kiz_policies(shop_id,sku,requires_kiz,updated_at)
                        VALUES(?,?,0,?)
                        """)) {
                delete.setInt(1, shopId);
                delete.executeUpdate();
                String now = Instant.now().toString();
                for (String sku : safeSkus) {
                    insert.setInt(1, shopId);
                    insert.setString(2, sku);
                    insert.setString(3, now);
                    insert.addBatch();
                }
                insert.executeBatch();
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static void replace(int shopId, List<String> skus, boolean required) {
        if (shopId <= 0) throw new IllegalArgumentException("Invalid Ozon shop.");
        String sql = required
                ? "DELETE FROM ozon_product_kiz_policies WHERE shop_id=? AND sku=?"
                : """
                  INSERT INTO ozon_product_kiz_policies(shop_id,sku,requires_kiz,updated_at)
                  VALUES(?,?,0,?)
                  ON CONFLICT(shop_id,sku) DO UPDATE SET requires_kiz=0,updated_at=excluded.updated_at
                  """;
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String sku : skus) {
                statement.setInt(1, shopId);
                statement.setString(2, sku);
                if (!required) statement.setString(3, Instant.now().toString());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }
}
