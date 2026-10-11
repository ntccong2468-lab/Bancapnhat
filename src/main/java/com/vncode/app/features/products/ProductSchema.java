package com.vncode.app.features.products;

import java.sql.*;
public final class ProductSchema {
    private ProductSchema(){}
    public static void ensureTables(Connection c)throws SQLException {
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS product_snapshots(shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,marketplace TEXT NOT NULL,card_id TEXT NOT NULL,variant_id TEXT NOT NULL,payload TEXT NOT NULL,checked_at TEXT NOT NULL,PRIMARY KEY(shop_id,marketplace,card_id,variant_id))");
            s.execute("CREATE TABLE IF NOT EXISTS product_documents(shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,marketplace TEXT NOT NULL,card_id TEXT NOT NULL,document_id TEXT NOT NULL,state TEXT NOT NULL,valid_until TEXT,source TEXT,verified_at TEXT,payload TEXT NOT NULL,PRIMARY KEY(shop_id,marketplace,card_id,document_id))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_product_documents_scope_state ON product_documents(shop_id,marketplace,card_id,state)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_wb_product_title ON wb_product_cards(shop_id,title,nm_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_ozon_product_title ON ozon_products(shop_id,archived,name,product_id)");
        }
    }
}
