package com.tuandev.fbsbarcode.features.fbo;

import com.tuandev.fbsbarcode.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class FboProductRepository {
    private static final String PRODUCT_SELECT = """
            SELECT c.nm_id, c.vendor_code, c.subject_name, c.brand, c.title,
                   COALESCE(c.need_kiz, 0) AS need_kiz,
                   COALESCE(c.kiz_marked, 0) AS kiz_marked,
                   s.tech_size, s.wb_size, sku.sku,
                   p.c246x328_url, p.square_url, p.big_url, p.hq_url, p.tm_url,
                   (SELECT COALESCE(json_extract(ch.value_json, '$[0]'), json_extract(ch.value_json, '$'))
                    FROM wb_product_characteristics ch
                    WHERE ch.shop_id = c.shop_id
                      AND ch.nm_id = c.nm_id
                      AND ch.characteristic_id IN (14177449, 204557)
                    ORDER BY CASE ch.characteristic_id
                             WHEN 14177449 THEN 0
                             WHEN 204557 THEN 1
                             ELSE 9
                             END
                    LIMIT 1) AS color_value
            FROM wb_product_cards c
            JOIN wb_product_sizes s ON s.shop_id = c.shop_id AND s.nm_id = c.nm_id
            JOIN wb_product_size_skus sku ON sku.shop_id = s.shop_id AND sku.chrt_id = s.chrt_id
            LEFT JOIN wb_product_photos p ON p.shop_id = c.shop_id AND p.nm_id = c.nm_id AND p.photo_index = 0
            WHERE c.shop_id = ?
            """;

    public List<String> findSubjects(int shopId) {
        String sql = """
                SELECT DISTINCT subject_name
                FROM wb_product_cards
                WHERE shop_id = ? AND subject_name IS NOT NULL AND TRIM(subject_name) <> ''
                ORDER BY subject_name COLLATE NOCASE
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> subjects = new ArrayList<>();
                while (rs.next()) {
                    subjects.add(rs.getString("subject_name"));
                }
                return subjects;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<FboProductSku> search(FboProductSearchCriteria criteria) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(PRODUCT_SELECT);
        params.add(criteria.shopId());

        String query = criteria.query() == null ? "" : criteria.query().trim();
        if (!query.isBlank()) {
            sql.append("""
                    AND (
                        CAST(c.nm_id AS TEXT) LIKE ?
                        OR LOWER(c.vendor_code) LIKE ?
                        OR LOWER(sku.sku) LIKE ?
                    )
                    """);
            String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }

        List<String> subjects = criteria.subjectNames() == null ? List.of() : criteria.subjectNames().stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        if (!subjects.isEmpty()) {
            sql.append(" AND c.subject_name IN (")
                    .append(String.join(", ", Collections.nCopies(subjects.size(), "?")))
                    .append(")");
            params.addAll(subjects);
        }

        sql.append("""
                ORDER BY c.title COLLATE NOCASE, c.nm_id, s.tech_size COLLATE NOCASE, sku.sku
                LIMIT ? OFFSET ?
                """);
        params.add(Math.max(1, criteria.limit()));
        params.add(Math.max(0, criteria.offset()));

        return query(sql.toString(), params);
    }

    public List<FboProductSku> findBySkus(int shopId, List<String> skus) {
        List<String> values = skus == null ? List.of() : skus.stream().distinct().toList();
        if (shopId <= 0 || values.isEmpty()) {
            return List.of();
        }
        String sql = PRODUCT_SELECT + " AND sku.sku IN ("
                + String.join(", ", Collections.nCopies(values.size(), "?"))
                + ") ORDER BY sku.sku";
        List<Object> params = new ArrayList<>();
        params.add(shopId);
        params.addAll(values);
        return query(sql, params);
    }

    private List<FboProductSku> query(String sql, List<Object> params) {
        try (Connection conn = Database.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<FboProductSku> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(mapProduct(rs));
                }
                return items;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static FboProductSku mapProduct(ResultSet rs) throws SQLException {
        return new FboProductSku(
                rs.getLong("nm_id"),
                rs.getString("vendor_code"),
                rs.getString("subject_name"),
                rs.getString("brand"),
                rs.getString("title"),
                rs.getString("color_value"),
                firstNonBlank(rs.getString("tech_size"), rs.getString("wb_size")),
                firstNonBlank(rs.getString("wb_size")),
                rs.getString("sku"),
                firstNonBlank(
                        rs.getString("c246x328_url"),
                        rs.getString("square_url"),
                        rs.getString("big_url"),
                        rs.getString("hq_url"),
                        rs.getString("tm_url")),
                rs.getInt("need_kiz") > 0 || rs.getInt("kiz_marked") > 0);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

}
