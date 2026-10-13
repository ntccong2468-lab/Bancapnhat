package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.models.Kiz;
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
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Collections;

public class ZnackGtinInventoryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZnackGtinInventoryService.class);

    public List<Kiz> reserveAvailable(int shopId, String gtin, int quantity) {
        return reserveAvailable(shopId, gtin, quantity, UUID.randomUUID().toString());
    }

    public List<Kiz> reserveAvailable(int shopId, String gtin, int quantity, String reservationToken) {
        return reserveAvailableBulk(shopId, Map.of(gtin, quantity), reservationToken).get(GtinNormalizer.normalize(gtin));
    }

    /** One scoped transaction for the entire print plan; shortages never leave partial reservations. */
    public Map<String, List<Kiz>> reserveAvailableBulk(int shopId, Map<String, Integer> quantities) {
        return reserveAvailableBulk(shopId, quantities, UUID.randomUUID().toString());
    }

    public Map<String, List<Kiz>> reserveAvailableBulk(int shopId, Map<String, Integer> quantities, String reservationToken) {
        if (shopId <= 0 || quantities == null || quantities.isEmpty() || quantities.size() > 10000) throw new IllegalArgumentException("Invalid KIZ reservation plan.");
        Map<String,Integer> requested = new LinkedHashMap<>();
        for (var entry : quantities.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0 || entry.getValue() > 100000) throw new IllegalArgumentException("Quantity must be positive and bounded.");
            requested.merge(GtinNormalizer.normalize(entry.getKey()), entry.getValue(), Math::addExact);
        }
        String token = reservationToken == null || reservationToken.isBlank()
                ? UUID.randomUUID().toString() : reservationToken;
        try (Connection c = Database.getConnection(); Statement tx = c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                Map<String,List<Kiz>> selected = selectAvailableBulk(c, shopId, requested);
                for(var entry:requested.entrySet()) {
                    int available = selected.getOrDefault(entry.getKey(), List.of()).size();
                    if (available != entry.getValue()) {
                        throw new IllegalStateException("KIZ_INVENTORY_SHORTAGE: GTIN " + entry.getKey()
                                + "; required=" + entry.getValue() + "; available=" + available);
                    }
                }
                List<Kiz> all=selected.values().stream().flatMap(List::stream).toList();
                String now=Instant.now().toString();
                for(int offset=0;offset<all.size();offset+=900) {
                List<Kiz> batch=all.subList(offset,Math.min(offset+900,all.size()));
                String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE kiz_codes SET status='RESERVED',reservation_token=?,reserved_at=?,
                        reservation_recoverable=1,updated_at=?
                        WHERE shop_id=? AND status='AVAILABLE' AND legal_status='IN_CIRCULATION' AND id IN (
                        """ + placeholders + ")")) {
                    ps.setString(1, token);
                    ps.setString(2, now);
                    ps.setString(3, now);
                    ps.setInt(4, shopId);
                    for (int i = 0; i < batch.size(); i++) ps.setInt(i + 5, batch.get(i).getId());
                    if (ps.executeUpdate() != batch.size()) {
                        throw new IllegalStateException("KIZ inventory changed while reserving codes.");
                    }
                }
                }
                tx.execute("COMMIT");
                Map<String,List<Kiz>> result=new LinkedHashMap<>();
                selected.forEach((gtin,codes)->result.put(gtin,codes.stream().map(kiz->new Kiz(kiz.getId(),kiz.getCode(),token)).toList()));
                return Collections.unmodifiableMap(result);
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
                "SELECT COUNT(*) FROM kiz_codes INDEXED BY idx_kiz_codes_available_circulation WHERE shop_id=? AND gtin=? "
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

    private Map<String,List<Kiz>> selectAvailableBulk(Connection c,int shopId,Map<String,Integer> requested)throws SQLException {
        Map<String,List<Kiz>> result=new LinkedHashMap<>();
        List<Map.Entry<String,Integer>> entries=new ArrayList<>(requested.entrySet());
        for(int start=0;start<entries.size();start+=400) {
            var batch=entries.subList(start,Math.min(start+400,entries.size()));
            String values=String.join(",",Collections.nCopies(batch.size(),"(?,?)"));
            String sql="WITH wanted(gtin,quantity) AS (VALUES "+values+")"+"""
                    , ranked AS (SELECT code.id,code.raw_code,code.gtin,wanted.quantity,
                      ROW_NUMBER() OVER(PARTITION BY code.gtin ORDER BY code.id) AS position
                      FROM kiz_codes code INDEXED BY idx_kiz_codes_available_circulation JOIN wanted ON wanted.gtin=code.gtin
                      WHERE code.shop_id=? AND code.status='AVAILABLE' AND code.legal_status='IN_CIRCULATION')
                    SELECT id,raw_code,gtin FROM ranked WHERE position<=quantity ORDER BY gtin,id
                    """;
            try(PreparedStatement ps=c.prepareStatement(sql)) {
                int index=1;for(var entry:batch){ps.setString(index++,entry.getKey());ps.setInt(index++,entry.getValue());}ps.setInt(index,shopId);
                try(ResultSet rs=ps.executeQuery()){while(rs.next())result.computeIfAbsent(rs.getString(3),ignored->new ArrayList<>()).add(new Kiz(rs.getInt(1),rs.getString(2)));}
            }
        }
        return result;
    }

    private int updateCodes(int shopId, List<Kiz> codes, String target, boolean consumed, boolean requireAll) {
        if (codes == null || codes.isEmpty()) return 0;
        var ids=new HashSet<Integer>();
        for(Kiz code:codes)if(code==null||code.getReservationToken()==null||code.getReservationToken().isBlank()||!ids.add(code.getId()))throw new IllegalStateException("KIZ reservation ownership is invalid.");
        String update = """
                UPDATE kiz_codes SET status=?,reservation_token=NULL,reserved_at=NULL,
                reservation_recoverable=NULL,consumed_at=?,updated_at=?
                WHERE status='RESERVED' AND shop_id=? AND id IN (SELECT id FROM requested) AND EXISTS (
                  SELECT 1 FROM requested WHERE requested.id=kiz_codes.id AND requested.token=kiz_codes.reservation_token)
                """;
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                int updated = 0;
                String now = Instant.now().toString();
                for (int start=0;start<codes.size();start+=400) {
                    var batch=codes.subList(start,Math.min(start+400,codes.size()));
                    String values=String.join(",",Collections.nCopies(batch.size(),"(?,?)"));
                    try(PreparedStatement ps=c.prepareStatement("WITH requested(id,token) AS (VALUES "+values+") "+update)) {
                    int index=1;for(Kiz code:batch){ps.setInt(index++,code.getId());ps.setString(index++,code.getReservationToken());}
                    ps.setString(index++, target);
                    ps.setString(index++, consumed ? now : null);
                    ps.setString(index++, now);
                    ps.setInt(index, shopId);
                    updated += ps.executeUpdate();
                    }
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
