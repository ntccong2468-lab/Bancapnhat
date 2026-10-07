package com.tuandev.fbsbarcode.features.shop;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.I18nService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class ShopRepository {
    public int insert(Shop shop) {
        shop.validateForCreate();
        String sql = "INSERT INTO shops (name, marketplace, client_id, api_key) VALUES (?, ?, ?, ?)";

        try (Connection conn = Database.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shop.getName());
            ps.setString(2, shop.getMarketplace().name());
            ps.setString(3, shop.getMarketplace() == Marketplace.OZON ? shop.getClientId() : null);
            ps.setString(4, shop.getApiKey());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int update(int id, Shop shop) {
        Shop existing = findById(id);
        if (existing == null) {
            return 0;
        }
        if (existing.getMarketplace() != shop.getMarketplace()) {
            throw new IllegalArgumentException("Marketplace cannot be changed after a shop is created.");
        }
        if (shop.getMarketplace() == Marketplace.OZON
                && (shop.getClientId() == null || shop.getClientId().isBlank())) {
            throw new IllegalArgumentException("Ozon Client ID is required.");
        }
        boolean replaceCredential = shop.getApiKey() != null && !shop.getApiKey().isBlank();
        String sql = replaceCredential
                ? "UPDATE shops SET name = ?, client_id = ?, api_key = ? WHERE id = ? AND marketplace = ?"
                : "UPDATE shops SET name = ?, client_id = ? WHERE id = ? AND marketplace = ?";

        try (Connection conn = Database.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shop.getName());
            ps.setString(2, shop.getMarketplace() == Marketplace.OZON ? shop.getClientId() : null);
            if (replaceCredential) {
                ps.setString(3, shop.getApiKey().strip());
                ps.setInt(4, id);
                ps.setString(5, existing.getMarketplace().name());
            } else {
                ps.setInt(3, id);
                ps.setString(4, existing.getMarketplace().name());
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Shop> findAll() {
        String sql = "SELECT id, name, marketplace, client_id, api_key FROM shops ORDER BY id";
        List<Shop> shops = new ArrayList<>();

        try (Connection conn = Database.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                shops.add(new Shop(
                        rs.getInt("id"),
                        rs.getString("name"),
                        Marketplace.fromDatabase(rs.getString("marketplace")),
                        rs.getString("client_id"),
                        rs.getString("api_key")
                ));
            }
            return shops;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Shop findById(int id) {
        String sql = "SELECT id, name, marketplace, client_id, api_key FROM shops WHERE id = ?";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Shop(
                        rs.getInt("id"),
                        rs.getString("name"),
                        Marketplace.fromDatabase(rs.getString("marketplace")),
                        rs.getString("client_id"),
                        rs.getString("api_key"));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int delete(int id) {
        return ShopOperationCoordinator.withExclusiveShop(id, () -> deleteExclusive(id));
    }

    private int deleteExclusive(int id) {
        String sql = "DELETE FROM shops WHERE id = ?";
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement active = conn.prepareStatement("""
                        SELECT 1 FROM ozon_exemplar_jobs
                        WHERE shop_id=? AND stage NOT IN ('ACCEPTED','REJECTED') LIMIT 1
                    """);
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                active.setInt(1, id);
                try (ResultSet result = active.executeQuery()) {
                    if (result.next()) {
                        throw new IllegalStateException("The Ozon shop has an unfinished exemplar job.");
                    }
                }
                // Keep the shop, credentials and reconciliation history until every GTIN outcome is known.
                try (PreparedStatement gtin = conn.prepareStatement("""
                            SELECT 1 FROM gtin_sync_items
                            WHERE shop_id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED') LIMIT 1
                        """)) {
                    gtin.setInt(1, id);
                    try (ResultSet result = gtin.executeQuery()) {
                        if (result.next()) {
                            throw new IllegalStateException(I18nService.getInstance().tr("gtinsync.shop_delete_pending"));
                        }
                    }
                }
                try (PreparedStatement registration = conn.prepareStatement("""
                            SELECT 1 FROM znack_card_registrations
                            WHERE shop_id=? AND status NOT IN ('PUBLISHED','PRE_SUBMIT_ERROR','PREFLIGHT_ERROR') LIMIT 1
                        """)) {
                    registration.setInt(1, id);
                    try (ResultSet result = registration.executeQuery()) {
                        if (result.next()) {
                            throw new IllegalStateException(I18nService.getInstance().tr("znack.registration.shop_delete_pending"));
                        }
                    }
                }
                ps.setInt(1, id);
                int deleted = ps.executeUpdate();
                conn.commit();
                return deleted;
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
