package com.tuandev.fbsbarcode.config;

import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Product;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.ShopContext;
import com.tuandev.fbsbarcode.integration.znack.ZnackRepository;
import com.tuandev.fbsbarcode.shared.LocalDataMigrationGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseMigrationCompatibilityTest {
    @TempDir Path temp;

    @AfterEach void clear() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void migratesV119SchemaZeroDatabaseWithoutChangingIdsOrCredentials() throws Exception {
        assertV119Migration(0);
    }

    @Test
    void migratesV119SchemaOneDatabaseWithoutChangingIdsOrCredentials() throws Exception {
        assertV119Migration(1);
    }

    @Test
    void migratesProductionV119RelationshipsWithoutLosingOperationalRows() throws Exception {
        System.setProperty("wcode.appdata.dir", temp.toString());
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE shops(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,api_key TEXT NOT NULL,
                        wb_products_cursor_updated_at TEXT,wb_products_cursor_nm_id INTEGER,
                        wb_products_last_synced_at TEXT,wb_supplies_next INTEGER NOT NULL DEFAULT 0,
                        wb_supplies_last_synced_at TEXT,wb_orders_next INTEGER NOT NULL DEFAULT 0,
                        wb_orders_last_synced_at TEXT,wb_orders_window_from INTEGER,
                        wb_orders_window_to INTEGER,wb_last_sync_error TEXT)
                    """);
            st.execute("""
                    CREATE TABLE wb_product_cards(
                        shop_id INTEGER NOT NULL,nm_id INTEGER NOT NULL,vendor_code TEXT,title TEXT,
                        synced_at TEXT NOT NULL,PRIMARY KEY(shop_id,nm_id),
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE wb_product_sizes(
                        shop_id INTEGER NOT NULL,chrt_id INTEGER NOT NULL,nm_id INTEGER NOT NULL,
                        tech_size TEXT,wb_size TEXT,PRIMARY KEY(shop_id,chrt_id),
                        FOREIGN KEY(shop_id,nm_id) REFERENCES wb_product_cards(shop_id,nm_id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE wb_product_size_skus(
                        shop_id INTEGER NOT NULL,chrt_id INTEGER NOT NULL,sku TEXT NOT NULL,
                        PRIMARY KEY(shop_id,chrt_id,sku),
                        FOREIGN KEY(shop_id,chrt_id) REFERENCES wb_product_sizes(shop_id,chrt_id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE wb_supplies(
                        shop_id INTEGER NOT NULL,supply_id TEXT NOT NULL,done INTEGER,name TEXT,
                        created_at TEXT,synced_at TEXT NOT NULL,PRIMARY KEY(shop_id,supply_id),
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE wb_orders(
                        shop_id INTEGER NOT NULL,order_id INTEGER NOT NULL,supply_id TEXT,article TEXT,
                        nm_id INTEGER,chrt_id INTEGER,created_at TEXT,synced_at TEXT NOT NULL,
                        supplier_status TEXT,wb_status TEXT,PRIMARY KEY(shop_id,order_id),
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE wb_order_skus(
                        shop_id INTEGER NOT NULL,order_id INTEGER NOT NULL,sku TEXT NOT NULL,
                        PRIMARY KEY(shop_id,order_id,sku),
                        FOREIGN KEY(shop_id,order_id) REFERENCES wb_orders(shop_id,order_id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE wb_supply_orders(
                        shop_id INTEGER NOT NULL,supply_id TEXT NOT NULL,order_id INTEGER NOT NULL,
                        PRIMARY KEY(shop_id,supply_id,order_id),
                        FOREIGN KEY(shop_id,supply_id) REFERENCES wb_supplies(shop_id,supply_id) ON DELETE CASCADE,
                        FOREIGN KEY(shop_id,order_id) REFERENCES wb_orders(shop_id,order_id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE znack_products(
                        shop_id INTEGER NOT NULL,gtin TEXT NOT NULL,product_name TEXT,tn_ved TEXT,
                        certificate_type TEXT,certificate_number TEXT,certificate_date TEXT,
                        production_date TEXT,good_mark_flag INTEGER,good_turn_flag INTEGER,
                        card_status TEXT,card_detailed_status TEXT,category TEXT,readiness_checked_at TEXT,
                        deleted_at TEXT,cis_type TEXT,synced_at TEXT NOT NULL,PRIMARY KEY(shop_id,gtin),
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE kiz_orders(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,shop_id INTEGER NOT NULL,
                        external_order_id TEXT,gtin TEXT NOT NULL,quantity INTEGER NOT NULL,
                        remote_status TEXT,local_status TEXT NOT NULL,error_message TEXT,
                        created_at TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(shop_id,id),
                        UNIQUE(shop_id,external_order_id),
                        FOREIGN KEY(shop_id,gtin) REFERENCES znack_products(shop_id,gtin),
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE kiz_codes(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,shop_id INTEGER NOT NULL,order_id INTEGER NOT NULL,
                        raw_code TEXT NOT NULL,display_code TEXT NOT NULL,gtin TEXT NOT NULL,block_id TEXT,
                        pdf_path TEXT,document_id INTEGER,status TEXT NOT NULL,reservation_recoverable INTEGER,
                        created_at TEXT NOT NULL,updated_at TEXT NOT NULL,legal_status TEXT,
                        reservation_token TEXT,reserved_at TEXT,consumed_at TEXT,UNIQUE(shop_id,id),
                        UNIQUE(shop_id,raw_code),
                        FOREIGN KEY(shop_id,order_id) REFERENCES kiz_orders(shop_id,id) ON DELETE CASCADE,
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE print_jobs(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,shop_id INTEGER NOT NULL,shop_name TEXT,
                        supply_id TEXT,supply_name TEXT,printed_at TEXT NOT NULL,item_count INTEGER NOT NULL,
                        template_id INTEGER,template_name TEXT,template_layout_json TEXT NOT NULL,
                        status TEXT NOT NULL,error_message TEXT,
                        FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE print_job_items(
                        print_job_id INTEGER NOT NULL,sort_index INTEGER NOT NULL,order_id INTEGER NOT NULL,
                        brand TEXT,name TEXT,subject_name TEXT,size TEXT,ru_size TEXT,color TEXT,article TEXT,
                        barcode TEXT,sticker TEXT,sticker_code TEXT,kiz TEXT,image_cache_key TEXT,
                        PRIMARY KEY(print_job_id,sort_index),
                        FOREIGN KEY(print_job_id) REFERENCES print_jobs(id) ON DELETE CASCADE)
                    """);
            st.execute("CREATE TABLE app_config(key TEXT PRIMARY KEY,value TEXT)");
            st.execute("INSERT INTO shops(id,name,api_key) VALUES(41,'Upgrade Sentinel','preserve-token')");
            st.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) "
                    + "VALUES(41,51001,'ARTICLE-119','Upgrade Product','2026-08-25T00:00:00Z')");
            st.execute("INSERT INTO wb_product_sizes VALUES(41,61001,51001,'XL','52')");
            st.execute("INSERT INTO wb_product_size_skus VALUES(41,61001,'4600000000001')");
            st.execute("INSERT INTO wb_supplies(shop_id,supply_id,done,name,synced_at) "
                    + "VALUES(41,'SUPPLY-119',0,'Upgrade Supply','2026-08-25T00:00:00Z')");
            st.execute("INSERT INTO wb_orders(shop_id,order_id,supply_id,article,nm_id,chrt_id,created_at,"
                    + "synced_at,supplier_status,wb_status) VALUES(41,900719925474099,'SUPPLY-119',"
                    + "'ARTICLE-119',51001,61001,'2026-08-25T00:00:00Z','2026-08-25T00:00:00Z','confirm','waiting')");
            st.execute("INSERT INTO wb_order_skus VALUES(41,900719925474099,'4600000000001')");
            st.execute("INSERT INTO wb_supply_orders VALUES(41,'SUPPLY-119',900719925474099)");
            st.execute("INSERT INTO znack_products(shop_id,gtin,product_name,tn_ved,synced_at) "
                    + "VALUES(41,'04600000000001','Upgrade KIZ Product','6201','2026-08-25T00:00:00Z')");
            st.execute("INSERT INTO kiz_orders(id,shop_id,external_order_id,gtin,quantity,remote_status,"
                    + "local_status,created_at,updated_at) VALUES(71,41,'REMOTE-119','04600000000001',1,"
                    + "'READY','COMPLETED','2026-08-25T00:00:00Z','2026-08-25T00:00:00Z')");
            st.execute("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,legal_status,"
                    + "created_at,updated_at) VALUES(81,41,71,'010460000000000121UPGRADE119',"
                    + "'010460000000000121UPGRADE119','04600000000001','AVAILABLE','AVAILABLE',"
                    + "'2026-08-25T00:00:00Z','2026-08-25T00:00:00Z')");
            st.execute("INSERT INTO print_jobs(id,shop_id,shop_name,supply_id,printed_at,item_count,"
                    + "template_layout_json,status) VALUES(91,41,'Upgrade Sentinel','SUPPLY-119',"
                    + "'2026-08-25T00:00:00Z',1,'{}','COMPLETED')");
            st.execute("INSERT INTO print_job_items(print_job_id,sort_index,order_id,name,article,barcode,kiz) "
                    + "VALUES(91,0,900719925474099,'Upgrade Product','ARTICLE-119','4600000000001',"
                    + "'010460000000000121UPGRADE119')");
            st.execute("INSERT INTO app_config VALUES('last_selected_shop_id','41')");
            st.execute("PRAGMA user_version=0");
        }

        try (LocalDataMigrationGate.Session ignored =
                LocalDataMigrationGate.prepare(temp, "1.1.10", "javafx")) {
            assertEquals(3, scalarInt("PRAGMA user_version"));
            assertEquals("WILDBERRIES", scalarText("SELECT marketplace FROM shops WHERE id=41"));
            assertEquals("preserve-token", scalarText("SELECT api_key FROM shops WHERE id=41"));
            assertEquals("ARTICLE-119", scalarText(
                    "SELECT article FROM wb_orders WHERE shop_id=41 AND order_id=900719925474099"));
            assertEquals("010460000000000121UPGRADE119", scalarText(
                    "SELECT raw_code FROM kiz_codes WHERE shop_id=41 AND id=81"));
            assertEquals("SUPPLY-119", scalarText("SELECT supply_id FROM print_jobs WHERE id=91"));
            assertEquals("ok", scalarText("PRAGMA integrity_check"));
            assertEquals(0, resultRowCount("PRAGMA foreign_key_check"));
            assertTrue(tableExists("ozon_postings"));
            assertTrue(tableExists("wb_fbw_orders"));
            assertTrue(tableExists("wb_fbw_order_items"));
            assertTrue(tableExists("wb_fbw_sync_state"));
            assertTrue(tableExists("ozon_fbo_orders"));
            assertTrue(tableExists("ozon_fbo_supplies"));
            assertTrue(tableExists("ozon_fbo_supply_items"));
            assertTrue(tableExists("ozon_fbo_sync_state"));
            assertTrue(columnExists("znack_products", "permit_documents_json"));
            assertTrue(columnExists("znack_products", "gender"));
            assertTrue(columnExists("znack_products", "size"));
        }
    }

    @Test
    void parksOnlyLegacyReadinessPipelinesWithConfirmedMissingDocuments() throws Exception {
        System.setProperty("wcode.appdata.dir", temp.toString());
        Database.initDatabase();
        try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Shop','secret')");
            statement.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES"
                    + "(1,'04600000000001','Missing document','2026-09-03T00:00:00Z'),"
                    + "(1,'04600000000002','Temporary error','2026-09-03T00:00:00Z')");
            statement.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,error_message,created_at,updated_at) VALUES"
                    + "(1,1,'04600000000001',1,'WAITING_INTRODUCTION_READINESS','old','2026-09-03T00:00:00Z','2026-09-03T00:00:00Z'),"
                    + "(2,1,'04600000000002',1,'WAITING_INTRODUCTION_READINESS','old','2026-09-03T00:00:00Z','2026-09-03T00:00:00Z')");
            statement.execute("INSERT INTO znack_purchase_pipelines(id,shop_id,gtin,quantity,order_id,request_key,stage,error_message,created_at,updated_at) VALUES"
                    + "(1,1,'04600000000001',1,1,'missing-doc','WAITING_INTRODUCTION_READINESS',"
                    + "'GTIN 04600000000001 has no active National Catalog declaration or certificate.','2026-09-03T00:00:00Z','2026-09-03T00:00:00Z'),"
                    + "(2,1,'04600000000002',1,2,'temporary','WAITING_INTRODUCTION_READINESS',"
                    + "'Could not verify active National Catalog documents.','2026-09-03T00:00:00Z','2026-09-03T00:00:00Z')");
        }

        Database.initDatabase();

        assertEquals("WAITING_INTRODUCTION_DOCUMENTS",
                scalarText("SELECT stage FROM znack_purchase_pipelines WHERE id=1"));
        assertEquals("WAITING_INTRODUCTION_DOCUMENTS",
                scalarText("SELECT local_status FROM kiz_orders WHERE id=1"));
        assertEquals("WAITING_INTRODUCTION_READINESS",
                scalarText("SELECT stage FROM znack_purchase_pipelines WHERE id=2"));
        assertEquals("WAITING_INTRODUCTION_READINESS",
                scalarText("SELECT local_status FROM kiz_orders WHERE id=2"));
    }

    private void assertV119Migration(int legacySchemaVersion) throws Exception {
        System.setProperty("wcode.appdata.dir", temp.toString());
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE shops(id INTEGER PRIMARY KEY,name TEXT NOT NULL,api_key TEXT NOT NULL)");
            st.execute("CREATE TABLE legacy_orders(id INTEGER PRIMARY KEY,shop_id INTEGER NOT NULL "
                    + "REFERENCES shops(id),external_id TEXT NOT NULL)");
            st.execute("INSERT INTO shops VALUES(41,'Existing WB','preserve-token')");
            st.execute("INSERT INTO legacy_orders VALUES(7,41,'90071992547409931234')");
            st.execute("PRAGMA user_version=" + legacySchemaVersion);
        }

        try (LocalDataMigrationGate.Session ignored =
                LocalDataMigrationGate.prepare(temp, "1.1.10", "javafx")) {
            assertEquals(3, scalarInt("PRAGMA user_version"));
            assertEquals("WILDBERRIES", scalarText("SELECT marketplace FROM shops WHERE id=41"));
            assertEquals("preserve-token", scalarText("SELECT api_key FROM shops WHERE id=41"));
            assertEquals("90071992547409931234", scalarText("SELECT external_id FROM legacy_orders WHERE id=7"));
            assertEquals("ok", scalarText("PRAGMA integrity_check"));
            assertEquals(0, resultRowCount("PRAGMA foreign_key_check"));
            assertTrue(tableExists("ozon_postings"));
            assertTrue(tableExists("wb_fbw_orders"));
            assertTrue(tableExists("ozon_fbo_orders"));
        }
        try (var paths = java.nio.file.Files.walk(temp.resolve("snapshots"))) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().equals("database.db")));
        }
    }

    @Test
    void dropsLegacyKizTablesAndKeepsZnackAuditIdempotently() throws Exception {
        System.setProperty("wcode.appdata.dir", temp.toString());
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE shops(id INTEGER PRIMARY KEY,name TEXT NOT NULL,api_key TEXT NOT NULL)");
            st.execute("CREATE TABLE categories(id INTEGER PRIMARY KEY,name TEXT)");
            st.execute("CREATE TABLE shop_categories(shop_id INTEGER,category_id INTEGER,created_at TEXT)");
            st.execute("CREATE TABLE kizs(id INTEGER PRIMARY KEY,code TEXT,shop_id INTEGER,category_id INTEGER)");
            st.execute("CREATE TABLE wb_product_kiz_mappings(shop_id INTEGER,nm_id INTEGER,kiz_category_id INTEGER,updated_at TEXT)");
            st.execute("CREATE TABLE app_config(key TEXT PRIMARY KEY,value TEXT)");
            st.execute("INSERT INTO shops VALUES(1,'Shop','token')");
        }

        Database.initDatabase();
        ZnackRepository repository = new ZnackRepository(new ShopContext(1, "Shop"));
        repository.upsertProducts(List.of(new Product("123", "Product", null, null, null, null, null)));
        repository.log("AUDIT", "123", "INFO", "kept", null);
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE categories(id INTEGER PRIMARY KEY,name TEXT)");
            st.execute("CREATE TABLE shop_categories(shop_id INTEGER,category_id INTEGER,created_at TEXT)");
            st.execute("CREATE TABLE kizs(id INTEGER PRIMARY KEY,code TEXT,shop_id INTEGER,category_id INTEGER)");
            st.execute("CREATE TABLE wb_product_kiz_mappings(shop_id INTEGER,nm_id INTEGER,kiz_category_id INTEGER,updated_at TEXT)");
        }
        Database.initDatabase();

        for (String table : new String[]{"categories","shop_categories","kizs","wb_product_kiz_mappings"}) {
            assertFalse(tableExists(table), table);
        }
        assertEquals(1, count("SELECT COUNT(*) FROM shops"));
        assertEquals(1, count("SELECT COUNT(*) FROM znack_operation_logs"));
        assertEquals(1, count("SELECT COUNT(*) FROM znack_products WHERE gtin='00000000000123'"));
    }

    @Test
    void addingOzonCardColumnsForcesOneCompleteCatalogRefresh() throws Exception {
        System.setProperty("wcode.appdata.dir", temp.toString());
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE shops(
                        id INTEGER PRIMARY KEY,name TEXT NOT NULL,marketplace TEXT,client_id TEXT,api_key TEXT NOT NULL)
                    """);
            st.execute("INSERT INTO shops VALUES(11,'Ozon','OZON','client','secret')");
            st.execute("""
                    CREATE TABLE ozon_sync_state(
                        shop_id INTEGER PRIMARY KEY,products_last_id TEXT,products_last_synced_at TEXT,
                        postings_changed_since TEXT,postings_last_synced_at TEXT,last_error TEXT)
                    """);
            st.execute("""
                    INSERT INTO ozon_sync_state(shop_id,products_last_id,products_last_synced_at)
                    VALUES(11,'finished-cursor','2026-08-24T00:00:00Z')
                    """);
            st.execute("""
                    CREATE TABLE ozon_products(
                        shop_id INTEGER NOT NULL,product_id TEXT NOT NULL,offer_id TEXT,sku TEXT,name TEXT,
                        primary_image_url TEXT,archived INTEGER NOT NULL DEFAULT 0,updated_at TEXT,synced_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id,product_id))
                    """);
            st.execute("""
                    INSERT INTO ozon_products VALUES(11,'101','offer','sku','Product','',0,'','2026-08-24T00:00:00Z')
                    """);
        }

        Database.initDatabase();

        assertTrue(columnExists("ozon_products", "article"));
        assertTrue(columnExists("ozon_products", "color"));
        assertTrue(columnExists("ozon_products", "size"));
        assertNull(scalarText("SELECT products_last_id FROM ozon_sync_state WHERE shop_id=11"));
    }

    @Test
    void mergesEquivalentShortAndPaddedGtinsWithoutLosingReferences() throws Exception {
        System.setProperty("wcode.appdata.dir", temp.toString());
        Database.initDatabase();
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO shops(id,name,api_key) VALUES(1,'Shop','token')");
            st.execute("""
                    INSERT INTO znack_products(shop_id,gtin,product_name,tn_ved,synced_at)
                    VALUES(1,'123','Short','6201','2026-01-01T00:00:00Z'),
                          (1,'00000000000123','Padded',NULL,'2026-01-02T00:00:00Z')
                    """);
            st.execute("""
                    INSERT INTO kiz_orders(id,shop_id,gtin,quantity,local_status,created_at,updated_at)
                    VALUES(1,1,'123',1,'DRAFT','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')
                    """);
            st.execute("""
                    INSERT INTO znack_purchase_pipelines(shop_id,gtin,quantity,stage,created_at,updated_at)
                    VALUES(1,'123',1,'POLLING_ORDER','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z'),
                          (1,'00000000000123',1,'POLLING_ORDER','2026-01-02T00:00:00Z','2026-01-02T00:00:00Z')
                    """);
        }

        Database.initDatabase();

        assertEquals(1, count("SELECT COUNT(*) FROM znack_products WHERE gtin='00000000000123'"));
        assertEquals(1, count("SELECT COUNT(*) FROM kiz_orders WHERE gtin='00000000000123'"));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM znack_purchase_pipelines
                WHERE gtin='00000000000123' AND stage='POLLING_ORDER'
                """));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM znack_purchase_pipelines
                WHERE gtin='00000000000123' AND stage='QUEUED'
                """));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM znack_products
                WHERE gtin='00000000000123' AND tn_ved='6201'
                """));
    }

    private boolean tableExists(String name) throws Exception {
        try (Connection c = Database.getConnection(); var ps = c.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private boolean columnExists(String table, String column) throws Exception {
        try (Connection c = Database.getConnection();
                ResultSet rs = c.createStatement().executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
            return false;
        }
    }

    private int count(String sql) throws Exception {
        try (Connection c = Database.getConnection(); ResultSet rs = c.createStatement().executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private int scalarInt(String sql) throws Exception {
        try (Connection c = Database.getConnection(); ResultSet rs = c.createStatement().executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private String scalarText(String sql) throws Exception {
        try (Connection c = Database.getConnection(); ResultSet rs = c.createStatement().executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private int resultRowCount(String sql) throws Exception {
        try (Connection c = Database.getConnection(); ResultSet rs = c.createStatement().executeQuery(sql)) {
            int rows = 0;
            while (rs.next()) rows++;
            return rows;
        }
    }
}
