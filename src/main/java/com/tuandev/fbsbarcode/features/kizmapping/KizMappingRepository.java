package com.tuandev.fbsbarcode.features.kizmapping;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.znack.GtinNormalizer;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventorySummary;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinMappingRule;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class KizMappingRepository {
    public static final int GENDER_CHARACTERISTIC_ID = 204557;
    public static final String WILDCARD_GENDER = "*";
    public static final String UNSPECIFIED_GENDER = "__UNSPECIFIED__";
    private static final int SQL_PARAM_BATCH_SIZE = 900;

    public List<String> findSubjects(int shopId) {
        return strings("""
                SELECT DISTINCT subject_name FROM wb_product_cards
                WHERE shop_id=? AND subject_name IS NOT NULL AND TRIM(subject_name)<>''
                  AND (COALESCE(need_kiz,0)=1 OR COALESCE(kiz_marked,0)=1)
                ORDER BY subject_name COLLATE NOCASE
                """, shopId);
    }

    public List<String> findGtinCategories(int shopId) {
        return strings("""
                SELECT DISTINCT TRIM(category) FROM znack_products
                WHERE shop_id=? AND gtin NOT LIKE '029%' AND deleted_at IS NULL
                  AND category IS NOT NULL AND TRIM(category)<>''
                ORDER BY TRIM(category) COLLATE NOCASE
                """, shopId);
    }

    public List<ZnackGtinInventorySummary> findGtinSummariesPage(
            int shopId, String query, List<String> categories, int limit, int offset) {
        if (shopId <= 0 || query == null || categories == null || categories.size() > 30
                || limit < 1 || limit > 101 || offset < 0 || offset > 10_000_000) {
            throw new IllegalArgumentException("Invalid GTIN summary page.");
        }
        String categoryClause = categories.isEmpty()
                ? ""
                : " AND TRIM(p.category) IN (" + String.join(",", Collections.nCopies(categories.size(), "?")) + ")";
        String sql = """
                SELECT p.gtin,p.product_name,p.category,
                  SUM(CASE WHEN c.status='AVAILABLE' AND c.legal_status='IN_CIRCULATION' THEN 1 ELSE 0 END) available_count,
                  SUM(CASE WHEN c.status='RESERVED' AND c.legal_status='IN_CIRCULATION' THEN 1 ELSE 0 END) reserved_count,
                  SUM(CASE WHEN c.status='CONSUMED' AND c.legal_status='IN_CIRCULATION' THEN 1 ELSE 0 END) consumed_count,
                  SUM(CASE WHEN c.status='AVAILABLE' AND COALESCE(c.legal_status,'')<>'IN_CIRCULATION'
                    AND EXISTS (
                      SELECT 1 FROM znack_purchase_pipelines failed
                      WHERE failed.shop_id=c.shop_id AND failed.order_id=c.order_id
                        AND failed.stage='INTRODUCTION_FAILED'
                    ) THEN 1 ELSE 0 END) discardable_count,
                  ((SELECT COUNT(*) FROM znack_gtin_mapping_rules r WHERE r.shop_id=p.shop_id AND r.gtin=p.gtin)
                   + (SELECT COUNT(*) FROM ozon_product_gtin_mappings o WHERE o.shop_id=p.shop_id AND o.gtin=p.gtin)
                   + (SELECT COUNT(*) FROM ozon_article_gtin_mappings a WHERE a.shop_id=p.shop_id AND a.gtin=p.gtin)) rule_count,
                  (SELECT o.local_status FROM kiz_orders o WHERE o.shop_id=p.shop_id AND o.gtin=p.gtin ORDER BY o.updated_at DESC LIMIT 1) order_status,
                  (SELECT x.id FROM znack_purchase_pipelines x WHERE x.shop_id=p.shop_id AND x.gtin=p.gtin ORDER BY x.updated_at DESC LIMIT 1) pipeline_id,
                  (SELECT x.stage FROM znack_purchase_pipelines x WHERE x.shop_id=p.shop_id AND x.gtin=p.gtin ORDER BY x.updated_at DESC LIMIT 1) pipeline_stage,
                  COALESCE((SELECT x.error_message FROM znack_purchase_pipelines x WHERE x.shop_id=p.shop_id AND x.gtin=p.gtin ORDER BY x.updated_at DESC LIMIT 1),
                           (SELECT o.error_message FROM kiz_orders o WHERE o.shop_id=p.shop_id AND o.gtin=p.gtin ORDER BY o.updated_at DESC LIMIT 1)) latest_error,
                  p.synced_at
                FROM znack_products p
                LEFT JOIN kiz_codes c ON c.shop_id=p.shop_id AND c.gtin=p.gtin
                WHERE p.shop_id=? AND p.gtin NOT LIKE '029%' AND p.deleted_at IS NULL
                  AND (?='' OR LOWER(p.gtin) LIKE ? ESCAPE '\\'
                       OR LOWER(COALESCE(p.product_name,'')) LIKE ? ESCAPE '\\'
                       OR LOWER(COALESCE(p.category,'')) LIKE ? ESCAPE '\\')
                """ + categoryClause + """
                GROUP BY p.shop_id,p.gtin,p.product_name,p.category,p.synced_at
                ORDER BY CASE WHEN p.category IS NULL OR TRIM(p.category)='' THEN 1 ELSE 0 END,
                         TRIM(p.category) COLLATE NOCASE,p.gtin
                LIMIT ? OFFSET ?
                """;
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int parameter = 1;
            ps.setInt(parameter++, shopId);
            String normalizedQuery = query.trim().toLowerCase(java.util.Locale.ROOT);
            String pattern = "%" + escapeLike(normalizedQuery) + "%";
            ps.setString(parameter++, normalizedQuery);
            ps.setString(parameter++, pattern);
            ps.setString(parameter++, pattern);
            ps.setString(parameter++, pattern);
            for (String category : categories) ps.setString(parameter++, category.trim());
            ps.setInt(parameter++, limit);
            ps.setInt(parameter, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<ZnackGtinInventorySummary> result = new ArrayList<>();
                while (rs.next()) result.add(summary(rs));
                return result;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean hasGtinProduct(int shopId, String gtin) {
        String normalized = GtinNormalizer.normalize(gtin);
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM znack_products WHERE shop_id=? AND gtin=? AND deleted_at IS NULL")) {
            ps.setInt(1, shopId);
            ps.setString(2, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public ZnackKizLabelMetadata findLabelMetadata(int shopId, String gtin) {
        String normalized = GtinNormalizer.normalize(gtin);
        String productName = "";
        String directGender = "";
        String directSize = "";
        Set<String> genders = new LinkedHashSet<>();
        Set<String> sizes = new LinkedHashSet<>();
        try (Connection c = Database.getConnection()) {
            try (PreparedStatement product = c.prepareStatement("""
                    SELECT COALESCE(product_name,''),COALESCE(gender,''),COALESCE(size,'')
                    FROM znack_products
                    WHERE shop_id=? AND gtin=? AND deleted_at IS NULL
                    """)) {
                product.setInt(1, shopId);
                product.setString(2, normalized);
                try (ResultSet rs = product.executeQuery()) {
                    if (rs.next()) {
                        productName = rs.getString(1);
                        directGender = rs.getString(2);
                        directSize = rs.getString(3);
                        if (directGender != null && !directGender.isBlank()) genders.add(directGender.strip());
                        if (directSize != null && !directSize.isBlank()) sizes.add(directSize.strip());
                    }
                }
            }
            try (PreparedStatement variants = c.prepareStatement("""
                    SELECT DISTINCT
                      COALESCE(NULLIF(TRIM(COALESCE(json_extract(gender.value_json,'$[0]'),
                        json_extract(gender.value_json,'$'))),''),?) gender_value,
                      COALESCE(NULLIF(TRIM(size.tech_size),''),NULLIF(TRIM(size.wb_size),''),'') size_value
                    FROM znack_gtin_mapping_rules rule
                    JOIN wb_product_cards card
                      ON card.shop_id=rule.shop_id AND card.subject_name=rule.subject_name
                    LEFT JOIN wb_product_characteristics gender
                      ON gender.shop_id=card.shop_id AND gender.nm_id=card.nm_id
                     AND gender.characteristic_id=?
                    LEFT JOIN wb_product_sizes size
                      ON size.shop_id=card.shop_id AND size.nm_id=card.nm_id
                    WHERE rule.shop_id=? AND rule.gtin=?
                      AND (rule.wildcard_gender=1 OR rule.gender_value=COALESCE(
                        NULLIF(TRIM(COALESCE(json_extract(gender.value_json,'$[0]'),
                          json_extract(gender.value_json,'$'))),''),?))
                    ORDER BY gender_value COLLATE NOCASE,size_value COLLATE NOCASE
                    """)) {
                variants.setString(1, UNSPECIFIED_GENDER);
                variants.setInt(2, GENDER_CHARACTERISTIC_ID);
                variants.setInt(3, shopId);
                variants.setString(4, normalized);
                variants.setString(5, UNSPECIFIED_GENDER);
                try (ResultSet rs = variants.executeQuery()) {
                    while (rs.next()) {
                        String gender = rs.getString(1);
                        String size = rs.getString(2);
                        if ((directGender == null || directGender.isBlank())
                                && gender != null && !gender.isBlank()
                                && !UNSPECIFIED_GENDER.equals(gender)) {
                            genders.add(gender.strip());
                        }
                        if ((directSize == null || directSize.isBlank())
                                && size != null && !size.isBlank()) sizes.add(size.strip());
                    }
                }
            }
            if (genders.isEmpty()) {
                try (PreparedStatement rules = c.prepareStatement("""
                        SELECT DISTINCT gender_value FROM znack_gtin_mapping_rules
                        WHERE shop_id=? AND gtin=? AND wildcard_gender=0
                          AND gender_value<>? ORDER BY gender_value COLLATE NOCASE
                        """)) {
                    rules.setInt(1, shopId);
                    rules.setString(2, normalized);
                    rules.setString(3, UNSPECIFIED_GENDER);
                    try (ResultSet rs = rules.executeQuery()) {
                        while (rs.next()) genders.add(rs.getString(1));
                    }
                }
            }
            return new ZnackKizLabelMetadata(productName, String.join(", ", genders), String.join(", ", sizes));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> findGenders(int shopId) {
        return findGendersForSubject(shopId, null);
    }

    public List<String> findGendersForSubject(int shopId, String subjectName) {
        String subjectFilter = subjectName == null ? "" : " AND c.subject_name=? ";
        String sql = """
                SELECT DISTINCT COALESCE(NULLIF(TRIM(COALESCE(json_extract(ch.value_json,'$[0]'),
                       json_extract(ch.value_json,'$'))),''),?) gender_value
                FROM wb_product_cards c
                LEFT JOIN wb_product_characteristics ch
                  ON ch.shop_id=c.shop_id AND ch.nm_id=c.nm_id AND ch.characteristic_id=?
                WHERE c.shop_id=? AND (COALESCE(c.need_kiz,0)=1 OR COALESCE(c.kiz_marked,0)=1)
                """ + subjectFilter + " ORDER BY gender_value COLLATE NOCASE";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, UNSPECIFIED_GENDER);
            ps.setInt(2, GENDER_CHARACTERISTIC_ID);
            ps.setInt(3, shopId);
            if (subjectName != null) ps.setString(4, subjectName);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rs.next()) values.add(rs.getString(1));
                return values;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<ZnackGtinMappingRule> findRulesForGtin(int shopId, String gtin) {
        String normalized = GtinNormalizer.normalize(gtin);
        String sql = """
                SELECT shop_id,gtin,subject_name,gender_value,wildcard_gender,updated_at
                FROM znack_gtin_mapping_rules WHERE shop_id=? AND gtin=?
                ORDER BY subject_name COLLATE NOCASE,gender_value COLLATE NOCASE
                """;
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setString(2, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                List<ZnackGtinMappingRule> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new ZnackGtinMappingRule(rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getInt(5) != 0, Instant.parse(rs.getString(6))));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, String> findOwnersForSubject(int shopId, String subjectName) {
        String sql = """
                SELECT mapping.gender_value,mapping.gtin
                FROM znack_gtin_mapping_rules mapping
                JOIN znack_products product
                  ON product.shop_id=mapping.shop_id AND product.gtin=mapping.gtin
                WHERE mapping.shop_id=? AND mapping.subject_name=? AND mapping.gtin NOT LIKE '029%'
                  AND product.deleted_at IS NULL AND product.identity_archived_at IS NULL
                """;
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setString(2, subjectName);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, String> result = new LinkedHashMap<>();
                while (rs.next()) result.put(rs.getString(1), rs.getString(2));
                return result;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void replaceRulesForGtin(int shopId, String gtin, List<ZnackGtinMappingSelection> selections) {
        String normalized = GtinNormalizer.requireProductionOrderable(gtin);
        List<ZnackGtinMappingSelection> safe = normalizeSelections(selections);
        try (Connection c = Database.getConnection(); Statement tx = c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                requireProduct(c, shopId, normalized);
                try (PreparedStatement cleanup = c.prepareStatement(
                        "DELETE FROM znack_gtin_mapping_rules WHERE shop_id=? AND gtin LIKE '029%'")) {
                    cleanup.setInt(1, shopId);
                    cleanup.executeUpdate();
                }
                validateNoConflicts(c, shopId, normalized, safe);
                try (PreparedStatement delete = c.prepareStatement(
                        "DELETE FROM znack_gtin_mapping_rules WHERE shop_id=? AND gtin=?")) {
                    delete.setInt(1, shopId);
                    delete.setString(2, normalized);
                    delete.executeUpdate();
                }
                String now = Instant.now().toString();
                try (PreparedStatement insert = c.prepareStatement("""
                        INSERT INTO znack_gtin_mapping_rules
                        (shop_id,gtin,subject_name,gender_value,wildcard_gender,created_at,updated_at)
                        VALUES(?,?,?,?,?,?,?)
                        """)) {
                    for (ZnackGtinMappingSelection selection : safe) {
                        insert.setInt(1, shopId);
                        insert.setString(2, normalized);
                        insert.setString(3, selection.subjectName().trim());
                        insert.setString(4, selection.wildcardGender() ? WILDCARD_GENDER : normalizeGender(selection.genderValue()));
                        insert.setInt(5, selection.wildcardGender() ? 1 : 0);
                        insert.setString(6, now);
                        insert.setString(7, now);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                tx.execute("COMMIT");
            } catch (Exception e) {
                tx.execute("ROLLBACK");
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<Long, String> findMappings(int shopId, List<Long> nmIds) {
        if (nmIds == null || nmIds.isEmpty()) return Map.of();
        Map<Long, String> mappings = new LinkedHashMap<>();
        try (Connection c = Database.getConnection()) {
            for (int start = 0; start < nmIds.size(); start += SQL_PARAM_BATCH_SIZE) {
                List<Long> batch = nmIds.subList(start, Math.min(start + SQL_PARAM_BATCH_SIZE, nmIds.size()));
                String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
                String sql = """
                        SELECT c.nm_id,COALESCE(exact.gtin,wildcard.gtin) gtin
                        FROM wb_product_cards c
                        LEFT JOIN wb_product_characteristics ch
                          ON ch.shop_id=c.shop_id AND ch.nm_id=c.nm_id AND ch.characteristic_id=?
                        LEFT JOIN znack_gtin_mapping_rules exact
                          ON exact.shop_id=c.shop_id AND exact.subject_name=c.subject_name
                         AND exact.gtin NOT LIKE '029%'
                         AND EXISTS(SELECT 1 FROM znack_products exact_product
                           WHERE exact_product.shop_id=exact.shop_id AND exact_product.gtin=exact.gtin
                             AND exact_product.deleted_at IS NULL
                             AND exact_product.identity_archived_at IS NULL)
                         AND exact.wildcard_gender=0
                         AND exact.gender_value=COALESCE(NULLIF(TRIM(COALESCE(json_extract(ch.value_json,'$[0]'),
                             json_extract(ch.value_json,'$'))),''),?)
                        LEFT JOIN znack_gtin_mapping_rules wildcard
                          ON wildcard.shop_id=c.shop_id AND wildcard.subject_name=c.subject_name
                         AND wildcard.gtin NOT LIKE '029%'
                         AND EXISTS(SELECT 1 FROM znack_products wildcard_product
                           WHERE wildcard_product.shop_id=wildcard.shop_id AND wildcard_product.gtin=wildcard.gtin
                             AND wildcard_product.deleted_at IS NULL
                             AND wildcard_product.identity_archived_at IS NULL)
                         AND wildcard.wildcard_gender=1
                        WHERE c.shop_id=? AND c.nm_id IN (
                        """ + placeholders + ")";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setInt(1, GENDER_CHARACTERISTIC_ID);
                    ps.setString(2, UNSPECIFIED_GENDER);
                    ps.setInt(3, shopId);
                    for (int i = 0; i < batch.size(); i++) ps.setLong(i + 4, batch.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String gtin = rs.getString(2);
                            if (gtin != null) mappings.put(rs.getLong(1), gtin);
                        }
                    }
                }
            }
            return mappings;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Set<Long> findKizRequiredNmIds(int shopId, List<Long> nmIds) {
        if (nmIds == null || nmIds.isEmpty()) return Set.of();
        Set<Long> result = new LinkedHashSet<>();
        try (Connection c = Database.getConnection()) {
            for (int start = 0; start < nmIds.size(); start += SQL_PARAM_BATCH_SIZE) {
                List<Long> batch = nmIds.subList(start, Math.min(start + SQL_PARAM_BATCH_SIZE, nmIds.size()));
                String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
                String sql = """
                        SELECT nm_id FROM wb_product_cards WHERE shop_id=? AND nm_id IN (
                        """ + placeholders + ") AND (COALESCE(need_kiz,0)=1 OR COALESCE(kiz_marked,0)=1)";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setInt(1, shopId);
                    for (int i = 0; i < batch.size(); i++) ps.setLong(i + 2, batch.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) result.add(rs.getLong(1));
                    }
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<ZnackGtinInventorySummary> findGtinSummaries(int shopId) {
        String sql = """
                SELECT p.gtin,p.product_name,p.category,
                  SUM(CASE WHEN c.status='AVAILABLE' AND c.legal_status='IN_CIRCULATION' THEN 1 ELSE 0 END) available_count,
                  SUM(CASE WHEN c.status='RESERVED' AND c.legal_status='IN_CIRCULATION' THEN 1 ELSE 0 END) reserved_count,
                  SUM(CASE WHEN c.status='CONSUMED' AND c.legal_status='IN_CIRCULATION' THEN 1 ELSE 0 END) consumed_count,
                  SUM(CASE WHEN c.status='AVAILABLE' AND COALESCE(c.legal_status,'')<>'IN_CIRCULATION'
                    AND EXISTS (
                      SELECT 1 FROM znack_purchase_pipelines failed
                      WHERE failed.shop_id=c.shop_id AND failed.order_id=c.order_id
                        AND failed.stage='INTRODUCTION_FAILED'
                    ) THEN 1 ELSE 0 END) discardable_count,
                  ((SELECT COUNT(*) FROM znack_gtin_mapping_rules r WHERE r.shop_id=p.shop_id AND r.gtin=p.gtin)
                   + (SELECT COUNT(*) FROM ozon_product_gtin_mappings o WHERE o.shop_id=p.shop_id AND o.gtin=p.gtin)
                   + (SELECT COUNT(*) FROM ozon_article_gtin_mappings a WHERE a.shop_id=p.shop_id AND a.gtin=p.gtin)) rule_count,
                  (SELECT o.local_status FROM kiz_orders o WHERE o.shop_id=p.shop_id AND o.gtin=p.gtin ORDER BY o.updated_at DESC LIMIT 1) order_status,
                  (SELECT x.id FROM znack_purchase_pipelines x WHERE x.shop_id=p.shop_id AND x.gtin=p.gtin ORDER BY x.updated_at DESC LIMIT 1) pipeline_id,
                  (SELECT x.stage FROM znack_purchase_pipelines x WHERE x.shop_id=p.shop_id AND x.gtin=p.gtin ORDER BY x.updated_at DESC LIMIT 1) pipeline_stage,
                  COALESCE((SELECT x.error_message FROM znack_purchase_pipelines x WHERE x.shop_id=p.shop_id AND x.gtin=p.gtin ORDER BY x.updated_at DESC LIMIT 1),
                           (SELECT o.error_message FROM kiz_orders o WHERE o.shop_id=p.shop_id AND o.gtin=p.gtin ORDER BY o.updated_at DESC LIMIT 1)) latest_error,
                  p.synced_at
                FROM znack_products p
                LEFT JOIN kiz_codes c ON c.shop_id=p.shop_id AND c.gtin=p.gtin
                WHERE p.shop_id=? AND p.gtin NOT LIKE '029%' AND p.deleted_at IS NULL
                GROUP BY p.shop_id,p.gtin,p.product_name,p.category,p.synced_at
                ORDER BY CASE WHEN p.category IS NULL OR p.category='' THEN 1 ELSE 0 END,
                         p.category COLLATE NOCASE,p.gtin
                """;
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ZnackGtinInventorySummary> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(summary(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void validateNoConflicts(Connection c, int shopId, String gtin,
                                     List<ZnackGtinMappingSelection> selections) throws SQLException {
        String sql = """
                SELECT mapping.gtin FROM znack_gtin_mapping_rules mapping
                JOIN znack_products product
                  ON product.shop_id=mapping.shop_id AND product.gtin=mapping.gtin
                WHERE mapping.shop_id=? AND mapping.subject_name=? AND mapping.gtin<>?
                  AND product.deleted_at IS NULL AND product.identity_archived_at IS NULL
                  AND (mapping.wildcard_gender=1 OR ?=1 OR mapping.gender_value=?)
                LIMIT 1
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (ZnackGtinMappingSelection selection : selections) {
                ps.setInt(1, shopId);
                ps.setString(2, selection.subjectName().trim());
                ps.setString(3, gtin);
                ps.setInt(4, selection.wildcardGender() ? 1 : 0);
                ps.setString(5, selection.wildcardGender() ? WILDCARD_GENDER : normalizeGender(selection.genderValue()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new MappingConflictException();
                    }
                }
            }
        }
    }

    private void requireProduct(Connection c, int shopId, String gtin) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM znack_products WHERE shop_id=? AND gtin=? "
                        + "AND deleted_at IS NULL AND identity_archived_at IS NULL")) {
            ps.setInt(1, shopId);
            ps.setString(2, gtin);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("GTIN is not registered for the selected shop.");
            }
        }
    }

    private List<String> strings(String sql, int shopId) {
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rs.next()) result.add(rs.getString(1));
                return result;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static String normalizeGender(String value) {
        return value == null || value.isBlank() ? UNSPECIFIED_GENDER : value.trim();
    }

    private static List<ZnackGtinMappingSelection> normalizeSelections(List<ZnackGtinMappingSelection> selections) {
        Map<String, List<ZnackGtinMappingSelection>> bySubject = new LinkedHashMap<>();
        if (selections != null) {
            selections.stream()
                    .filter(s -> s != null && s.subjectName() != null && !s.subjectName().isBlank())
                    .forEach(s -> bySubject.computeIfAbsent(s.subjectName().trim(), ignored -> new ArrayList<>()).add(s));
        }
        List<ZnackGtinMappingSelection> result = new ArrayList<>();
        bySubject.forEach((subject, values) -> {
            if (values.stream().anyMatch(ZnackGtinMappingSelection::wildcardGender)) {
                result.add(new ZnackGtinMappingSelection(subject, null, true));
            } else {
                values.stream().map(value -> new ZnackGtinMappingSelection(subject,
                                normalizeGender(value.genderValue()), false))
                        .distinct().forEach(result::add);
            }
        });
        return result;
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static ZnackGtinInventorySummary summary(ResultSet rs) throws SQLException {
        return new ZnackGtinInventorySummary(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getString(9),
                nullableLong(rs, 10), rs.getString(11), rs.getString(12), instant(rs.getString(13)));
    }

    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public static final class MappingConflictException extends IllegalStateException {
        public MappingConflictException() {
            super("The mapping is already owned by another GTIN.");
        }
    }
}
