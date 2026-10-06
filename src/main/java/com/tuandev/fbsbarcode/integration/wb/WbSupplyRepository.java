package com.tuandev.fbsbarcode.integration.wb;

import com.tuandev.fbsbarcode.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.setNullableBoolean;
import static com.tuandev.fbsbarcode.integration.wb.WbRepositorySupport.setNullableInteger;

public class WbSupplyRepository {
    public int deleteSupply(int shopId, String supplyId) {
        if (shopId <= 0 || supplyId == null || supplyId.isBlank()) {
            throw new IllegalArgumentException("Invalid supply delete");
        }
        String sql = "DELETE FROM wb_supplies WHERE shop_id = ? AND supply_id = ?";
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shopId);
            statement.setString(2, supplyId);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public void saveSupplies(int shopId, List<WbSupplyDto> supplies) {
        if (supplies == null || supplies.isEmpty()) {
            return;
        }
        String now = Instant.now().toString();
        String sql = """
                INSERT INTO wb_supplies (
                    shop_id, supply_id, is_b2b, done, order_count, created_at, closed_at, scan_dt, reject_dt, name, cargo_type, cross_border_type, destination_office_id, recommended_wh_id, synced_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(shop_id, supply_id) DO UPDATE SET
                    is_b2b = excluded.is_b2b,
                    done = excluded.done,
                    order_count = COALESCE(wb_supplies.order_count, excluded.order_count),
                    created_at = excluded.created_at,
                    closed_at = excluded.closed_at,
                    scan_dt = excluded.scan_dt,
                    reject_dt = excluded.reject_dt,
                    name = excluded.name,
                    cargo_type = excluded.cargo_type,
                    cross_border_type = excluded.cross_border_type,
                    destination_office_id = excluded.destination_office_id,
                    recommended_wh_id = excluded.recommended_wh_id,
                    synced_at = excluded.synced_at
                """;
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (WbSupplyDto supply : supplies) {
                    ps.setInt(1, shopId);
                    ps.setString(2, supply.getId());
                    setNullableBoolean(ps, 3, supply.getIsB2b());
                    setNullableBoolean(ps, 4, supply.getDone());
                    ps.setObject(5, null);
                    ps.setString(6, supply.getCreatedAt());
                    ps.setString(7, supply.getClosedAt());
                    ps.setString(8, supply.getScanDt());
                    ps.setString(9, supply.getRejectDt());
                    ps.setString(10, supply.getName());
                    setNullableInteger(ps, 11, supply.getCargoType());
                    setNullableInteger(ps, 12, supply.getCrossBorderType());
                    setNullableInteger(ps, 13, supply.getDestinationOfficeId());
                    setNullableInteger(ps, 14, supply.getRecommendedWhId());
                    ps.setString(15, now);
                    ps.addBatch();
                }
                ps.executeBatch();
                for (WbSupplyDto supply : supplies) {
                    if (supply.getId() != null && !supply.getId().isBlank()) {
                        populateSupplyOrderLinks(conn, shopId, supply.getId());
                    }
                }
                conn.commit();
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<WbSupplySummary> getSupplySummaries(int shopId) {
        List<WbSupplySummary> supplies = new ArrayList<>();
        String sql = """
                SELECT s.supply_id,
                       s.name,
                       s.done,
                       s.is_b2b,
                       s.created_at,
                       COALESCE(NULLIF((
                           SELECT COUNT(*)
                           FROM wb_supply_orders so
                           WHERE so.shop_id = s.shop_id
                             AND so.supply_id = s.supply_id
                       ), 0), s.order_count, 0) AS item_count
                FROM wb_supplies s
                WHERE s.shop_id = ?
                ORDER BY s.done ASC, s.created_at DESC, s.supply_id DESC
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                supplies.add(new WbSupplySummary(
                        rs.getString("supply_id"),
                        rs.getString("name"),
                        rs.getInt("done") == 1,
                        rs.getObject("is_b2b") == null ? null : rs.getInt("is_b2b") == 1,
                        rs.getString("created_at"),
                        rs.getInt("item_count")
                ));
            }
            return supplies;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public WbSupplySummary findSupplySummary(int shopId, String supplyId) {
        if (shopId <= 0 || supplyId == null || supplyId.isBlank()) {
            throw new IllegalArgumentException("Invalid supply lookup");
        }
        String sql = """
                SELECT s.supply_id,
                       s.name,
                       s.done,
                       s.is_b2b,
                       s.created_at,
                       COALESCE(NULLIF((
                           SELECT COUNT(*)
                           FROM wb_supply_orders so
                           WHERE so.shop_id = s.shop_id
                             AND so.supply_id = s.supply_id
                       ), 0), s.order_count, 0) AS item_count
                FROM wb_supplies s
                WHERE s.shop_id = ? AND s.supply_id = ?
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setInt(1, shopId);
            statement.setString(2, supplyId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? toSummary(rs) : null;
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public SupplyPage findSupplyPage(
            int shopId, String query, Boolean done, int limit, int offset) {
        if (shopId <= 0 || query == null || limit <= 0 || limit > 100 || offset < 0) {
            throw new IllegalArgumentException("Invalid supply page request");
        }
        String searchPattern = "%" + escapeLike(query.strip().toLowerCase(Locale.ROOT)) + "%";
        String searchClause = """
                AND (
                    LOWER(s.supply_id) LIKE ? ESCAPE '\\'
                    OR LOWER(COALESCE(s.name, '')) LIKE ? ESCAPE '\\'
                )
                """;
        String statusClause = done == null ? "" : "AND COALESCE(s.done, 0) = ?";
        String countSql = """
                SELECT COALESCE(SUM(CASE WHEN COALESCE(s.done, 0) = 0 THEN 1 ELSE 0 END), 0) AS open_items,
                       COALESCE(SUM(CASE WHEN s.done = 1 THEN 1 ELSE 0 END), 0) AS closed_items
                FROM wb_supplies s
                WHERE s.shop_id = ?
                """ + searchClause;
        String pageSql = """
                SELECT s.supply_id,
                       s.name,
                       s.done,
                       s.is_b2b,
                       s.created_at,
                       COALESCE(NULLIF((
                           SELECT COUNT(*)
                           FROM wb_supply_orders so
                           WHERE so.shop_id = s.shop_id
                             AND so.supply_id = s.supply_id
                       ), 0), s.order_count, 0) AS item_count
                FROM wb_supplies s
                WHERE s.shop_id = ?
                """ + searchClause + statusClause + """
                ORDER BY COALESCE(s.done, 0) ASC, s.created_at DESC, s.supply_id DESC
                LIMIT ? OFFSET ?
                """;
        try (Connection conn = Database.getConnection()) {
            int openItems;
            int closedItems;
            try (PreparedStatement counts = conn.prepareStatement(countSql)) {
                bindSearch(counts, shopId, searchPattern);
                try (ResultSet rs = counts.executeQuery()) {
                    if (rs.next()) {
                        openItems = rs.getInt("open_items");
                        closedItems = rs.getInt("closed_items");
                    } else {
                        openItems = 0;
                        closedItems = 0;
                    }
                }
            }

            List<WbSupplySummary> items = new ArrayList<>();
            try (PreparedStatement page = conn.prepareStatement(pageSql)) {
                int index = bindSearch(page, shopId, searchPattern);
                if (done != null) {
                    page.setInt(index++, done ? 1 : 0);
                }
                page.setInt(index++, limit);
                page.setInt(index, offset);
                try (ResultSet rs = page.executeQuery()) {
                    while (rs.next()) {
                        items.add(toSummary(rs));
                    }
                }
            }
            int totalItems = done == null ? openItems + closedItems : done ? closedItems : openItems;
            return new SupplyPage(List.copyOf(items), totalItems, openItems, closedItems);
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public List<String> getRecentSupplyIds(int shopId, int limit) {
        List<String> supplyIds = new ArrayList<>();
        String sql = """
                SELECT DISTINCT supply_id
                FROM wb_orders
                WHERE shop_id = ? AND supply_id IS NOT NULL AND supply_id <> ''
                ORDER BY created_at DESC
                LIMIT ?
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                supplyIds.add(rs.getString("supply_id"));
            }
            return supplyIds;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> getOpenSupplyIds(int shopId, int limit) {
        List<String> supplyIds = new ArrayList<>();
        String sql = """
                SELECT supply_id
                FROM wb_supplies
                WHERE shop_id = ? AND done = 0
                ORDER BY CASE WHEN COALESCE(order_count,0)=0 THEN 0 ELSE 1 END,
                         COALESCE(synced_at,'') ASC,created_at ASC,supply_id ASC
                LIMIT ?
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                supplyIds.add(rs.getString("supply_id"));
            }
            return supplyIds;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int countOpenSupplies(int shopId) {
        String sql = """
                SELECT COUNT(*)
                FROM wb_supplies
                WHERE shop_id = ? AND done = 0
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void updateSupplyOrderCount(int shopId, String supplyId, int orderCount) {
        String sql = """
                UPDATE wb_supplies
                SET order_count = ?, synced_at = ?
                WHERE shop_id = ? AND supply_id = ?
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Math.max(0, orderCount));
            ps.setString(2, Instant.now().toString());
            ps.setInt(3, shopId);
            ps.setString(4, supplyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void saveCreatedSupply(int shopId, String supplyId, String name, Boolean b2b) {
        String sql = """
                INSERT INTO wb_supplies(
                    shop_id, supply_id, is_b2b, done, order_count, created_at, name, synced_at
                ) VALUES (?, ?, ?, 0, 0, ?, ?, ?)
                ON CONFLICT(shop_id, supply_id) DO UPDATE SET
                    is_b2b = excluded.is_b2b,
                    done = 0,
                    name = excluded.name,
                    synced_at = excluded.synced_at
                """;
        String now = Instant.now().toString();
        try (Connection conn = Database.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setString(2, supplyId);
            setNullableBoolean(ps, 3, b2b);
            ps.setString(4, now);
            ps.setString(5, name);
            ps.setString(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Boolean getSupplyB2b(int shopId, String supplyId) {
        String sql = "SELECT is_b2b FROM wb_supplies WHERE shop_id = ? AND supply_id = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setString(2, supplyId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next() || rs.getObject("is_b2b") == null) {
                return null;
            }
            return rs.getInt("is_b2b") == 1;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void markSupplyDelivered(int shopId, String supplyId) {
        String sql = """
                UPDATE wb_supplies
                SET done = 1, closed_at = COALESCE(closed_at, ?), synced_at = ?
                WHERE shop_id = ? AND supply_id = ?
                """;
        String now = Instant.now().toString();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, now);
            ps.setString(2, now);
            ps.setInt(3, shopId);
            ps.setString(4, supplyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void populateSupplyOrderLinks(Connection conn, int shopId, String supplyId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO wb_supply_orders(shop_id, supply_id, order_id)
                SELECT shop_id, supply_id, order_id
                FROM wb_orders
                WHERE shop_id = ?
                  AND supply_id = ?
                """)) {
            ps.setInt(1, shopId);
            ps.setString(2, supplyId);
            ps.executeUpdate();
        }
    }

    private static int bindSearch(PreparedStatement statement, int shopId, String searchPattern)
            throws SQLException {
        statement.setInt(1, shopId);
        statement.setString(2, searchPattern);
        statement.setString(3, searchPattern);
        return 4;
    }

    private static WbSupplySummary toSummary(ResultSet rs) throws SQLException {
        return new WbSupplySummary(
                rs.getString("supply_id"),
                rs.getString("name"),
                rs.getInt("done") == 1,
                rs.getObject("is_b2b") == null ? null : rs.getInt("is_b2b") == 1,
                rs.getString("created_at"),
                rs.getInt("item_count"));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public record SupplyPage(
            List<WbSupplySummary> items, int totalItems, int openItems, int closedItems) {
        public SupplyPage {
            items = List.copyOf(items);
        }
    }
}
