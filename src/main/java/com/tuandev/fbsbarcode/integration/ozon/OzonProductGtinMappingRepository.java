package com.tuandev.fbsbarcode.integration.ozon;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.znack.GtinNormalizer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class OzonProductGtinMappingRepository {
    public void put(int shopId, String sku, String gtin) {
        String safeSku = OzonApiClient.requireExternalId(sku, "SKU");
        String normalizedGtin = GtinNormalizer.normalize(gtin);
        String now = Instant.now().toString();
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ozon_product_gtin_mappings(shop_id,sku,gtin,created_at,updated_at)
                        VALUES(?,?,?,?,?)
                        ON CONFLICT(shop_id,sku) DO UPDATE SET gtin=excluded.gtin,updated_at=excluded.updated_at
                        """)) {
            statement.setInt(1, shopId);
            statement.setString(2, safeSku);
            statement.setString(3, normalizedGtin);
            statement.setString(4, now);
            statement.setString(5, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public String findGtin(int shopId, String sku) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT mapping.gtin FROM ozon_product_gtin_mappings mapping
                        JOIN znack_products product
                          ON product.shop_id=mapping.shop_id AND product.gtin=mapping.gtin
                        WHERE mapping.shop_id=? AND mapping.sku=?
                          AND product.deleted_at IS NULL AND product.identity_archived_at IS NULL
                        """)) {
            statement.setInt(1, shopId);
            statement.setString(2, OzonApiClient.requireExternalId(sku, "SKU"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    public Map<String, String> findAll(int shopId) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT mapping.sku,mapping.gtin FROM ozon_product_gtin_mappings mapping
                        JOIN znack_products product
                          ON product.shop_id=mapping.shop_id AND product.gtin=mapping.gtin
                        WHERE mapping.shop_id=?
                          AND product.deleted_at IS NULL AND product.identity_archived_at IS NULL
                        ORDER BY mapping.sku
                        """)) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                Map<String, String> mappings = new LinkedHashMap<>();
                while (result.next()) mappings.put(result.getString(1), result.getString(2));
                return Map.copyOf(mappings);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    /** Returns the article-level rules shown in the editor. */
    public Map<String, String> findAllArticles(int shopId) {
        Map<String, String> legacy = findActiveSkuMappings(shopId);
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT mapping.article,mapping.gtin
                        FROM ozon_article_gtin_mappings mapping
                        JOIN znack_products product
                          ON product.shop_id=mapping.shop_id AND product.gtin=mapping.gtin
                        WHERE mapping.shop_id=? AND product.deleted_at IS NULL
                          AND product.identity_archived_at IS NULL
                        ORDER BY mapping.article COLLATE NOCASE
                        """);
                PreparedStatement products = connection.prepareStatement("""
                        SELECT sku,article FROM ozon_products
                        WHERE shop_id=? AND archived=0 AND article IS NOT NULL AND trim(article)<>''
                        ORDER BY article,sku
                        """)) {
            statement.setInt(1, shopId);
            Map<String, ArticleMapping> byKey = new LinkedHashMap<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String article = result.getString(1);
                    byKey.put(articleKey(article), new ArticleMapping(article, result.getString(2)));
                }
            }
            products.setInt(1, shopId);
            Map<String, ArticleMapping> inferred = new LinkedHashMap<>();
            Set<String> ambiguous = new LinkedHashSet<>();
            try (ResultSet result = products.executeQuery()) {
                while (result.next()) {
                    String gtin = legacy.get(result.getString(1));
                    if (gtin == null) continue;
                    String article = result.getString(2);
                    String key = articleKey(article);
                    ArticleMapping previous = inferred.putIfAbsent(key, new ArticleMapping(article, gtin));
                    if (previous != null && !previous.gtin().equals(gtin)) ambiguous.add(key);
                }
            }
            ambiguous.forEach(inferred::remove);
            inferred.forEach(byKey::putIfAbsent);
            Map<String, String> values = new LinkedHashMap<>();
            byKey.values().forEach(value -> values.put(value.article(), value.gtin()));
            return Map.copyOf(values);
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    /**
     * Resolves every current catalog SKU from its article rule. A legacy per-SKU rule remains a
     * fallback so upgrades never invalidate mappings created by older WCode versions.
     */
    public Map<String, String> findResolvedBySku(int shopId) {
        Map<String, String> legacy = findActiveSkuMappings(shopId);
        Map<String, String> byArticleKey = new LinkedHashMap<>();
        try (Connection connection = Database.getConnection();
                PreparedStatement rules = connection.prepareStatement("""
                        SELECT mapping.article_key,mapping.gtin
                        FROM ozon_article_gtin_mappings mapping
                        JOIN znack_products product
                          ON product.shop_id=mapping.shop_id AND product.gtin=mapping.gtin
                        WHERE mapping.shop_id=? AND product.deleted_at IS NULL
                          AND product.identity_archived_at IS NULL
                        """);
                PreparedStatement products = connection.prepareStatement("""
                        SELECT sku,article FROM ozon_products
                        WHERE shop_id=? AND archived=0 AND sku IS NOT NULL AND trim(sku)<>''
                        ORDER BY sku
                        """)) {
            rules.setInt(1, shopId);
            try (ResultSet result = rules.executeQuery()) {
                while (result.next()) byArticleKey.put(result.getString(1), result.getString(2));
            }
            products.setInt(1, shopId);
            List<CatalogMappingRow> catalogRows = new ArrayList<>();
            try (ResultSet result = products.executeQuery()) {
                while (result.next()) {
                    catalogRows.add(new CatalogMappingRow(result.getString(1), result.getString(2)));
                }
            }
            Map<String, String> inferredByArticleKey = new LinkedHashMap<>();
            Set<String> ambiguousArticleKeys = new LinkedHashSet<>();
            for (CatalogMappingRow row : catalogRows) {
                String legacyGtin = legacy.get(row.sku());
                if (legacyGtin == null || row.article() == null || row.article().isBlank()) continue;
                String key = articleKey(row.article());
                String previous = inferredByArticleKey.putIfAbsent(key, legacyGtin);
                if (previous != null && !previous.equals(legacyGtin)) ambiguousArticleKeys.add(key);
            }
            ambiguousArticleKeys.forEach(inferredByArticleKey::remove);
            Map<String, String> resolved = new LinkedHashMap<>();
            for (CatalogMappingRow row : catalogRows) {
                String key = row.article() == null || row.article().isBlank() ? "" : articleKey(row.article());
                String gtin = key.isEmpty() ? null : byArticleKey.get(key);
                if (gtin == null && !key.isEmpty()) gtin = inferredByArticleKey.get(key);
                if (gtin == null) gtin = legacy.get(row.sku());
                if (gtin != null && !gtin.isBlank()) resolved.put(row.sku(), gtin);
            }
            // Posting data may outlive a catalog row. Preserve its legacy fallback as well.
            legacy.forEach(resolved::putIfAbsent);
            return Map.copyOf(resolved);
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    /** Atomically replaces every Ozon article assigned to one Znack GTIN. */
    public void replaceArticlesForGtin(int shopId, String gtin, List<String> articles) {
        if (shopId <= 0 || articles == null || articles.size() > 10_000) {
            throw new IllegalArgumentException("Invalid Ozon article mapping.");
        }
        String normalizedGtin = GtinNormalizer.normalize(gtin);
        Map<String, String> normalizedArticles = new LinkedHashMap<>();
        for (String article : articles) {
            String display = safeArticle(article);
            normalizedArticles.putIfAbsent(articleKey(display), display);
        }
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                requireGtin(connection, shopId, normalizedGtin);
                Set<String> catalogArticles = catalogArticleKeys(connection, shopId);
                if (!catalogArticles.containsAll(normalizedArticles.keySet())) {
                    throw new IllegalArgumentException("Article is not in the current Ozon catalog.");
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM ozon_article_gtin_mappings WHERE shop_id=? AND gtin=?")) {
                    delete.setInt(1, shopId);
                    delete.setString(2, normalizedGtin);
                    delete.executeUpdate();
                }
                String now = Instant.now().toString();
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO ozon_article_gtin_mappings(
                            shop_id,article_key,article,gtin,created_at,updated_at)
                        VALUES(?,?,?,?,?,?)
                        ON CONFLICT(shop_id,article_key) DO UPDATE SET
                            article=excluded.article,gtin=excluded.gtin,updated_at=excluded.updated_at
                        """)) {
                    for (var entry : normalizedArticles.entrySet()) {
                        insert.setInt(1, shopId);
                        insert.setString(2, entry.getKey());
                        insert.setString(3, entry.getValue());
                        insert.setString(4, normalizedGtin);
                        insert.setString(5, now);
                        insert.setString(6, now);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
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

    /** Atomically replaces every Ozon catalog SKU assigned to one Znack GTIN. */
    public void replaceForGtin(int shopId, String gtin, List<String> skus) {
        if (shopId <= 0 || skus == null || skus.size() > 10_000) {
            throw new IllegalArgumentException("Invalid Ozon catalog mapping.");
        }
        String normalizedGtin = GtinNormalizer.normalize(gtin);
        List<String> normalizedSkus = skus.stream()
                .map(sku -> OzonApiClient.requireExternalId(sku, "SKU"))
                .distinct()
                .toList();
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                requireGtin(connection, shopId, normalizedGtin);
                for (String sku : normalizedSkus) requireCatalogSku(connection, shopId, sku);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM ozon_product_gtin_mappings WHERE shop_id=? AND gtin=?")) {
                    delete.setInt(1, shopId);
                    delete.setString(2, normalizedGtin);
                    delete.executeUpdate();
                }
                String now = Instant.now().toString();
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO ozon_product_gtin_mappings(shop_id,sku,gtin,created_at,updated_at)
                        VALUES(?,?,?,?,?)
                        ON CONFLICT(shop_id,sku) DO UPDATE SET gtin=excluded.gtin,updated_at=excluded.updated_at
                        """)) {
                    for (String sku : normalizedSkus) {
                        insert.setInt(1, shopId);
                        insert.setString(2, sku);
                        insert.setString(3, normalizedGtin);
                        insert.setString(4, now);
                        insert.setString(5, now);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
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

    public int delete(int shopId, String sku) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM ozon_product_gtin_mappings WHERE shop_id=? AND sku=?")) {
            statement.setInt(1, shopId);
            statement.setString(2, OzonApiClient.requireExternalId(sku, "SKU"));
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static void requireGtin(Connection connection, int shopId, String gtin) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM znack_products WHERE shop_id=? AND gtin=? "
                        + "AND deleted_at IS NULL AND identity_archived_at IS NULL")) {
            statement.setInt(1, shopId);
            statement.setString(2, gtin);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("GTIN is not registered for this shop.");
            }
        }
    }

    private Map<String, String> findActiveSkuMappings(int shopId) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT mapping.sku,mapping.gtin
                        FROM ozon_product_gtin_mappings mapping
                        JOIN znack_products product
                          ON product.shop_id=mapping.shop_id AND product.gtin=mapping.gtin
                        WHERE mapping.shop_id=? AND product.deleted_at IS NULL
                          AND product.identity_archived_at IS NULL
                        ORDER BY mapping.sku
                        """)) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                Map<String, String> mappings = new LinkedHashMap<>();
                while (result.next()) mappings.put(result.getString(1), result.getString(2));
                return Map.copyOf(mappings);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static void requireCatalogSku(Connection connection, int shopId, String sku) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM ozon_products WHERE shop_id=? AND sku=? AND archived=0")) {
            statement.setInt(1, shopId);
            statement.setString(2, sku);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("SKU is not in the current Ozon catalog.");
            }
        }
    }

    private static Set<String> catalogArticleKeys(Connection connection, int shopId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT article FROM ozon_products
                WHERE shop_id=? AND archived=0 AND article IS NOT NULL AND trim(article)<>''
                """)) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                Set<String> values = new LinkedHashSet<>();
                while (result.next()) values.add(articleKey(result.getString(1)));
                return values;
            }
        }
    }

    static String articleKey(String value) {
        return safeArticle(value).toLowerCase(Locale.ROOT);
    }

    private static String safeArticle(String value) {
        if (value == null) throw new IllegalArgumentException("Ozon article is required.");
        String safe = value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        if (safe.isEmpty() || safe.length() > 512) {
            throw new IllegalArgumentException("Ozon article is invalid.");
        }
        return safe;
    }

    private record CatalogMappingRow(String sku, String article) {
    }

    private record ArticleMapping(String article, String gtin) {
    }
}
