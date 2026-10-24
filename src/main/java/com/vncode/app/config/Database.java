package com.vncode.app.config;

import com.vncode.app.shared.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.vncode.app.integration.wb.WbSchemaSupport;
import com.vncode.app.integration.ozon.OzonSchemaSupport;
import com.vncode.app.integration.znack.ZnackMappingLifecycle;
import com.vncode.app.integration.znack.ZnackSchemaSupport;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationSchema;

public class Database {
    private static final Logger LOGGER = LoggerFactory.getLogger(Database.class);
    private static final String DB_NAME = "database.db";
    private static final int CURRENT_SCHEMA_VERSION = 6;

    public static int currentSchemaVersion() {
        return CURRENT_SCHEMA_VERSION;
    }

    /**
     * Keep SQLite open for a bounded batch without holding a transaction or a read lock.
     * Each repository still owns and commits its own transaction. In WAL mode this prevents
     * closing the last connection (and checkpointing/deleting/recreating WAL files) per order.
     * No connection is retained globally, and synchronous/durability settings are unchanged.
     */
    public static BatchSession openBatchSession() {
        Connection connection = getConnection();
        try (Statement statement = connection.createStatement();
             ResultSet schema = statement.executeQuery("PRAGMA schema_version")) {
            if (!schema.next()) throw new SQLException("Cannot open database batch session");
            return new BatchSession(connection);
        } catch (SQLException | RuntimeException failure) {
            try { connection.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw new IllegalStateException("Cannot open database batch session", failure);
        }
    }

    public static final class BatchSession implements AutoCloseable {
        private final Connection connection;
        private BatchSession(Connection connection) { this.connection = connection; }
        @Override public void close() {
            try { connection.close(); }
            catch (SQLException failure) { throw new IllegalStateException("Cannot close database batch session", failure); }
        }
    }

    public static Connection getConnection() {
        com.vncode.app.features.sync.SyncDataScope.requireCurrent();
        try {
            Path dir = AppPaths.appDataDir();

            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            Path dbFile = dir.resolve(DB_NAME);

            Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + dbFile.toString()
            );
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
            }
            return connection;

        } catch (IOException | SQLException e) {
            LOGGER.error("Không thể mở database tại thư mục ứng dụng {}", AppPaths.appDataDir(), e);
            throw new RuntimeException(e);
        }
    }

    public static void initDatabase() {
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous = NORMAL");
            int existingSchemaVersion = readSchemaVersion(conn);
            if (existingSchemaVersion > CURRENT_SCHEMA_VERSION) {
                throw new SQLException("Database schema is newer than this VN code binary");
            }
            st.execute("""
                CREATE TABLE IF NOT EXISTS shops(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    marketplace TEXT NOT NULL DEFAULT 'WILDBERRIES'
                        CHECK(marketplace IN ('WILDBERRIES','OZON')),
                    client_id TEXT,
                    api_key TEXT NOT NULL
                )
            """);

            st.execute("""
            CREATE TABLE IF NOT EXISTS config(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type INTEGER NOT NULL DEFAULT 1
            )
            """);

            st.execute("""
            CREATE TABLE IF NOT EXISTS app_config(
                key   TEXT PRIMARY KEY,
                value TEXT
            )
            """);

            st.execute("""
            CREATE TABLE IF NOT EXISTS print_templates(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                page_width REAL NOT NULL DEFAULT 164.40944881889766,
                page_height REAL NOT NULL DEFAULT 113.38582677165356,
                is_default INTEGER NOT NULL DEFAULT 0,
                layout_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);

            st.execute("""
            CREATE TABLE IF NOT EXISTS fbo_print_templates(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                page_width REAL NOT NULL DEFAULT 164.40944881889766,
                page_height REAL NOT NULL DEFAULT 113.38582677165356,
                is_default INTEGER NOT NULL DEFAULT 0,
                layout_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);

            st.execute("""
            CREATE TABLE IF NOT EXISTS image_cache(
                cache_key TEXT PRIMARY KEY,
                image_url TEXT NOT NULL,
                image_blob BLOB NOT NULL,
                content_type TEXT,
                updated_at TEXT NOT NULL,
                last_used_at TEXT NOT NULL
            )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS print_jobs(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                shop_id INTEGER NOT NULL,
                shop_name TEXT,
                supply_id TEXT,
                supply_name TEXT,
                printed_at TEXT NOT NULL,
                item_count INTEGER NOT NULL,
                template_id INTEGER,
                template_name TEXT,
                template_layout_json TEXT NOT NULL,
                status TEXT NOT NULL,
                    error_message TEXT,
                    marketplace TEXT NOT NULL DEFAULT 'WILDBERRIES',
                FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
            )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS print_job_items(
                print_job_id INTEGER NOT NULL,
                sort_index INTEGER NOT NULL,
                order_id INTEGER NOT NULL,
                brand TEXT,
                name TEXT,
                subject_name TEXT,
                size TEXT,
                ru_size TEXT,
                color TEXT,
                article TEXT,
                barcode TEXT,
                sticker TEXT,
                sticker_code TEXT,
                kiz TEXT,
                    image_cache_key TEXT,
                    external_order_id TEXT,
                    external_item_id TEXT,
                PRIMARY KEY (print_job_id, sort_index),
                FOREIGN KEY (print_job_id) REFERENCES print_jobs(id) ON DELETE CASCADE
            )
            """);

            st.execute("INSERT INTO config (id, type) SELECT 1, 1 WHERE NOT EXISTS (SELECT 1 FROM config WHERE id = 1)");
            ensureColumnExists(conn, "print_jobs", "shop_name", "TEXT");
            ensureColumnExists(conn, "shops", "marketplace", "TEXT NOT NULL DEFAULT 'WILDBERRIES'");
            ensureColumnExists(conn, "shops", "client_id", "TEXT");
            ensureColumnExists(conn, "print_jobs", "marketplace", "TEXT NOT NULL DEFAULT 'WILDBERRIES'");
            ensureColumnExists(conn, "print_job_items", "ru_size", "TEXT");
            ensureColumnExists(conn, "print_job_items", "external_order_id", "TEXT");
            ensureColumnExists(conn, "print_job_items", "external_item_id", "TEXT");
            st.execute("UPDATE shops SET marketplace='WILDBERRIES' WHERE marketplace IS NULL OR trim(marketplace)=''");
            st.execute("UPDATE print_jobs SET marketplace='WILDBERRIES' WHERE marketplace IS NULL OR trim(marketplace)=''");
            createIndexIfNotExists(conn, "idx_print_jobs_shop_id", "print_jobs", "shop_id");
            createIndexIfNotExists(conn, "idx_print_jobs_shop_supply_status", "print_jobs", "shop_id, supply_id, status");
            createIndexIfNotExists(conn, "idx_print_jobs_shop_printed_at", "print_jobs", "shop_id, printed_at DESC");
            createIndexIfNotExists(conn, "idx_print_job_items_print_job_id", "print_job_items", "print_job_id");
            createIndexIfNotExists(conn, "idx_print_job_items_order_id", "print_job_items", "order_id");
            createIndexIfNotExists(conn, "idx_image_cache_last_used_at", "image_cache", "last_used_at");
            WbSchemaSupport.initialize(conn);
            ZnackSchemaSupport.initialize(conn);
            ZnackCardRegistrationSchema.initialize(conn);
            com.vncode.app.integration.znack.registration.v3.RegistrationStore.initialize(conn);
            com.vncode.app.integration.znack.registration.OzonCardRegistrationRepository.initialize(conn);
            OzonSchemaSupport.initialize(conn);
            com.vncode.app.features.gtinsync.GtinSyncSchema.initialize(conn);
            com.vncode.app.features.products.ProductSchema.ensureTables(conn);
            com.vncode.app.features.listingcopy.ListingCopySchema.ensureTables(conn);
            com.vncode.app.features.products.ProductEditRepository.ensureTables(conn);
                com.vncode.app.features.gs1.Gs1BalanceSchema.ensureTables(conn);
                com.vncode.app.features.news.SellerNewsSchema.ensureTables(conn);
                com.vncode.app.features.news.NewsTranslationService.ensureTables(conn);
                com.vncode.app.integration.translation.TranslationSettingsRepository.ensureTables(conn);
            int inactiveMappings = ZnackMappingLifecycle.removeInactiveMappings(conn);
            if (inactiveMappings > 0) {
                LOGGER.info("Đã gỡ {} mapping tham chiếu GTIN không còn hoạt động", inactiveMappings);
            }
            dropLegacyKizTables(conn);
            ShopCredentialSchema.initialize(conn);
            com.vncode.app.features.sync.SyncSchema.initialize(conn);
            com.vncode.app.integration.ozon.OzonPrintReceiptRepository.migrate(conn);
            st.execute("PRAGMA user_version = " + CURRENT_SCHEMA_VERSION);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static int readSchemaVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static void ensureColumnExists(Connection conn, String tableName, String columnName, String columnDefinition) throws SQLException {
        if (hasColumn(conn, tableName, columnName)) {
            return;
        }
        try (Statement statement = conn.createStatement()) {
            statement.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
        }
    }

    private static boolean hasColumn(Connection conn, String tableName, String columnName) throws SQLException {
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

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT 1
                FROM sqlite_master
                WHERE type = 'table' AND name = ?
                LIMIT 1
                """)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void createIndexIfNotExists(Connection conn, String indexName, String tableName, String columns) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + tableName + "(" + columns + ")");
        }
    }

    private static void dropLegacyKizTables(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS wb_product_kiz_mappings");
            statement.execute("DROP TABLE IF EXISTS kizs");
            statement.execute("DROP TABLE IF EXISTS shop_categories");
            statement.execute("DROP TABLE IF EXISTS categories");
        }
    }
}
