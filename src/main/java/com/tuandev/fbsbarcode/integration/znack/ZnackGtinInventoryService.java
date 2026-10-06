package com.tuandev.fbsbarcode.integration.znack;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.models.Kiz;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ZnackGtinInventoryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZnackGtinInventoryService.class);

    public List<Kiz> reserveAvailable(int shopId, String gtin, int quantity) {
        return reserveAvailable(shopId, gtin, quantity, UUID.randomUUID().toString());
    }

    public List<Kiz> reserveAvailable(int shopId, String gtin, int quantity, String reservationToken) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive.");
        String normalized = GtinNormalizer.normalize(gtin);
        String token = reservationToken == null || reservationToken.isBlank()
                ? UUID.randomUUID().toString() : reservationToken;
        try (Connection c = Database.getConnection(); Statement tx = c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                List<Kiz> selected = selectAvailable(c, shopId, normalized, quantity);
                if (selected.size() != quantity) {
                    throw new IllegalStateException("Not enough available KIZ for GTIN " + normalized
                            + ": required " + quantity + ", available " + selected.size());
                }
                String placeholders = String.join(",", java.util.Collections.nCopies(selected.size(), "?"));
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE kiz_codes SET status='RESERVED',reservation_token=?,reserved_at=?,
                        reservation_recoverable=1,updated_at=?
                        WHERE shop_id=? AND status='AVAILABLE' AND legal_status='IN_CIRCULATION' AND id IN (
                        """ + placeholders + ")")) {
                    String now = Instant.now().toString();
                    ps.setString(1, token);
                    ps.setString(2, now);
                    ps.setString(3, now);
                    ps.setInt(4, shopId);
                    for (int i = 0; i < selected.size(); i++) ps.setInt(i + 5, selected.get(i).getId());
                    if (ps.executeUpdate() != selected.size()) {
                        throw new IllegalStateException("KIZ inventory changed while reserving codes.");
                    }
                }
                tx.execute("COMMIT");
                return selected.stream().map(kiz -> new Kiz(kiz.getId(), kiz.getCode(), token)).toList();
            } catch (Exception e) {
                tx.execute("ROLLBACK");
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int consume(int shopId, List<Kiz> codes) {
        return updateCodes(shopId, codes, "CONSUMED", true, true);
    }

    public int release(int shopId, List<Kiz> codes) {
        return updateCodes(shopId, codes, "AVAILABLE", false, true);
    }

    public int availableCount(int shopId, String gtin) {
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM kiz_codes WHERE shop_id=? AND gtin=? "
                        + "AND status='AVAILABLE' AND legal_status='IN_CIRCULATION'")) {
            ps.setInt(1, shopId);
            ps.setString(2, GtinNormalizer.normalize(gtin));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int discardableCount(int shopId, String gtin) {
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM kiz_codes code
                WHERE code.shop_id=? AND code.gtin=? AND code.status='AVAILABLE'
                  AND COALESCE(code.legal_status,'')<>'IN_CIRCULATION'
                  AND EXISTS (
                    SELECT 1 FROM znack_purchase_pipelines failed
                    WHERE failed.shop_id=code.shop_id AND failed.order_id=code.order_id
                      AND failed.stage='INTRODUCTION_FAILED'
                  )
                """)) {
            ps.setInt(1, shopId);
            ps.setString(2, GtinNormalizer.normalize(gtin));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Archives unusable marks only after their purchase pipeline has explicitly failed introduction. */
    public int archiveDiscardable(int shopId, String shopName, String gtin) {
        String normalized = GtinNormalizer.normalize(gtin);
        try (Connection c = Database.getConnection(); Statement tx = c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                int archived;
                String now = Instant.now().toString();
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE kiz_codes AS code
                        SET status='ARCHIVED',reservation_token=NULL,reserved_at=NULL,
                            reservation_recoverable=NULL,updated_at=?
                        WHERE code.shop_id=? AND code.gtin=? AND code.status='AVAILABLE'
                          AND COALESCE(code.legal_status,'')<>'IN_CIRCULATION'
                          AND EXISTS (
                            SELECT 1 FROM znack_purchase_pipelines failed
                            WHERE failed.shop_id=code.shop_id AND failed.order_id=code.order_id
                              AND failed.stage='INTRODUCTION_FAILED'
                          )
                        """)) {
                    ps.setString(1, now);
                    ps.setInt(2, shopId);
                    ps.setString(3, normalized);
                    archived = ps.executeUpdate();
                }
                if (archived > 0) {
                    try (PreparedStatement log = c.prepareStatement("""
                            INSERT INTO znack_operation_logs
                            (shop_id,shop_name,action,entity_reference,severity,message,http_status,created_at)
                            VALUES(?,?,?,?,?,?,NULL,?)
                            """)) {
                        log.setInt(1, shopId);
                        log.setString(2, shopName == null ? "" : shopName);
                        log.setString(3, "KIZ_ARCHIVE");
                        log.setString(4, normalized);
                        log.setString(5, "INFO");
                        log.setString(6, "ARCHIVED_UNUSABLE_KIZ:" + archived);
                        log.setString(7, now);
                        log.executeUpdate();
                    }
                }
                tx.execute("COMMIT");
                return archived;
            } catch (Exception e) {
                tx.execute("ROLLBACK");
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int releaseRecoverableReservations() {
        String now = Instant.now().toString();
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE kiz_codes SET status='AVAILABLE',reservation_token=NULL,reserved_at=NULL,
                reservation_recoverable=NULL,updated_at=?
                WHERE status='RESERVED' AND reservation_recoverable=1
                """)) {
            ps.setString(1, now);
            int released = ps.executeUpdate();
            if (released > 0) {
                LOGGER.warn("Released {} recoverable KIZ reservations left by an interrupted print workflow.", released);
            }
            return released;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private List<Kiz> selectAvailable(Connection c, int shopId, String gtin, int quantity) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id,raw_code FROM kiz_codes
                WHERE shop_id=? AND gtin=? AND status='AVAILABLE' AND legal_status='IN_CIRCULATION'
                ORDER BY id LIMIT ?
                """)) {
            ps.setInt(1, shopId);
            ps.setString(2, gtin);
            ps.setInt(3, quantity);
            try (ResultSet rs = ps.executeQuery()) {
                List<Kiz> result = new ArrayList<>();
                while (rs.next()) result.add(new Kiz(rs.getInt(1), rs.getString(2)));
                return result;
            }
        }
    }

    private int updateCodes(int shopId, List<Kiz> codes, String target, boolean consumed, boolean requireAll) {
        if (codes == null || codes.isEmpty()) return 0;
        String sql = """
                UPDATE kiz_codes SET status=?,reservation_token=NULL,reserved_at=NULL,
                reservation_recoverable=NULL,consumed_at=?,updated_at=?
                WHERE id=? AND status='RESERVED' AND reservation_token=? AND shop_id=?
                """;
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(false);
            try {
                int updated = 0;
                String now = Instant.now().toString();
                for (Kiz code : codes) {
                    if (code == null || code.getReservationToken() == null || code.getReservationToken().isBlank()) {
                        if (requireAll) throw new IllegalStateException("KIZ reservation token is missing.");
                        continue;
                    }
                    ps.setString(1, target);
                    ps.setString(2, consumed ? now : null);
                    ps.setString(3, now);
                    ps.setInt(4, code.getId());
                    ps.setString(5, code.getReservationToken());
                    ps.setInt(6, shopId);
                    updated += ps.executeUpdate();
                }
                if (requireAll && updated != codes.size()) {
                    throw new IllegalStateException("One or more KIZ reservations are no longer owned by this workflow.");
                }
                c.commit();
                return updated;
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
