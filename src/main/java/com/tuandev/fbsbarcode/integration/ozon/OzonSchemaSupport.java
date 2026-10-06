package com.tuandev.fbsbarcode.integration.ozon;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Additive Ozon FBS schema. It deliberately stores no buyer PII and no raw API responses. */
public final class OzonSchemaSupport {
    private OzonSchemaSupport() {
    }

    public static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_sync_state(
                        shop_id INTEGER PRIMARY KEY,
                        products_last_id TEXT,
                        products_last_synced_at TEXT,
                        postings_changed_since TEXT,
                        postings_last_synced_at TEXT,
                        last_error TEXT,
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_products(
                        shop_id INTEGER NOT NULL,
                        product_id TEXT NOT NULL,
                        offer_id TEXT,
                        sku TEXT,
                        name TEXT,
                        primary_image_url TEXT,
                        article TEXT,
                        color TEXT,
                        size TEXT,
                        category TEXT,
                        gender TEXT,
                        archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1)),
                        updated_at TEXT,
                        synced_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id,product_id),
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);
            boolean hasArticle = columnExists(connection, "ozon_products", "article");
            boolean hasColor = columnExists(connection, "ozon_products", "color");
            boolean hasSize = columnExists(connection, "ozon_products", "size");
            boolean hasCategory = columnExists(connection, "ozon_products", "category");
            boolean hasGender = columnExists(connection, "ozon_products", "gender");
            if (!hasArticle || !hasColor || !hasSize || !hasCategory || !hasGender) {
                // Reset before altering so an interrupted migration still forces a complete card refresh.
                statement.execute("UPDATE ozon_sync_state "
                        + "SET products_last_id=NULL, products_last_synced_at=NULL");
            }
            if (!hasArticle) {
                statement.execute("ALTER TABLE ozon_products ADD COLUMN article TEXT");
            }
            if (!hasColor) {
                statement.execute("ALTER TABLE ozon_products ADD COLUMN color TEXT");
            }
            if (!hasSize) {
                statement.execute("ALTER TABLE ozon_products ADD COLUMN size TEXT");
            }
            if (!hasCategory) {
                statement.execute("ALTER TABLE ozon_products ADD COLUMN category TEXT");
            }
            if (!hasGender) {
                statement.execute("ALTER TABLE ozon_products ADD COLUMN gender TEXT");
            }
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_product_barcodes(
                        shop_id INTEGER NOT NULL,
                        product_id TEXT NOT NULL,
                        barcode TEXT NOT NULL,
                        PRIMARY KEY(shop_id,product_id,barcode),
                        FOREIGN KEY(shop_id,product_id) REFERENCES ozon_products(shop_id,product_id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_article_gtin_mappings(
                        shop_id INTEGER NOT NULL,
                        article_key TEXT NOT NULL,
                        article TEXT NOT NULL,
                        gtin TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id,article_key),
                        FOREIGN KEY(shop_id,gtin) REFERENCES znack_products(shop_id,gtin) ON DELETE CASCADE,
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_product_gtin_mappings(
                        shop_id INTEGER NOT NULL,
                        sku TEXT NOT NULL,
                        gtin TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id,sku),
                        FOREIGN KEY(shop_id,gtin) REFERENCES znack_products(shop_id,gtin) ON DELETE CASCADE,
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_product_kiz_policies(
                        shop_id INTEGER NOT NULL,
                        sku TEXT NOT NULL,
                        requires_kiz INTEGER NOT NULL DEFAULT 1 CHECK(requires_kiz IN (0,1)),
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id,sku),
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_postings(
                        shop_id INTEGER NOT NULL,
                        posting_number TEXT NOT NULL,
                        order_id TEXT,
                        order_number TEXT,
                        status TEXT NOT NULL,
                        substatus TEXT,
                        warehouse_id TEXT,
                        shipment_at TEXT,
                        in_process_at TEXT,
                        lower_barcode TEXT,
                        upper_barcode TEXT,
                        mandatory_mark_product_ids TEXT NOT NULL DEFAULT '[]',
                        optional_mark_product_ids TEXT NOT NULL DEFAULT '[]',
                        unsupported_requirements TEXT NOT NULL DEFAULT '[]',
                        available_actions TEXT NOT NULL DEFAULT '[]',
                        ship_available INTEGER NOT NULL DEFAULT 0 CHECK(ship_available IN (0,1)),
                        synced_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id,posting_number),
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_posting_items(
                        shop_id INTEGER NOT NULL,
                        posting_number TEXT NOT NULL,
                        item_index INTEGER NOT NULL CHECK(item_index >= 0),
                        product_id TEXT,
                        sku TEXT,
                        offer_id TEXT,
                        name TEXT,
                        quantity INTEGER NOT NULL CHECK(quantity > 0),
                        currency_code TEXT,
                        price TEXT,
                        PRIMARY KEY(shop_id,posting_number,item_index),
                        FOREIGN KEY(shop_id,posting_number) REFERENCES ozon_postings(shop_id,posting_number) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_exemplar_jobs(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        shop_id INTEGER NOT NULL,
                        posting_number TEXT NOT NULL,
                        stage TEXT NOT NULL CHECK(stage IN (
                            'CREATED','RESERVED','VALIDATED','SET_PENDING','VERIFYING','ACCEPTED','REJECTED','RECONCILE_REQUIRED')),
                        request_fingerprint TEXT,
                        safe_error_code TEXT,
                        mutation_attempted_at TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        UNIQUE(shop_id,posting_number),
                        FOREIGN KEY(shop_id,posting_number) REFERENCES ozon_postings(shop_id,posting_number) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_exemplars(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        job_id INTEGER NOT NULL,
                        shop_id INTEGER NOT NULL,
                        posting_number TEXT NOT NULL,
                        item_index INTEGER NOT NULL,
                        product_id TEXT,
                        exemplar_id TEXT,
                        exemplar_index INTEGER NOT NULL CHECK(exemplar_index >= 0),
                        kiz_id INTEGER,
                        check_status TEXT,
                        weight TEXT,
                        updated_at TEXT NOT NULL,
                        UNIQUE(job_id,item_index,exemplar_index),
                        UNIQUE(kiz_id),
                        FOREIGN KEY(job_id) REFERENCES ozon_exemplar_jobs(id) ON DELETE CASCADE,
                        FOREIGN KEY(shop_id,posting_number,item_index)
                            REFERENCES ozon_posting_items(shop_id,posting_number,item_index) ON DELETE CASCADE,
                        FOREIGN KEY(kiz_id) REFERENCES kiz_codes(id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_action_log(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        shop_id INTEGER NOT NULL,
                        action_type TEXT NOT NULL,
                        posting_number TEXT,
                        status TEXT NOT NULL,
                        safe_error_code TEXT,
                        request_fingerprint TEXT,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_label_jobs(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        shop_id INTEGER NOT NULL,
                        posting_number TEXT NOT NULL,
                        task_id TEXT,
                        status TEXT NOT NULL,
                        output_path TEXT,
                        safe_error_code TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        UNIQUE(shop_id,posting_number),
                        FOREIGN KEY(shop_id,posting_number) REFERENCES ozon_postings(shop_id,posting_number) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_fbo_orders(
                        shop_id INTEGER NOT NULL,
                        order_id TEXT NOT NULL,
                        order_number TEXT,
                        state TEXT NOT NULL,
                        created_date TEXT,
                        state_updated_date TEXT,
                        data_filling_deadline TEXT,
                        drop_off_warehouse_id TEXT,
                        drop_off_warehouse_name TEXT,
                        drop_off_warehouse_address TEXT,
                        timeslot_from TEXT,
                        timeslot_to TEXT,
                        timezone_name TEXT,
                        is_virtual INTEGER,
                        is_pickup INTEGER,
                        is_econom INTEGER,
                        is_quant INTEGER,
                        is_super_fbo INTEGER,
                        synced_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id,order_id),
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_fbo_supplies(
                        shop_id INTEGER NOT NULL,
                        order_id TEXT NOT NULL,
                        supply_id TEXT NOT NULL,
                        bundle_id TEXT,
                        state TEXT,
                        is_crossdock INTEGER,
                        macrolocal_cluster_id TEXT,
                        storage_warehouse_id TEXT,
                        storage_warehouse_name TEXT,
                        storage_warehouse_address TEXT,
                        marking_required INTEGER,
                        ettn_required INTEGER,
                        PRIMARY KEY(shop_id,order_id,supply_id),
                        FOREIGN KEY(shop_id,order_id)
                            REFERENCES ozon_fbo_orders(shop_id,order_id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_fbo_supply_items(
                        shop_id INTEGER NOT NULL,
                        order_id TEXT NOT NULL,
                        supply_id TEXT NOT NULL,
                        item_key TEXT NOT NULL,
                        sku TEXT,
                        product_id TEXT,
                        offer_id TEXT,
                        barcode TEXT,
                        name TEXT,
                        image_url TEXT,
                        quantity INTEGER NOT NULL DEFAULT 0,
                        quant INTEGER,
                        volume_litres REAL,
                        shipment_type TEXT,
                        placement_zone TEXT,
                        PRIMARY KEY(shop_id,order_id,supply_id,item_key),
                        FOREIGN KEY(shop_id,order_id,supply_id)
                            REFERENCES ozon_fbo_supplies(shop_id,order_id,supply_id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ozon_fbo_sync_state(
                        shop_id INTEGER PRIMARY KEY,
                        list_cursor TEXT,
                        last_synced_at TEXT,
                        last_error TEXT,
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE
                    )
                    """);

            statement.execute("CREATE INDEX IF NOT EXISTS idx_shops_marketplace ON shops(marketplace,id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ozon_products_shop_sku ON ozon_products(shop_id,sku)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ozon_products_shop_article ON ozon_products(shop_id,article)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ozon_article_gtin ON ozon_article_gtin_mappings(shop_id,gtin)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ozon_postings_shop_status ON ozon_postings(shop_id,status,shipment_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ozon_posting_items_shop_sku ON ozon_posting_items(shop_id,sku)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ozon_exemplar_jobs_stage ON ozon_exemplar_jobs(shop_id,stage,updated_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ozon_action_log_shop_created ON ozon_action_log(shop_id,created_at DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ozon_fbo_orders_state ON ozon_fbo_orders(shop_id,state,state_updated_date DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ozon_fbo_supplies_order ON ozon_fbo_supplies(shop_id,order_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ozon_fbo_items_order ON ozon_fbo_supply_items(shop_id,order_id,supply_id)");
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (var statement = connection.prepareStatement("PRAGMA table_info(" + table + ")");
                var result = statement.executeQuery()) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) return true;
            }
            return false;
        }
    }
}
