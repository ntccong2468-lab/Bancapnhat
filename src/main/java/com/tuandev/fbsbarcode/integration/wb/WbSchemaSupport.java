package com.tuandev.fbsbarcode.integration.wb;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class WbSchemaSupport {
    private WbSchemaSupport() {
    }

    public static void initialize(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_product_cards(
                    shop_id INTEGER NOT NULL,
                    nm_id INTEGER NOT NULL,
                    imt_id INTEGER,
                    nm_uuid TEXT,
                    subject_id INTEGER,
                    subject_name TEXT,
                    vendor_code TEXT,
                    kiz_marked INTEGER,
                    need_kiz INTEGER,
                    brand TEXT,
                    title TEXT,
                    description TEXT,
                    video_url TEXT,
                    is_swatch_try_on INTEGER,
                    wholesale_enabled INTEGER,
                    wholesale_quantum INTEGER,
                    dimension_length REAL,
                    dimension_width REAL,
                    dimension_height REAL,
                    dimension_weight_brutto REAL,
                    dimension_is_valid INTEGER,
                    created_at TEXT,
                    updated_at TEXT,
                    synced_at TEXT NOT NULL,
                    PRIMARY KEY (shop_id, nm_id),
                    FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_product_photos(
                    shop_id INTEGER NOT NULL,
                    nm_id INTEGER NOT NULL,
                    photo_index INTEGER NOT NULL,
                    big_url TEXT,
                    c246x328_url TEXT,
                    c516x688_url TEXT,
                    hq_url TEXT,
                    square_url TEXT,
                    tm_url TEXT,
                    PRIMARY KEY (shop_id, nm_id, photo_index),
                    FOREIGN KEY (shop_id, nm_id) REFERENCES wb_product_cards(shop_id, nm_id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_product_sizes(
                    shop_id INTEGER NOT NULL,
                    chrt_id INTEGER NOT NULL,
                    nm_id INTEGER NOT NULL,
                    tech_size TEXT,
                    wb_size TEXT,
                    PRIMARY KEY (shop_id, chrt_id),
                    FOREIGN KEY (shop_id, nm_id) REFERENCES wb_product_cards(shop_id, nm_id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_product_size_skus(
                    shop_id INTEGER NOT NULL,
                    chrt_id INTEGER NOT NULL,
                    sku TEXT NOT NULL,
                    PRIMARY KEY (shop_id, chrt_id, sku),
                    FOREIGN KEY (shop_id, chrt_id) REFERENCES wb_product_sizes(shop_id, chrt_id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_product_characteristics(
                    shop_id INTEGER NOT NULL,
                    nm_id INTEGER NOT NULL,
                    characteristic_id INTEGER NOT NULL,
                    name TEXT,
                    value_json TEXT,
                    PRIMARY KEY (shop_id, nm_id, characteristic_id),
                    FOREIGN KEY (shop_id, nm_id) REFERENCES wb_product_cards(shop_id, nm_id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_product_tags(
                    shop_id INTEGER NOT NULL,
                    nm_id INTEGER NOT NULL,
                    tag_id INTEGER NOT NULL,
                    name TEXT,
                    color TEXT,
                    PRIMARY KEY (shop_id, nm_id, tag_id),
                    FOREIGN KEY (shop_id, nm_id) REFERENCES wb_product_cards(shop_id, nm_id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_supplies(
                    shop_id INTEGER NOT NULL,
                    supply_id TEXT NOT NULL,
                    is_b2b INTEGER,
                    done INTEGER,
                    order_count INTEGER,
                    created_at TEXT,
                    closed_at TEXT,
                    scan_dt TEXT,
                    reject_dt TEXT,
                    name TEXT,
                    cargo_type INTEGER,
                    cross_border_type INTEGER,
                    destination_office_id INTEGER,
                    recommended_wh_id INTEGER,
                    synced_at TEXT NOT NULL,
                    PRIMARY KEY (shop_id, supply_id),
                    FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_orders(
                    shop_id INTEGER NOT NULL,
                    order_id INTEGER NOT NULL,
                    order_uid TEXT,
                    rid TEXT,
                    supply_id TEXT,
                    delivery_type TEXT,
                    ddate TEXT,
                    seller_date TEXT,
                    comment TEXT,
                    user_id TEXT,
                    article TEXT,
                    color_code TEXT,
                    warehouse_id INTEGER,
                    office_id INTEGER,
                    nm_id INTEGER,
                    chrt_id INTEGER,
                    price INTEGER,
                    final_price INTEGER,
                    sale_price INTEGER,
                    converted_price INTEGER,
                    converted_final_price INTEGER,
                    currency_code INTEGER,
                    converted_currency_code INTEGER,
                    cargo_type INTEGER,
                    cross_border_type INTEGER,
                    scan_price INTEGER,
                    is_zero_order INTEGER,
                    is_b2b INTEGER,
                    created_at TEXT,
                    synced_at TEXT NOT NULL,
                    supplier_status TEXT,
                    wb_status TEXT,
                    is_cancellable INTEGER,
                    status_synced_at TEXT,
                    address_full TEXT,
                    address_longitude REAL,
                    address_latitude REAL,
                    PRIMARY KEY (shop_id, order_id),
                    FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_order_offices(
                    shop_id INTEGER NOT NULL,
                    order_id INTEGER NOT NULL,
                    office_index INTEGER NOT NULL,
                    office_name TEXT,
                    PRIMARY KEY (shop_id, order_id, office_index),
                    FOREIGN KEY (shop_id, order_id) REFERENCES wb_orders(shop_id, order_id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_order_skus(
                    shop_id INTEGER NOT NULL,
                    order_id INTEGER NOT NULL,
                    sku TEXT NOT NULL,
                    PRIMARY KEY (shop_id, order_id, sku),
                    FOREIGN KEY (shop_id, order_id) REFERENCES wb_orders(shop_id, order_id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_order_meta_requirements(
                    shop_id INTEGER NOT NULL,
                    order_id INTEGER NOT NULL,
                    meta_key TEXT NOT NULL,
                    requirement_type TEXT NOT NULL,
                    PRIMARY KEY (shop_id, order_id, meta_key, requirement_type),
                    FOREIGN KEY (shop_id, order_id) REFERENCES wb_orders(shop_id, order_id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_supply_orders(
                    shop_id INTEGER NOT NULL,
                    supply_id TEXT NOT NULL,
                    order_id INTEGER NOT NULL,
                    PRIMARY KEY (shop_id, supply_id, order_id),
                    FOREIGN KEY (shop_id, supply_id) REFERENCES wb_supplies(shop_id, supply_id) ON DELETE CASCADE,
                    FOREIGN KEY (shop_id, order_id) REFERENCES wb_orders(shop_id, order_id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_sync_runs(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    shop_id INTEGER NOT NULL,
                    resource_type TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    finished_at TEXT,
                    success INTEGER,
                    items_read INTEGER NOT NULL DEFAULT 0,
                    items_written INTEGER NOT NULL DEFAULT 0,
                    error_code TEXT,
                    error_message TEXT,
                    FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_action_log(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    shop_id INTEGER NOT NULL,
                    action_type TEXT NOT NULL,
                    supply_id TEXT,
                    order_ids TEXT,
                    status TEXT NOT NULL,
                    request_json TEXT,
                    response_json TEXT,
                    error_message TEXT,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_fbw_orders(
                    shop_id INTEGER NOT NULL,
                    preorder_id TEXT NOT NULL,
                    supply_id TEXT,
                    status_id INTEGER,
                    box_type_id INTEGER,
                    virtual_type_id INTEGER,
                    create_date TEXT,
                    supply_date TEXT,
                    fact_date TEXT,
                    updated_date TEXT,
                    warehouse_id TEXT,
                    warehouse_name TEXT,
                    actual_warehouse_id TEXT,
                    actual_warehouse_name TEXT,
                    transit_warehouse_id TEXT,
                    transit_warehouse_name TEXT,
                    quantity INTEGER NOT NULL DEFAULT 0,
                    accepted_quantity INTEGER NOT NULL DEFAULT 0,
                    unloading_quantity INTEGER NOT NULL DEFAULT 0,
                    ready_for_sale_quantity INTEGER NOT NULL DEFAULT 0,
                    depersonalized_quantity INTEGER NOT NULL DEFAULT 0,
                    acceptance_cost INTEGER,
                    acceptance_coefficient TEXT,
                    storage_coefficient TEXT,
                    delivery_coefficient TEXT,
                    reject_reason TEXT,
                    synced_at TEXT NOT NULL,
                    detail_synced_at TEXT,
                    PRIMARY KEY(shop_id,preorder_id),
                    FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_fbw_order_items(
                    shop_id INTEGER NOT NULL,
                    preorder_id TEXT NOT NULL,
                    item_key TEXT NOT NULL,
                    barcode TEXT,
                    vendor_code TEXT,
                    nm_id TEXT,
                    need_kiz INTEGER,
                    tnved TEXT,
                    tech_size TEXT,
                    color TEXT,
                    quantity INTEGER NOT NULL DEFAULT 0,
                    accepted_quantity INTEGER NOT NULL DEFAULT 0,
                    unloading_quantity INTEGER NOT NULL DEFAULT 0,
                    ready_for_sale_quantity INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(shop_id,preorder_id,item_key),
                    FOREIGN KEY(shop_id,preorder_id)
                        REFERENCES wb_fbw_orders(shop_id,preorder_id) ON DELETE CASCADE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS wb_fbw_sync_state(
                    shop_id INTEGER PRIMARY KEY,
                    last_synced_at TEXT,
                    last_error TEXT,
                    FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE
                )
            """);
        }

        ensureShopColumn(conn, "wb_products_cursor_updated_at", "TEXT");
        ensureShopColumn(conn, "wb_products_cursor_nm_id", "INTEGER");
        ensureShopColumn(conn, "wb_products_last_synced_at", "TEXT");
        ensureShopColumn(conn, "wb_supplies_next", "INTEGER NOT NULL DEFAULT 0");
        ensureShopColumn(conn, "wb_supplies_last_synced_at", "TEXT");
        ensureShopColumn(conn, "wb_orders_next", "INTEGER NOT NULL DEFAULT 0");
        ensureShopColumn(conn, "wb_orders_last_synced_at", "TEXT");
        ensureShopColumn(conn, "wb_orders_window_from", "INTEGER");
        ensureShopColumn(conn, "wb_orders_window_to", "INTEGER");
        ensureShopColumn(conn, "wb_last_sync_error", "TEXT");
        ensureColumn(conn, "wb_product_cards", "is_swatch_try_on", "INTEGER");
        ensureColumn(conn, "wb_product_cards", "wholesale_enabled", "INTEGER");
        ensureColumn(conn, "wb_product_cards", "wholesale_quantum", "INTEGER");
        ensureColumn(conn, "wb_product_cards", "dimension_length", "REAL");
        ensureColumn(conn, "wb_product_cards", "dimension_width", "REAL");
        ensureColumn(conn, "wb_product_cards", "dimension_height", "REAL");
        ensureColumn(conn, "wb_product_cards", "dimension_weight_brutto", "REAL");
        ensureColumn(conn, "wb_product_cards", "dimension_is_valid", "INTEGER");
        ensureColumn(conn, "wb_product_photos", "hq_url", "TEXT");
        ensureColumn(conn, "wb_product_sizes", "wb_size", "TEXT");
        ensureColumn(conn, "wb_supplies", "closed_at", "TEXT");
        ensureColumn(conn, "wb_supplies", "scan_dt", "TEXT");
        ensureColumn(conn, "wb_supplies", "reject_dt", "TEXT");
        ensureColumn(conn, "wb_supplies", "cargo_type", "INTEGER");
        ensureColumn(conn, "wb_supplies", "cross_border_type", "INTEGER");
        ensureColumn(conn, "wb_supplies", "destination_office_id", "INTEGER");
        ensureColumn(conn, "wb_supplies", "recommended_wh_id", "INTEGER");
        ensureColumn(conn, "wb_supplies", "order_count", "INTEGER");
        ensureColumn(conn, "wb_orders", "seller_date", "TEXT");
        ensureColumn(conn, "wb_orders", "user_id", "TEXT");
        ensureColumn(conn, "wb_orders", "sale_price", "INTEGER");
        ensureColumn(conn, "wb_orders", "converted_price", "INTEGER");
        ensureColumn(conn, "wb_orders", "converted_final_price", "INTEGER");
        ensureColumn(conn, "wb_orders", "currency_code", "INTEGER");
        ensureColumn(conn, "wb_orders", "converted_currency_code", "INTEGER");
        ensureColumn(conn, "wb_orders", "cargo_type", "INTEGER");
        ensureColumn(conn, "wb_orders", "cross_border_type", "INTEGER");
        ensureColumn(conn, "wb_orders", "scan_price", "INTEGER");
        ensureColumn(conn, "wb_orders", "is_zero_order", "INTEGER");
        ensureColumn(conn, "wb_orders", "is_b2b", "INTEGER");
        ensureColumn(conn, "wb_orders", "supplier_status", "TEXT");
        ensureColumn(conn, "wb_orders", "wb_status", "TEXT");
        ensureColumn(conn, "wb_orders", "is_cancellable", "INTEGER");
        ensureColumn(conn, "wb_orders", "status_synced_at", "TEXT");
        ensureColumn(conn, "wb_orders", "address_full", "TEXT");
        ensureColumn(conn, "wb_orders", "address_longitude", "REAL");
        ensureColumn(conn, "wb_orders", "address_latitude", "REAL");

        createIndexIfTableExists(conn, "idx_wb_product_cards_shop_id", "wb_product_cards", "shop_id");
        createIndexIfTableExists(conn, "idx_wb_product_sizes_shop_nm_id", "wb_product_sizes", "shop_id, nm_id");
        createIndexIfTableExists(conn, "idx_wb_product_characteristics_shop_nm_id", "wb_product_characteristics", "shop_id, nm_id");
        createIndexIfTableExists(conn, "idx_wb_product_photos_shop_nm_id", "wb_product_photos", "shop_id, nm_id");
        createIndexIfTableExists(conn, "idx_wb_product_tags_shop_nm_id", "wb_product_tags", "shop_id, nm_id");
        createIndexIfTableExists(conn, "idx_wb_supplies_shop_id", "wb_supplies", "shop_id");
        createIndexIfTableExists(conn, "idx_wb_supplies_shop_done_created", "wb_supplies", "shop_id, done, created_at DESC");
        createIndexIfTableExists(conn, "idx_wb_orders_shop_id", "wb_orders", "shop_id");
        createIndexIfTableExists(conn, "idx_wb_orders_shop_supply_id", "wb_orders", "shop_id, supply_id");
        createIndexIfTableExists(conn, "idx_wb_orders_shop_nm_id", "wb_orders", "shop_id, nm_id");
        createIndexIfTableExists(conn, "idx_wb_orders_shop_supplier_status_supply", "wb_orders", "shop_id, supplier_status, supply_id");
        createIndexIfTableExists(conn, "idx_wb_orders_shop_supply_created", "wb_orders", "shop_id, supply_id, created_at DESC");
        createIndexIfTableExists(conn, "idx_wb_orders_shop_created", "wb_orders", "shop_id, created_at DESC");
        createIndexIfTableExists(conn, "idx_wb_orders_shop_chrt_id", "wb_orders", "shop_id, chrt_id");
        createIndexIfTableExists(conn, "idx_wb_order_offices_shop_order_id", "wb_order_offices", "shop_id, order_id");
        createIndexIfTableExists(conn, "idx_wb_order_skus_shop_order_id", "wb_order_skus", "shop_id, order_id");
        createIndexIfTableExists(conn, "idx_wb_order_meta_requirements_shop_order_id", "wb_order_meta_requirements", "shop_id, order_id");
        createIndexIfTableExists(conn, "idx_wb_order_meta_requirements_shop_type_order", "wb_order_meta_requirements", "shop_id, requirement_type, order_id");
        createIndexIfTableExists(conn, "idx_wb_supply_orders_shop_supply_id", "wb_supply_orders", "shop_id, supply_id");
        createIndexIfTableExists(conn, "idx_wb_supply_orders_shop_order_id", "wb_supply_orders", "shop_id, order_id");
        createIndexIfTableExists(conn, "idx_wb_product_size_skus_shop_chrt_id", "wb_product_size_skus", "shop_id, chrt_id");
        createIndexIfTableExists(conn, "idx_wb_sync_runs_shop_id", "wb_sync_runs", "shop_id");
        createIndexIfTableExists(conn, "idx_wb_action_log_shop_id", "wb_action_log", "shop_id");
        createIndexIfTableExists(conn, "idx_wb_fbw_orders_status", "wb_fbw_orders", "shop_id, status_id, updated_date DESC");
        createIndexIfTableExists(conn, "idx_wb_fbw_orders_supply", "wb_fbw_orders", "shop_id, supply_id");
        createIndexIfTableExists(conn, "idx_wb_fbw_items_order", "wb_fbw_order_items", "shop_id, preorder_id");
    }

    private static void ensureShopColumn(Connection conn, String columnName, String columnDefinition) throws SQLException {
        ensureColumn(conn, "shops", columnName, columnDefinition);
    }

    private static void ensureColumn(Connection conn, String tableName, String columnName, String columnDefinition) throws SQLException {
        if (columnExists(conn, tableName, columnName)) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
        }
    }

    private static boolean columnExists(Connection conn, String tableName, String columnName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(" + tableName + ")");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static void createIndexIfTableExists(Connection conn, String indexName, String tableName, String columns) throws SQLException {
        if (!tableExists(conn, tableName)) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + tableName + "(" + columns + ")");
        }
    }

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type IN ('table','view') AND name = ? LIMIT 1")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
