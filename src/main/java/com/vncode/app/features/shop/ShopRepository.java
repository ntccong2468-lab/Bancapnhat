package com.vncode.app.features.shop;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.I18nService;
import com.vncode.app.features.license.LicenseRuntime;
import static com.vncode.app.features.license.LicenseModels.Permission.SHOP_MANAGE;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class ShopRepository {
    private static final Object CREATE_LOCK = new Object();
    public int insert(Shop shop) {
        shop.validateForCreate();
        // One application process owns this database. Recheck rights and capacity after the creation wait.
        synchronized (CREATE_LOCK) {
            LicenseRuntime.require(SHOP_MANAGE);
            return insertWithCapacity(shop);
        }
    }

    private int insertWithCapacity(Shop shop) {
        String sql = "INSERT INTO shops (name, marketplace, client_id, api_key) VALUES (?, ?, ?, ?)";

        try (Connection conn = Database.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            try (Statement count = conn.createStatement(); ResultSet result = count.executeQuery("SELECT count(*) FROM shops")) {
                result.next();
                LicenseRuntime.requireShopCapacity(result.getInt(1));
            }
            ps.setString(1, shop.getName());
            ps.setString(2, shop.getMarketplace().name());
            ps.setString(3, shop.getMarketplace() == Marketplace.OZON ? shop.getClientId() : null);
            ps.setString(4, shop.getApiKey());
            int inserted=ps.executeUpdate();
            try(Statement count=conn.createStatement();ResultSet result=count.executeQuery("SELECT count(*) FROM shops")){result.next();LicenseRuntime.observeShopCount(result.getInt(1));}
            return inserted;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int update(int id, Shop shop) {
        return ShopOperationCoordinator.withExclusiveShop(id,()->updateExclusive(id,shop));
    }

    private int updateExclusive(int id, Shop shop) {
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
            LicenseRuntime.require(SHOP_MANAGE);
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
            LicenseRuntime.observeShopCount(shops.size());
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
        LicenseRuntime.require(SHOP_MANAGE);
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
                try (PreparedStatement copy = conn.prepareStatement("""
                            SELECT 1 FROM listing_copy_jobs
                            WHERE (source_shop_id=? OR destination_shop_id=?)
                              AND state IN ('SENDING','REMOTE_PROCESSING','RECONCILE_REQUIRED') LIMIT 1
                        """)) {
                    copy.setInt(1, id); copy.setInt(2, id);
                    try (ResultSet result = copy.executeQuery()) {
                        if (result.next()) throw new IllegalStateException(I18nService.getInstance().tr("copy.shop_delete_pending"));
                    }
                }
                try (PreparedStatement bulk = conn.prepareStatement("""
                            SELECT 1 FROM product_edit_items WHERE shop_id=?
                              AND state IN ('SENDING','REMOTE_PROCESSING','RECONCILE_REQUIRED') LIMIT 1
                        """)) {
                    bulk.setInt(1, id);
                    try (ResultSet result = bulk.executeQuery()) {
                        if (result.next()) throw new IllegalStateException(I18nService.getInstance().tr("products.shop_delete_pending"));
                    }
                }
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
                            WHERE shop_id=? AND status NOT IN ('PUBLISHED','PRE_SUBMIT_ERROR','PREFLIGHT_ERROR')
                            UNION ALL SELECT 1 FROM znack_card_registration_replacements
                            WHERE shop_id=? AND state='CANDIDATE'
                            UNION ALL SELECT 1 FROM ozon_card_registrations
                            WHERE shop_id=? AND (status NOT IN ('PUBLISHED','NOT_CREATED','PREFLIGHT_ERROR')
                                                OR (gtin<>'' AND active_gtin<>gtin)) LIMIT 1
                        """)) {
                    registration.setInt(1, id);
                    registration.setInt(2, id);
                    registration.setInt(3, id);
                    try (ResultSet result = registration.executeQuery()) {
                        if (result.next()) {
                            throw new IllegalStateException(I18nService.getInstance().tr("znack.registration.shop_delete_pending"));
                        }
                    }
                }
                ps.setInt(1, id);
                LicenseRuntime.require(SHOP_MANAGE);
                int deleted = ps.executeUpdate();
                conn.commit();
                try(Statement count=conn.createStatement();ResultSet result=count.executeQuery("SELECT count(*) FROM shops")){result.next();LicenseRuntime.observeShopCount(result.getInt(1));}
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
