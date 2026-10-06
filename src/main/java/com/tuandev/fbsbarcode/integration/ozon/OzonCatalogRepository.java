package com.tuandev.fbsbarcode.integration.ozon;

import com.tuandev.fbsbarcode.config.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class OzonCatalogRepository {
    private final OzonSyncStateRepository syncState;

    public OzonCatalogRepository() {
        this(new OzonSyncStateRepository());
    }

    OzonCatalogRepository(OzonSyncStateRepository syncState) {
        this.syncState = syncState;
    }

    /** Product rows, barcodes and cursor are committed together. */
    public int upsertPage(int shopId, List<OzonProductDto> products, String nextLastId) {
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String now = Instant.now().toString();
                try (PreparedStatement product = connection.prepareStatement("""
                                INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,primary_image_url,
                                    article,color,size,category,gender,archived,updated_at,synced_at)
                                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                                ON CONFLICT(shop_id,product_id) DO UPDATE SET offer_id=excluded.offer_id,
                                    sku=excluded.sku,name=excluded.name,primary_image_url=excluded.primary_image_url,
                                    article=excluded.article,color=excluded.color,size=excluded.size,
                                    category=excluded.category,gender=excluded.gender,
                                    archived=excluded.archived,updated_at=excluded.updated_at,synced_at=excluded.synced_at
                                """);
                        PreparedStatement clearBarcodes = connection.prepareStatement(
                                "DELETE FROM ozon_product_barcodes WHERE shop_id=? AND product_id=?");
                        PreparedStatement barcode = connection.prepareStatement("""
                                INSERT OR IGNORE INTO ozon_product_barcodes(shop_id,product_id,barcode) VALUES(?,?,?)
                                """)) {
                    for (OzonProductDto item : products) {
                        product.setInt(1, shopId);
                        product.setString(2, item.productId());
                        product.setString(3, item.offerId());
                        product.setString(4, item.sku());
                        product.setString(5, item.name());
                        product.setString(6, item.primaryImageUrl());
                        product.setString(7, item.article());
                        product.setString(8, item.color());
                        product.setString(9, item.size());
                        product.setString(10, item.category());
                        product.setString(11, item.gender());
                        product.setInt(12, item.archived() ? 1 : 0);
                        product.setString(13, item.updatedAt());
                        product.setString(14, now);
                        product.addBatch();
                    }
                    product.executeBatch();
                    for (OzonProductDto item : products) {
                        clearBarcodes.setInt(1, shopId);
                        clearBarcodes.setString(2, item.productId());
                        clearBarcodes.executeUpdate();
                        for (String value : item.barcodes()) {
                            barcode.setInt(1, shopId);
                            barcode.setString(2, item.productId());
                            barcode.setString(3, value);
                            barcode.addBatch();
                        }
                    }
                    barcode.executeBatch();
                }
                syncState.advanceProducts(connection, shopId, nextLastId);
                connection.commit();
                return products.size();
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

    public List<OzonProductDto> findAll(int shopId) {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT product_id,offer_id,sku,name,primary_image_url,article,color,size,category,gender,
                               archived,updated_at
                        FROM ozon_products WHERE shop_id=? ORDER BY name,product_id
                        """)) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                List<OzonProductDto> products = new ArrayList<>();
                while (result.next()) {
                    String productId = result.getString(1);
                    products.add(new OzonProductDto(productId, result.getString(2), result.getString(3),
                            result.getString(4), result.getString(5), result.getString(6), result.getString(7),
                            result.getString(8), result.getString(9), result.getString(10),
                            result.getBoolean(11), result.getString(12),
                            barcodes(connection, shopId, productId)));
                }
                return List.copyOf(products);
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static List<String> barcodes(Connection connection, int shopId, String productId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT barcode FROM ozon_product_barcodes WHERE shop_id=? AND product_id=? ORDER BY barcode")) {
            statement.setInt(1, shopId);
            statement.setString(2, productId);
            try (ResultSet result = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (result.next()) values.add(result.getString(1));
                return List.copyOf(values);
            }
        }
    }
}
