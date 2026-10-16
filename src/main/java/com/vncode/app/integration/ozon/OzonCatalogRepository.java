package com.vncode.app.integration.ozon;

import com.vncode.app.config.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class OzonCatalogRepository {
    private final OzonSyncStateRepository syncState;

    public java.util.Map<String,OzonProductDto> findBySkus(int shop,java.util.Collection<String> skus){try(var c=Database.getConnection()){return findBySkus(c,shop,skus);}catch(SQLException e){throw new IllegalStateException("Cannot load page products");}}
    public java.util.Map<String,OzonProductDto> findBySkus(Connection c,int shop,java.util.Collection<String> skus)throws SQLException{
        var keys=skus.stream().filter(s->s!=null&&!s.isBlank()).distinct().toList();if(keys.size()>10000)throw new IllegalArgumentException("Too many page SKUs");var out=new java.util.LinkedHashMap<String,OzonProductDto>();var ambiguous=new java.util.HashSet<String>();
        for(int start=0;start<keys.size();start+=500){var batch=keys.subList(start,Math.min(start+500,keys.size()));String marks=String.join(",",java.util.Collections.nCopies(batch.size(),"?"));
            try(var p=c.prepareStatement("SELECT p.*,coalesce((SELECT json_group_array(b.barcode) FROM ozon_product_barcodes b WHERE b.shop_id=p.shop_id AND b.product_id=p.product_id),'[]') AS barcodes FROM ozon_products p WHERE p.shop_id=? AND p.archived=0 AND p.sku IN ("+marks+") ORDER BY p.product_id")){p.setInt(1,shop);int n=2;for(String key:batch)p.setString(n++,key);try(var r=p.executeQuery()){while(r.next()){var barcodes=com.google.gson.JsonParser.parseString(r.getString("barcodes")).getAsJsonArray().asList().stream().map(com.google.gson.JsonElement::getAsString).sorted().toList();var value=new OzonProductDto(r.getString("product_id"),r.getString("offer_id"),r.getString("sku"),r.getString("name"),r.getString("primary_image_url"),r.getString("article"),r.getString("color"),r.getString("size"),r.getString("category"),r.getString("gender"),r.getBoolean("archived"),r.getString("updated_at"),barcodes,r.getString("description_category_id"),r.getString("type_id"),r.getString("manufacturer_size"),r.getString("russian_size"));if(out.putIfAbsent(value.sku(),value)!=null)ambiguous.add(value.sku());}}}
        }ambiguous.forEach(out::remove);return java.util.Map.copyOf(out);
    }

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
                                    article,color,size,category,gender,archived,updated_at,synced_at,description_category_id,type_id,manufacturer_size,russian_size)
                                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                                ON CONFLICT(shop_id,product_id) DO UPDATE SET offer_id=excluded.offer_id,
                                    sku=excluded.sku,name=excluded.name,primary_image_url=excluded.primary_image_url,
                                    article=excluded.article,color=excluded.color,size=excluded.size,
                                    category=excluded.category,gender=excluded.gender,
                                    archived=excluded.archived,updated_at=excluded.updated_at,synced_at=excluded.synced_at,description_category_id=excluded.description_category_id,type_id=excluded.type_id,manufacturer_size=excluded.manufacturer_size,russian_size=excluded.russian_size
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
                        product.setString(15,item.descriptionCategoryId());product.setString(16,item.typeId());
                        product.setString(17,item.manufacturerSize());product.setString(18,item.russianSize());
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
                               archived,updated_at,description_category_id,type_id,manufacturer_size,russian_size
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
                            barcodes(connection, shopId, productId),result.getString(13),result.getString(14),result.getString(15),result.getString(16)));
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
