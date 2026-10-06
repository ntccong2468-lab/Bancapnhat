package com.tuandev.fbsbarcode.integration.ozon;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.fbo.FboProductSearchCriteria;
import com.tuandev.fbsbarcode.features.fbo.FboProductSku;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Provides one printable FBO row for each active Ozon catalog SKU. */
public final class OzonFboProductRepository {
    public List<String> findCategories(int shopId) {
        if (shopId <= 0) return List.of();
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT DISTINCT TRIM(category)
                        FROM ozon_products
                        WHERE shop_id=? AND archived=0
                          AND category IS NOT NULL AND TRIM(category)<>''
                        ORDER BY TRIM(category) COLLATE NOCASE
                        """)) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                List<String> categories = new ArrayList<>();
                while (result.next()) categories.add(result.getString(1));
                return List.copyOf(categories);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public List<FboProductSku> search(FboProductSearchCriteria criteria) {
        if (criteria == null || criteria.shopId() <= 0) {
            return List.of();
        }
        List<Object> parameters = new ArrayList<>();
        parameters.add(criteria.shopId());
        StringBuilder sql = new StringBuilder("""
                SELECT p.product_id,p.offer_id,p.sku AS catalog_sku,p.name,p.primary_image_url,
                       p.article,p.color,p.size,p.category,
                       COALESCE((
                           SELECT b.barcode FROM ozon_product_barcodes b
                           WHERE b.shop_id=p.shop_id AND b.product_id=p.product_id
                           ORDER BY b.barcode LIMIT 1
                       ),p.sku) AS print_barcode,
                       CASE WHEN policy.requires_kiz=0 THEN 0 ELSE 1 END AS requires_kiz
                FROM ozon_products p
                LEFT JOIN ozon_product_kiz_policies policy
                  ON policy.shop_id=p.shop_id AND policy.sku=p.sku
                WHERE p.shop_id=? AND p.archived=0
                """);

        String query = criteria.query() == null ? "" : criteria.query().trim();
        if (!query.isBlank()) {
            String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
            sql.append("""
                    AND (
                        LOWER(p.product_id) LIKE ? OR LOWER(p.offer_id) LIKE ?
                        OR LOWER(p.sku) LIKE ? OR LOWER(p.name) LIKE ?
                        OR LOWER(p.article) LIKE ? OR EXISTS (
                            SELECT 1 FROM ozon_product_barcodes search_barcode
                            WHERE search_barcode.shop_id=p.shop_id
                              AND search_barcode.product_id=p.product_id
                              AND LOWER(search_barcode.barcode) LIKE ?
                        )
                    )
                    """);
            for (int i = 0; i < 6; i++) parameters.add(like);
        }
        List<String> categories = criteria.subjectNames() == null ? List.of()
                : criteria.subjectNames().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (!categories.isEmpty()) {
            sql.append(" AND TRIM(p.category) IN (")
                    .append(String.join(", ", Collections.nCopies(categories.size(), "?")))
                    .append(")");
            parameters.addAll(categories);
        }
        sql.append("""
                ORDER BY p.name COLLATE NOCASE,p.article COLLATE NOCASE,
                         p.color COLLATE NOCASE,p.size COLLATE NOCASE,p.sku
                LIMIT ? OFFSET ?
                """);
        parameters.add(Math.max(1, criteria.limit()));
        parameters.add(Math.max(0, criteria.offset()));

        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i));
            }
            try (ResultSet result = statement.executeQuery()) {
                List<FboProductSku> products = new ArrayList<>();
                while (result.next()) products.add(map(result));
                return List.copyOf(products);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static FboProductSku map(ResultSet result) throws SQLException {
        String productId = safe(result.getString("product_id"));
        String name = safe(result.getString("name"));
        String article = first(result.getString("article"), result.getString("offer_id"));
        String size = safe(result.getString("size"));
        String barcode = first(result.getString("print_barcode"), result.getString("catalog_sku"));
        return new FboProductSku(
                numericId(productId), article, safe(result.getString("category")), "", name,
                safe(result.getString("color")), size, size, barcode,
                safe(result.getString("primary_image_url")), result.getBoolean("requires_kiz"),
                safe(result.getString("catalog_sku")));
    }

    private static long numericId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return Integer.toUnsignedLong(value.hashCode());
        }
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? safe(fallback) : preferred.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
