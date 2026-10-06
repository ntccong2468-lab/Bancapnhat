package com.tuandev.fbsbarcode.features.finance;

import com.tuandev.fbsbarcode.shared.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** A physically separate SQLite database for low-priority analytics data. */
public final class AnalyticsDatabase {
    public static final String FILE_NAME = "wcode_analytics.db";
    private static final int SCHEMA_VERSION = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyticsDatabase.class);
    private static Path initializedPath;

    private AnalyticsDatabase() {
    }

    public static Path databasePath() {
        return AppPaths.appDataDir().resolve(FILE_NAME);
    }

    public static Connection getConnection() {
        try {
            Files.createDirectories(AppPaths.appDataDir());
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 10000");
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA synchronous = NORMAL");
                statement.execute("PRAGMA temp_store = MEMORY");
            }
            return connection;
        } catch (IOException | SQLException exception) {
            LOGGER.error("Không thể mở analytics database {}", databasePath(), exception);
            throw new IllegalStateException("Không thể mở dữ liệu tài chính", exception);
        }
    }

    public static synchronized void initialize() {
        Path currentPath = databasePath().toAbsolutePath().normalize();
        if (currentPath.equals(initializedPath) && Files.exists(currentPath)) {
            return;
        }
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            int existingVersion = readSchemaVersion(connection);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS finance_raw(
                        shop_id INTEGER NOT NULL,
                        rrd_id TEXT NOT NULL,
                        report_id TEXT,
                        business_date TEXT NOT NULL,
                        currency TEXT NOT NULL DEFAULT 'RUB',
                        doc_type TEXT,
                        operation_name TEXT,
                        order_id TEXT,
                        nm_id TEXT,
                        vendor_code TEXT,
                        sku TEXT,
                        quantity REAL NOT NULL DEFAULT 0,
                        is_return INTEGER NOT NULL DEFAULT 0,
                        retail_amount REAL NOT NULL DEFAULT 0,
                        for_pay REAL NOT NULL DEFAULT 0,
                        commission_cost REAL NOT NULL DEFAULT 0,
                        acquiring_cost REAL NOT NULL DEFAULT 0,
                        logistics_cost REAL NOT NULL DEFAULT 0,
                        storage_cost REAL NOT NULL DEFAULT 0,
                        acceptance_cost REAL NOT NULL DEFAULT 0,
                        penalty_cost REAL NOT NULL DEFAULT 0,
                        deduction_cost REAL NOT NULL DEFAULT 0,
                        additional_payment REAL NOT NULL DEFAULT 0,
                        other_cost REAL NOT NULL DEFAULT 0,
                        advertising_cost REAL NOT NULL DEFAULT 0,
                        raw_json TEXT NOT NULL,
                        synced_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id, rrd_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS advertising_raw(
                        shop_id INTEGER NOT NULL,
                        source_key TEXT NOT NULL,
                        business_date TEXT NOT NULL,
                        update_number TEXT,
                        update_time TEXT,
                        advertising_id TEXT,
                        campaign_name TEXT,
                        advertising_type INTEGER,
                        payment_type TEXT,
                        amount REAL NOT NULL DEFAULT 0,
                        raw_json TEXT NOT NULL,
                        synced_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id, source_key)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS finance_daily(
                        shop_id INTEGER NOT NULL,
                        business_date TEXT NOT NULL,
                        currency TEXT NOT NULL DEFAULT 'RUB',
                        gross_sales REAL NOT NULL DEFAULT 0,
                        returns_amount REAL NOT NULL DEFAULT 0,
                        net_payout REAL NOT NULL DEFAULT 0,
                        commission_cost REAL NOT NULL DEFAULT 0,
                        acquiring_cost REAL NOT NULL DEFAULT 0,
                        logistics_cost REAL NOT NULL DEFAULT 0,
                        storage_cost REAL NOT NULL DEFAULT 0,
                        acceptance_cost REAL NOT NULL DEFAULT 0,
                        penalty_cost REAL NOT NULL DEFAULT 0,
                        deduction_cost REAL NOT NULL DEFAULT 0,
                        additional_payment REAL NOT NULL DEFAULT 0,
                        other_cost REAL NOT NULL DEFAULT 0,
                        advertising_cost REAL NOT NULL DEFAULT 0,
                        order_count INTEGER NOT NULL DEFAULT 0,
                        units_sold INTEGER NOT NULL DEFAULT 0,
                        units_returned INTEGER NOT NULL DEFAULT 0,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id, business_date)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS finance_sync_state(
                        shop_id INTEGER NOT NULL,
                        stream_name TEXT NOT NULL,
                        api_family TEXT NOT NULL,
                        phase TEXT NOT NULL,
                        status TEXT NOT NULL,
                        anchor_date TEXT,
                        window_from TEXT,
                        window_to TEXT,
                        cursor TEXT NOT NULL DEFAULT '0',
                        next_run_at TEXT,
                        next_allowed_at TEXT,
                        last_success_at TEXT,
                        last_error TEXT,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(shop_id, stream_name)
                    )
                    """);

            statement.execute("CREATE INDEX IF NOT EXISTS idx_finance_raw_shop_date ON finance_raw(shop_id, business_date)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_advertising_raw_shop_date ON advertising_raw(shop_id, business_date)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_finance_sync_due ON finance_sync_state(next_run_at, next_allowed_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_finance_sync_family ON finance_sync_state(shop_id, api_family, next_allowed_at)");
            statement.execute("DROP INDEX IF EXISTS idx_finance_raw_shop_order");
            statement.execute("DROP INDEX IF EXISTS idx_finance_daily_shop_date");
            // Older builds could store the complete WB object, including a duplicate KIZ value.
            // Keep the structured raw facts while reducing disk pressure and excluding KIZ from analytics.
            if (existingVersion < 1) {
                statement.execute("UPDATE finance_raw SET raw_json='{}' WHERE raw_json!='{}'");
            }
            if (existingVersion < 2) {
                ensureColumn(connection, "finance_raw", "other_cost", "REAL NOT NULL DEFAULT 0");
                ensureColumn(connection, "finance_raw", "advertising_cost", "REAL NOT NULL DEFAULT 0");
                ensureColumn(connection, "finance_daily", "other_cost", "REAL NOT NULL DEFAULT 0");
                statement.execute("""
                        UPDATE finance_daily
                        SET other_cost=ABS(logistics_cost) + ABS(storage_cost)
                            + ABS(acceptance_cost) + ABS(deduction_cost)
                        """);
            }
            if (existingVersion < 3) {
                // Ozon amount already includes service lines. Normalize historical Ozon rows to the
                // pre-service seller proceeds so daily net profit does not subtract services twice.
                statement.execute("""
                        UPDATE finance_raw
                        SET for_pay=CASE WHEN is_return=1
                                THEN -(ABS(retail_amount) - ABS(commission_cost))
                                ELSE ABS(retail_amount) - ABS(commission_cost) END
                        WHERE rrd_id LIKE 'ozon:%' AND ABS(retail_amount) > 0
                        """);
                statement.execute("""
                        UPDATE finance_daily
                        SET net_payout=COALESCE((
                            SELECT SUM(raw.for_pay) FROM finance_raw raw
                            WHERE raw.shop_id=finance_daily.shop_id
                              AND raw.business_date=finance_daily.business_date
                        ), 0)
                        WHERE EXISTS (
                            SELECT 1 FROM finance_raw raw
                            WHERE raw.shop_id=finance_daily.shop_id
                              AND raw.business_date=finance_daily.business_date
                              AND raw.rrd_id LIKE 'ozon:%'
                        )
                        """);
            }
            if (existingVersion < 4) {
                // Logistics and storage now have their own dashboard KPIs. Keep "other" limited
                // to acceptance/deductions and marketplace-specific unclassified service costs.
                statement.execute("""
                        UPDATE finance_daily
                        SET other_cost=ABS(acceptance_cost) + ABS(deduction_cost)
                            + COALESCE((
                                SELECT SUM(ABS(raw.other_cost)) FROM finance_raw raw
                                WHERE raw.shop_id=finance_daily.shop_id
                                  AND raw.business_date=finance_daily.business_date
                            ), 0)
                        """);
            }
            if (existingVersion < 5) {
                // WB exposes return forPay/commission/acquiring as positive amounts in detailed rows.
                // Apply their accounting sign only in the daily read model and preserve source raw rows.
                statement.execute("""
                        WITH recalculated AS (
                            SELECT shop_id, business_date,
                                   SUM(CASE WHEN is_return=1 THEN -ABS(for_pay) ELSE for_pay END) AS net_payout,
                                   SUM(CASE WHEN is_return=1 THEN -ABS(commission_cost)
                                            ELSE commission_cost END) AS commission_cost,
                                   SUM(CASE WHEN is_return=1 THEN -ABS(acquiring_cost)
                                            ELSE acquiring_cost END) AS acquiring_cost
                            FROM finance_raw
                            GROUP BY shop_id, business_date
                        )
                        UPDATE finance_daily
                        SET (net_payout, commission_cost, acquiring_cost) = (
                            SELECT net_payout, commission_cost, acquiring_cost
                            FROM recalculated
                            WHERE recalculated.shop_id=finance_daily.shop_id
                              AND recalculated.business_date=finance_daily.business_date
                        )
                        WHERE EXISTS (
                            SELECT 1 FROM recalculated
                            WHERE recalculated.shop_id=finance_daily.shop_id
                              AND recalculated.business_date=finance_daily.business_date
                        )
                        """);
            }
            statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            initializedPath = currentPath;
        } catch (SQLException exception) {
            throw new IllegalStateException("Không thể khởi tạo dữ liệu tài chính", exception);
        }
    }

    private static int readSchemaVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static void ensureColumn(Connection connection, String table, String column, String definition)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }
}
