package com.tuandev.fbsbarcode.integration.znack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.List;
import java.util.UUID;

public final class ZnackSchemaSupport {
    public static final String AMBIGUOUS_MIGRATION_NOTICE = "znack_ambiguous_migration_notice";
    private static final Logger LOGGER = LoggerFactory.getLogger(ZnackSchemaSupport.class);
    private static final List<String> TABLES = List.of(
            "znack_settings", "znack_products", "kiz_orders", "znack_documents", "kiz_codes", "znack_operation_logs");

    private ZnackSchemaSupport() {
    }

    public static void initialize(Connection connection) throws SQLException {
        if (tableExists(connection, "znack_settings") && !hasColumn(connection, "znack_settings", "shop_id")) {
            migrateLegacy(connection);
        }
        createTables(connection);
        addPurchaseIdempotencyColumns(connection);
        addCryptoProColumns(connection);
        addIntroductionColumns(connection);
        migrateMissingDocumentWaitStages(connection);
        addInventoryColumns(connection);
        migrateInventoryStatuses(connection);
        enforceGlobalCodeUniqueness(connection);
        normalizeGtins(connection);
    }

    private static void migrateLegacy(Connection c) throws SQLException {
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys=OFF");
            for (String table : TABLES) {
                if (tableExists(c, table)) {
                    st.execute("ALTER TABLE " + table + " RENAME TO znack_legacy_unscoped_" + table);
                }
            }
            createTables(c);
            Integer target = migrationTarget(c);
            if (target != null) {
                copyLegacy(c, target);
            } else {
                setConfig(c, AMBIGUOUS_MIGRATION_NOTICE, "pending");
                LOGGER.warn("Archived ambiguous unscoped Znack data; no shop was selected for assignment.");
            }
            st.execute("PRAGMA foreign_keys=ON");
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private static Integer migrationTarget(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id FROM shops ORDER BY id")) {
            Integer only = null;
            int count = 0;
            while (rs.next()) {
                only = rs.getInt(1);
                count++;
            }
            if (count == 1) return only;
        }
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT CAST(value AS INTEGER) FROM app_config
                WHERE key='last_selected_shop_id' AND CAST(value AS INTEGER) IN (SELECT id FROM shops)
                """);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : null;
        }
    }

    private static void copyLegacy(Connection c, int shopId) throws SQLException {
        String name;
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM shops WHERE id=?")) {
            ps.setInt(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                name = rs.getString(1);
            }
        }
        try (Statement st = c.createStatement()) {
            if (tableExists(c, "znack_legacy_unscoped_znack_settings")) st.execute("""
                    INSERT INTO znack_settings(shop_id,true_api_base_url,suz_base_url,oms_id,oms_connection,
                    participant_inn,producer_inn,owner_inn,signer_executable,signer_certificate,signer_arguments_json,
                    document_number,document_date,pdf_folder,auto_introduction,certificate_list_executable,
                    certificate_list_arguments_json,certificate_metadata_json,signer_tested_at,updated_at)
                    SELECT %d,true_api_base_url,suz_base_url,oms_id,oms_connection,
                    participant_inn,producer_inn,owner_inn,signer_executable,signer_certificate,signer_arguments_json,
                    document_number,document_date,pdf_folder,auto_introduction,'','[]','',NULL,updated_at
                    FROM znack_legacy_unscoped_znack_settings
                    """.formatted(shopId));
            if (tableExists(c, "znack_legacy_unscoped_znack_products")) st.execute("""
                    INSERT INTO znack_products(shop_id,gtin,product_name,tn_ved,certificate_type,certificate_number,
                    certificate_date,production_date,synced_at)
                    SELECT %d,gtin,product_name,tn_ved,certificate_type,certificate_number,
                    certificate_date,production_date,synced_at FROM znack_legacy_unscoped_znack_products
                    """.formatted(shopId));
            if (tableExists(c, "znack_legacy_unscoped_kiz_orders")) st.execute("""
                    INSERT INTO kiz_orders SELECT id,%d,external_order_id,gtin,quantity,remote_status,local_status,
                    error_message,created_at,updated_at FROM znack_legacy_unscoped_kiz_orders
                    """.formatted(shopId));
            if (tableExists(c, "znack_legacy_unscoped_znack_documents")) st.execute("""
                    INSERT INTO znack_documents SELECT id,%d,order_id,document_type,payload_json,external_document_id,
                    status,error_message,created_at,updated_at FROM znack_legacy_unscoped_znack_documents
                    """.formatted(shopId));
            if (tableExists(c, "znack_legacy_unscoped_kiz_codes")) {
                int sourceCount = countRows(c, "znack_legacy_unscoped_kiz_codes");
                int insertedCount = st.executeUpdate("""
                    INSERT OR IGNORE INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,block_id,pdf_path,document_id,
                    status,created_at,updated_at)
                    SELECT id,%d,order_id,raw_code,display_code,gtin,block_id,pdf_path,document_id,
                    status,created_at,updated_at FROM znack_legacy_unscoped_kiz_codes
                    """.formatted(shopId));
                if (insertedCount < sourceCount) {
                    LOGGER.warn("Skipped {} duplicate or conflicting legacy KIZ code rows during migration.",
                            sourceCount - insertedCount);
                }
            }
            if (tableExists(c, "znack_legacy_unscoped_znack_operation_logs")) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO znack_operation_logs(shop_id,shop_name,action,entity_reference,severity,message,http_status,created_at)
                        SELECT ?,?,action,entity_reference,severity,message,http_status,created_at
                        FROM znack_legacy_unscoped_znack_operation_logs
                        """)) {
                    ps.setInt(1, shopId);
                    ps.setString(2, name);
                    ps.executeUpdate();
                }
            }
        }
    }

    private static void createTables(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS znack_settings(
                    shop_id INTEGER PRIMARY KEY,true_api_base_url TEXT,suz_base_url TEXT,oms_id TEXT,oms_connection TEXT,
                    participant_inn TEXT,producer_inn TEXT,owner_inn TEXT,signer_executable TEXT,signer_certificate TEXT,
                    signer_arguments_json TEXT,document_number TEXT,document_date TEXT,pdf_folder TEXT,
                    auto_introduction INTEGER NOT NULL DEFAULT 0,certificate_list_executable TEXT,
                    certificate_list_arguments_json TEXT,certificate_metadata_json TEXT,signer_tested_at TEXT,
                    certmgr_path TEXT,cryptcp_path TEXT,csptest_path TEXT,cryptopro_timeout_seconds INTEGER NOT NULL DEFAULT 60,
                    document_expiry_date TEXT,document_type TEXT,updated_at TEXT NOT NULL,
                    FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS znack_products(
                    shop_id INTEGER NOT NULL,gtin TEXT NOT NULL,product_name TEXT,tn_ved TEXT,certificate_type TEXT,
                    certificate_number TEXT,certificate_date TEXT,production_date TEXT,good_mark_flag INTEGER,
                    good_turn_flag INTEGER,card_status TEXT,card_detailed_status TEXT,category TEXT,
                    readiness_checked_at TEXT,deleted_at TEXT,identity_archived_at TEXT,cis_type TEXT,
                    permit_documents_json TEXT,gender TEXT,size TEXT,synced_at TEXT NOT NULL,
                    PRIMARY KEY(shop_id,gtin),FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS kiz_orders(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,shop_id INTEGER NOT NULL,external_order_id TEXT,gtin TEXT NOT NULL,
                    quantity INTEGER NOT NULL,remote_status TEXT,local_status TEXT NOT NULL,error_message TEXT,created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,UNIQUE(shop_id,id),UNIQUE(shop_id,external_order_id),
                    FOREIGN KEY(shop_id,gtin) REFERENCES znack_products(shop_id,gtin),FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS znack_documents(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,shop_id INTEGER NOT NULL,order_id INTEGER NOT NULL,document_type TEXT NOT NULL,
                    payload_json TEXT NOT NULL,external_document_id TEXT,status TEXT NOT NULL,error_message TEXT,created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,UNIQUE(shop_id,id),FOREIGN KEY(shop_id,order_id) REFERENCES kiz_orders(shop_id,id) ON DELETE CASCADE,
                    FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS kiz_codes(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,shop_id INTEGER NOT NULL,order_id INTEGER NOT NULL,raw_code TEXT NOT NULL,
                    display_code TEXT NOT NULL,gtin TEXT NOT NULL,block_id TEXT,pdf_path TEXT,document_id INTEGER,status TEXT NOT NULL,
                    reservation_recoverable INTEGER,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
                    UNIQUE(shop_id,id),UNIQUE(shop_id,raw_code),
                    FOREIGN KEY(shop_id,order_id) REFERENCES kiz_orders(shop_id,id) ON DELETE CASCADE,
                    FOREIGN KEY(shop_id,document_id) REFERENCES znack_documents(shop_id,id),
                    FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS znack_operation_logs(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,shop_id INTEGER NOT NULL,shop_name TEXT NOT NULL,action TEXT NOT NULL,
                    entity_reference TEXT,severity TEXT NOT NULL,message TEXT NOT NULL,http_status INTEGER,created_at TEXT NOT NULL,
                    FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS znack_gtin_mapping_rules(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,shop_id INTEGER NOT NULL,gtin TEXT NOT NULL,
                    subject_name TEXT NOT NULL,gender_value TEXT NOT NULL,wildcard_gender INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
                    UNIQUE(shop_id,subject_name,gender_value),
                    FOREIGN KEY(shop_id,gtin) REFERENCES znack_products(shop_id,gtin) ON DELETE CASCADE,
                    FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS znack_purchase_pipelines(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,shop_id INTEGER NOT NULL,gtin TEXT NOT NULL,quantity INTEGER NOT NULL,
                    order_id INTEGER,request_key TEXT,stage TEXT NOT NULL,error_message TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
                    FOREIGN KEY(shop_id,gtin) REFERENCES znack_products(shop_id,gtin) ON DELETE CASCADE,
                    FOREIGN KEY(shop_id,order_id) REFERENCES kiz_orders(shop_id,id),
                    FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_znack_products_shop ON znack_products(shop_id,gtin)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_kiz_orders_shop_status ON kiz_orders(shop_id,local_status,updated_at DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_kiz_codes_shop_order ON kiz_codes(shop_id,order_id,status)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_kiz_codes_shop_gtin_status ON kiz_codes(shop_id,gtin,status,id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_znack_documents_shop_order ON znack_documents(shop_id,order_id,status)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_znack_logs_shop_created ON znack_operation_logs(shop_id,created_at DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_znack_gtin_mapping_rules_shop_gtin ON znack_gtin_mapping_rules(shop_id,gtin)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_znack_purchase_pipelines_shop_gtin ON znack_purchase_pipelines(shop_id,gtin,updated_at DESC)");
            // Only a remote-purchase mutation is serialized per GTIN. Later requests remain
            // durably QUEUED, while introduction jobs may continue independently after all codes
            // have been downloaded.
            st.execute("""
                    UPDATE znack_purchase_pipelines SET stage='QUEUED',error_message=NULL
                    WHERE stage IN ('VALIDATING','CREATING_ORDER','RECONCILING_ORDER','POLLING_ORDER','DOWNLOADING_CODES')
                      AND id NOT IN (
                        SELECT MIN(id) FROM znack_purchase_pipelines
                        WHERE stage IN ('VALIDATING','CREATING_ORDER','RECONCILING_ORDER','POLLING_ORDER','DOWNLOADING_CODES')
                        GROUP BY shop_id,gtin
                      )
                    """);
            st.execute("DROP INDEX IF EXISTS uq_znack_purchase_pipeline_active");
            st.execute("""
                    CREATE UNIQUE INDEX uq_znack_purchase_pipeline_active
                    ON znack_purchase_pipelines(shop_id,gtin)
                    WHERE stage IN ('VALIDATING','CREATING_ORDER','RECONCILING_ORDER','POLLING_ORDER','DOWNLOADING_CODES')
                    """);
        }
    }

    private static void addPurchaseIdempotencyColumns(Connection c) throws SQLException {
        if (!tableExists(c, "znack_purchase_pipelines")) return;
        try (Statement st = c.createStatement()) {
            if (!hasColumn(c, "znack_purchase_pipelines", "request_key")) {
                st.execute("ALTER TABLE znack_purchase_pipelines ADD COLUMN request_key TEXT");
            }
            try (ResultSet rows = st.executeQuery(
                    "SELECT id,shop_id FROM znack_purchase_pipelines WHERE request_key IS NULL")) {
                try (PreparedStatement update = c.prepareStatement(
                        "UPDATE znack_purchase_pipelines SET request_key=? WHERE id=? AND request_key IS NULL")) {
                    while (rows.next()) {
                        String seed = "wcode-znack-purchase:" + rows.getInt("shop_id") + ":" + rows.getLong("id");
                        update.setString(1, UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString());
                        update.setLong(2, rows.getLong("id"));
                        update.addBatch();
                    }
                    update.executeBatch();
                }
            }
            st.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uq_znack_purchase_pipeline_request
                    ON znack_purchase_pipelines(shop_id,request_key)
                    WHERE request_key IS NOT NULL
                    """);
        }
    }

    private static void addCryptoProColumns(Connection c) throws SQLException {
        if (!tableExists(c, "znack_settings")) return;
        try (Statement st = c.createStatement()) {
            if (!hasColumn(c, "znack_settings", "certmgr_path")) st.execute("ALTER TABLE znack_settings ADD COLUMN certmgr_path TEXT");
            if (!hasColumn(c, "znack_settings", "cryptcp_path")) st.execute("ALTER TABLE znack_settings ADD COLUMN cryptcp_path TEXT");
            if (!hasColumn(c, "znack_settings", "csptest_path")) st.execute("ALTER TABLE znack_settings ADD COLUMN csptest_path TEXT");
            if (!hasColumn(c, "znack_settings", "cryptopro_timeout_seconds")) {
                st.execute("ALTER TABLE znack_settings ADD COLUMN cryptopro_timeout_seconds INTEGER NOT NULL DEFAULT 60");
            }
            if (!hasColumn(c, "znack_settings", "document_expiry_date")) st.execute("ALTER TABLE znack_settings ADD COLUMN document_expiry_date TEXT");
            st.execute("""
                    UPDATE znack_settings SET
                    cryptcp_path=CASE WHEN (cryptcp_path IS NULL OR cryptcp_path='') AND lower(signer_executable) LIKE '%cryptcp%' THEN signer_executable ELSE cryptcp_path END,
                    certmgr_path=CASE WHEN (certmgr_path IS NULL OR certmgr_path='') AND lower(certificate_list_executable) LIKE '%certmgr%' THEN certificate_list_executable ELSE certmgr_path END,
                    signer_tested_at=CASE
                      WHEN (signer_executable IS NOT NULL AND signer_executable<>'' AND lower(signer_executable) NOT LIKE '%cryptcp%')
                        OR (certificate_list_executable IS NOT NULL AND certificate_list_executable<>'' AND lower(certificate_list_executable) NOT LIKE '%certmgr%')
                      THEN NULL ELSE signer_tested_at END
                    """);
        }
    }

    private static void addIntroductionColumns(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            if (tableExists(c, "znack_settings") && !hasColumn(c, "znack_settings", "document_type")) {
                st.execute("ALTER TABLE znack_settings ADD COLUMN document_type TEXT");
            }
            if (!tableExists(c, "znack_products")) return;
            if (!hasColumn(c, "znack_products", "good_mark_flag")) {
                st.execute("ALTER TABLE znack_products ADD COLUMN good_mark_flag INTEGER");
            }
            if (!hasColumn(c, "znack_products", "good_turn_flag")) {
                st.execute("ALTER TABLE znack_products ADD COLUMN good_turn_flag INTEGER");
            }
            if (!hasColumn(c, "znack_products", "card_status")) {
                st.execute("ALTER TABLE znack_products ADD COLUMN card_status TEXT");
            }
            if (!hasColumn(c, "znack_products", "card_detailed_status")) {
                st.execute("ALTER TABLE znack_products ADD COLUMN card_detailed_status TEXT");
            }
            if (!hasColumn(c, "znack_products", "readiness_checked_at")) {
                st.execute("ALTER TABLE znack_products ADD COLUMN readiness_checked_at TEXT");
            }
            if (!hasColumn(c, "znack_products", "deleted_at")) {
                st.execute("ALTER TABLE znack_products ADD COLUMN deleted_at TEXT");
            }
            if (!hasColumn(c, "znack_products", "identity_archived_at")) {
                st.execute("ALTER TABLE znack_products ADD COLUMN identity_archived_at TEXT");
            }
            if (!hasColumn(c, "znack_products", "category")) {
                st.execute("ALTER TABLE znack_products ADD COLUMN category TEXT");
            }
            if (!hasColumn(c, "znack_products", "cis_type")) {
                st.execute("ALTER TABLE znack_products ADD COLUMN cis_type TEXT");
            }
            if (!hasColumn(c, "znack_products", "permit_documents_json")) {
                st.execute("ALTER TABLE znack_products ADD COLUMN permit_documents_json TEXT");
            }
            if (!hasColumn(c, "znack_products", "gender")) {
                st.execute("ALTER TABLE znack_products ADD COLUMN gender TEXT");
            }
            if (!hasColumn(c, "znack_products", "size")) {
                st.execute("ALTER TABLE znack_products ADD COLUMN size TEXT");
            }
        }
    }

    private static void migrateMissingDocumentWaitStages(Connection c) throws SQLException {
        if (!tableExists(c, "znack_purchase_pipelines") || !tableExists(c, "kiz_orders")) return;
        try (Statement st = c.createStatement()) {
            st.executeUpdate("""
                    UPDATE kiz_orders
                    SET local_status='WAITING_INTRODUCTION_DOCUMENTS'
                    WHERE local_status='WAITING_INTRODUCTION_READINESS'
                      AND id IN (
                        SELECT order_id FROM znack_purchase_pipelines
                        WHERE order_id IS NOT NULL
                          AND stage='WAITING_INTRODUCTION_READINESS'
                          AND lower(COALESCE(error_message,''))
                            LIKE '%no active national catalog declaration or certificate%'
                      )
                    """);
            st.executeUpdate("""
                    UPDATE znack_purchase_pipelines
                    SET stage='WAITING_INTRODUCTION_DOCUMENTS'
                    WHERE stage='WAITING_INTRODUCTION_READINESS'
                      AND lower(COALESCE(error_message,''))
                        LIKE '%no active national catalog declaration or certificate%'
                    """);
        }
    }

    private static void addInventoryColumns(Connection c) throws SQLException {
        if (!tableExists(c, "kiz_codes")) return;
        try (Statement st = c.createStatement()) {
            if (!hasColumn(c, "kiz_codes", "legal_status")) st.execute("ALTER TABLE kiz_codes ADD COLUMN legal_status TEXT");
            if (!hasColumn(c, "kiz_codes", "reservation_token")) st.execute("ALTER TABLE kiz_codes ADD COLUMN reservation_token TEXT");
            if (!hasColumn(c, "kiz_codes", "reserved_at")) st.execute("ALTER TABLE kiz_codes ADD COLUMN reserved_at TEXT");
            if (!hasColumn(c, "kiz_codes", "consumed_at")) st.execute("ALTER TABLE kiz_codes ADD COLUMN consumed_at TEXT");
            if (!hasColumn(c, "kiz_codes", "reservation_recoverable")) st.execute("ALTER TABLE kiz_codes ADD COLUMN reservation_recoverable INTEGER");
        }
    }

    private static void migrateInventoryStatuses(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    UPDATE kiz_codes
                    SET legal_status=COALESCE(legal_status,CASE
                          WHEN status NOT IN ('AVAILABLE','RESERVED','CONSUMED','ARCHIVED') THEN status END),
                        consumed_at=CASE
                          WHEN status NOT IN ('AVAILABLE','RESERVED','CONSUMED','ARCHIVED','RECEIVED')
                          THEN COALESCE(consumed_at,updated_at) ELSE consumed_at END,
                        status=CASE
                          WHEN status='RECEIVED' THEN 'AVAILABLE'
                          WHEN status IN ('AVAILABLE','RESERVED','CONSUMED','ARCHIVED') THEN status
                          ELSE 'CONSUMED' END
                    WHERE legal_status IS NULL OR status NOT IN ('AVAILABLE','RESERVED','CONSUMED','ARCHIVED')
                    """);
        }
    }

    private static void enforceGlobalCodeUniqueness(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS znack_duplicate_kiz_code_audit(
                    source_id INTEGER PRIMARY KEY,shop_id INTEGER,order_id INTEGER,raw_code TEXT NOT NULL,
                    display_code TEXT,gtin TEXT,block_id TEXT,document_id INTEGER,status TEXT,legal_status TEXT,
                    reservation_token TEXT,reserved_at TEXT,reservation_recoverable INTEGER,consumed_at TEXT,
                    created_at TEXT,updated_at TEXT,
                    archived_at TEXT NOT NULL,reason TEXT NOT NULL)
                    """);
            if (!hasColumn(c, "znack_duplicate_kiz_code_audit", "reservation_recoverable")) {
                st.execute("ALTER TABLE znack_duplicate_kiz_code_audit ADD COLUMN reservation_recoverable INTEGER");
            }
        }
        int duplicateCount;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
                SELECT COUNT(*) FROM kiz_codes
                WHERE id NOT IN (SELECT MIN(id) FROM kiz_codes GROUP BY raw_code)
                """)) {
            duplicateCount = rs.next() ? rs.getInt(1) : 0;
        }
        if (duplicateCount > 0) {
            String archivedAt = java.time.Instant.now().toString();
            try (PreparedStatement archive = c.prepareStatement("""
                    INSERT OR IGNORE INTO znack_duplicate_kiz_code_audit(
                    source_id,shop_id,order_id,raw_code,display_code,gtin,block_id,document_id,status,legal_status,
                    reservation_token,reserved_at,reservation_recoverable,consumed_at,created_at,updated_at,archived_at,reason)
                    SELECT id,shop_id,order_id,raw_code,display_code,gtin,block_id,document_id,status,legal_status,
                    reservation_token,reserved_at,reservation_recoverable,consumed_at,created_at,updated_at,?,'DUPLICATE_RAW_CODE'
                    FROM kiz_codes WHERE id NOT IN (SELECT MIN(id) FROM kiz_codes GROUP BY raw_code)
                    """);
                 Statement delete = c.createStatement()) {
                archive.setString(1, archivedAt);
                archive.executeUpdate();
                delete.executeUpdate("""
                        DELETE FROM kiz_codes
                        WHERE id NOT IN (SELECT MIN(id) FROM kiz_codes GROUP BY raw_code)
                        """);
            }
            LOGGER.warn("Archived and removed {} duplicate KIZ code rows before enforcing global uniqueness.",
                    duplicateCount);
        }
        try (Statement st = c.createStatement()) {
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_kiz_codes_raw_code ON kiz_codes(raw_code)");
        }
    }

    private static void normalizeGtins(Connection c) throws SQLException {
        boolean originalAutoCommit = c.getAutoCommit();
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys=OFF");
            c.setAutoCommit(false);
            st.execute("""
                    UPDATE znack_purchase_pipelines SET stage='QUEUED',error_message=NULL
                    WHERE stage IN ('VALIDATING','CREATING_ORDER','RECONCILING_ORDER','POLLING_ORDER','DOWNLOADING_CODES')
                      AND id NOT IN (
                        SELECT MIN(id) FROM znack_purchase_pipelines
                        WHERE stage IN ('VALIDATING','CREATING_ORDER','RECONCILING_ORDER','POLLING_ORDER','DOWNLOADING_CODES')
                        GROUP BY shop_id,CASE
                          WHEN length(gtin)<14 AND gtin NOT GLOB '*[^0-9]*'
                          THEN substr('00000000000000'||gtin,-14,14) ELSE gtin END
                      )
                    """);
            st.execute("""
                    INSERT INTO znack_products(shop_id,gtin,product_name,tn_ved,certificate_type,certificate_number,
                                               certificate_date,production_date,synced_at)
                    SELECT shop_id,substr('00000000000000'||gtin,-14,14),product_name,tn_ved,certificate_type,
                           certificate_number,certificate_date,production_date,synced_at
                    FROM znack_products
                    WHERE length(gtin)<14 AND gtin NOT GLOB '*[^0-9]*' AND gtin<>''
                    ON CONFLICT(shop_id,gtin) DO UPDATE SET
                      product_name=COALESCE(NULLIF(znack_products.product_name,''),excluded.product_name),
                      tn_ved=COALESCE(NULLIF(znack_products.tn_ved,''),excluded.tn_ved),
                      certificate_type=COALESCE(NULLIF(znack_products.certificate_type,''),excluded.certificate_type),
                      certificate_number=COALESCE(NULLIF(znack_products.certificate_number,''),excluded.certificate_number),
                      certificate_date=COALESCE(NULLIF(znack_products.certificate_date,''),excluded.certificate_date),
                      production_date=COALESCE(NULLIF(znack_products.production_date,''),excluded.production_date),
                      synced_at=MAX(znack_products.synced_at,excluded.synced_at)
                    """);
            for (String table : List.of("kiz_codes", "kiz_orders", "znack_gtin_mapping_rules",
                    "znack_purchase_pipelines")) {
                st.execute("""
                        UPDATE %s SET gtin=substr('00000000000000'||gtin,-14,14)
                        WHERE gtin IS NOT NULL AND gtin<>'' AND length(gtin)<14 AND gtin NOT GLOB '*[^0-9]*'
                        """.formatted(table));
            }
            st.execute("""
                    DELETE FROM znack_products
                    WHERE gtin<>'' AND length(gtin)<14 AND gtin NOT GLOB '*[^0-9]*'
                    """);
            c.commit();
            c.setAutoCommit(originalAutoCommit);
            st.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException e) {
            try { c.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
            try { c.setAutoCommit(originalAutoCommit); } catch (SQLException resetError) { e.addSuppressed(resetError); }
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
            }
            throw e;
        }
    }

    private static void setConfig(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO app_config(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private static int countRows(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            return false;
        }
    }
}
