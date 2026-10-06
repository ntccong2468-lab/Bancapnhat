package com.tuandev.fbsbarcode.features.finance;

import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class FinanceAnalyticsRepository {
    private static final int BATCH_SIZE = 500;

    public void ensureShopState(int shopId, LocalDate anchorDate) {
        ensureShopState(shopId, Marketplace.WILDBERRIES, anchorDate, Instant.now());
    }

    public void ensureShopState(int shopId, Marketplace marketplace, LocalDate anchorDate, Instant now) {
        AnalyticsDatabase.initialize();
        String sql = """
                INSERT OR IGNORE INTO finance_sync_state(
                    shop_id, stream_name, api_family, phase, status, anchor_date,
                    cursor, next_run_at, updated_at
                ) VALUES(?, ?, ?, ?, 'IDLE', ?, '0', ?, ?)
                """;
        try (Connection connection = AnalyticsDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (marketplace == Marketplace.OZON) {
                insertInitialState(statement, shopId, "OZON_FINANCE_RECENT", "OZON_FINANCE",
                        "INITIAL_30", anchorDate, now, now);
                insertInitialState(statement, shopId, "OZON_FINANCE_BACKFILL", "OZON_FINANCE",
                        "WAITING_RECENT", anchorDate, now, now);
                repairOversizedOzonWindow(connection, shopId, now);
            } else {
                insertInitialState(statement, shopId, "FINANCE_RECENT", "FINANCE",
                        "INITIAL_30", anchorDate, now, now);
                insertInitialState(statement, shopId, "ADVERTISING_RECENT", "ADVERTISING",
                        "INITIAL_30", anchorDate, now, now);
                insertInitialState(statement, shopId, "FINANCE_BACKFILL", "FINANCE",
                        "WAITING_RECENT", anchorDate, now, now);
                insertInitialState(statement, shopId, "ADVERTISING_BACKFILL", "ADVERTISING",
                        "WAITING_RECENT", anchorDate, now, now);
                insertInitialState(statement, shopId, "FINANCE_WEEKLY", "FINANCE",
                        "WEEKLY_RECONCILIATION", anchorDate, WeeklyFinanceSchedule.firstDueAt(now), now);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Không thể tạo checkpoint tài chính", exception);
        }
    }

    private void repairOversizedOzonWindow(Connection connection, int shopId, Instant now) throws SQLException {
        String sql = """
                UPDATE finance_sync_state
                SET window_from=date(window_to, '-27 days'), cursor='0', status='IDLE',
                    next_run_at=?, next_allowed_at=NULL, last_error=NULL, updated_at=?
                WHERE shop_id=? AND api_family='OZON_FINANCE'
                  AND window_from IS NOT NULL AND window_to IS NOT NULL
                  AND julianday(window_to) - julianday(window_from) >= 28
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, now.toString());
            statement.setString(2, now.toString());
            statement.setInt(3, shopId);
            statement.executeUpdate();
        }
    }

    private void insertInitialState(PreparedStatement statement, int shopId, String stream, String api,
                                    String phase, LocalDate anchor, Instant nextRunAt, Instant now) throws SQLException {
        statement.setInt(1, shopId);
        statement.setString(2, stream);
        statement.setString(3, api);
        statement.setString(4, phase);
        statement.setString(5, anchor.toString());
        statement.setString(6, nextRunAt.toString());
        statement.setString(7, now.toString());
        statement.executeUpdate();
    }

    public List<FinanceSyncState> loadStates(int shopId) {
        AnalyticsDatabase.initialize();
        String sql = """
                SELECT shop_id, stream_name, api_family, phase, status, anchor_date,
                       window_from, window_to, cursor, next_run_at, next_allowed_at,
                       last_success_at, last_error
                FROM finance_sync_state WHERE shop_id=? ORDER BY stream_name
                """;
        List<FinanceSyncState> states = new ArrayList<>();
        try (Connection connection = AnalyticsDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shopId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    states.add(readState(result));
                }
            }
            return states;
        } catch (SQLException exception) {
            throw new IllegalStateException("Không thể đọc checkpoint tài chính", exception);
        }
    }

    public FinanceSyncState loadState(int shopId, String streamName) {
        return loadStates(shopId).stream()
                .filter(state -> streamName.equals(state.streamName()))
                .findFirst()
                .orElse(null);
    }

    public Instant familyNextAllowedAt(int shopId, String apiFamily) {
        AnalyticsDatabase.initialize();
        String sql = """
                SELECT MAX(next_allowed_at) FROM finance_sync_state
                WHERE shop_id=? AND api_family=?
                """;
        try (Connection connection = AnalyticsDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shopId);
            statement.setString(2, apiFamily);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? instant(result.getString(1)) : null;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Không thể đọc giới hạn API", exception);
        }
    }

    public void startWindow(FinanceSyncState state, String phase, LocalDate from, LocalDate to, Instant now) {
        updateState(state.shopId(), state.streamName(), phase, "IN_PROGRESS", from, to, "0",
                now, state.nextAllowedAt(), state.lastSuccessAt(), null);
    }

    public void markAttempt(int shopId, String streamName, Instant nextAllowedAt) {
        AnalyticsDatabase.initialize();
        String sql = """
                UPDATE finance_sync_state
                SET next_allowed_at=?, status='IN_PROGRESS', updated_at=?
                WHERE shop_id=? AND stream_name=?
                """;
        try (Connection connection = AnalyticsDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, nextAllowedAt.toString());
            statement.setString(2, Instant.now().toString());
            statement.setInt(3, shopId);
            statement.setString(4, streamName);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Không thể lưu giới hạn API", exception);
        }
    }

    public void saveProgress(FinanceSyncState state, String cursor, Instant nextRunAt, Instant successAt) {
        updateState(state.shopId(), state.streamName(), state.phase(), "IN_PROGRESS",
                state.windowFrom(), state.windowTo(), cursor, nextRunAt, nextRunAt, successAt, null);
    }

    public void completeWindow(FinanceSyncState state, String nextPhase, String nextStatus,
                               LocalDate nextFrom, LocalDate nextTo, Instant nextRunAt, Instant successAt) {
        updateState(state.shopId(), state.streamName(), nextPhase, nextStatus, nextFrom, nextTo, "0",
                nextRunAt, state.nextAllowedAt(), successAt, null);
    }

    public void markError(FinanceSyncState state, Instant retryAt, String message) {
        updateState(state.shopId(), state.streamName(), state.phase(), "ERROR",
                state.windowFrom(), state.windowTo(), state.cursor(), retryAt, retryAt,
                state.lastSuccessAt(), compactError(message));
    }

    public void requestRecentSync(int shopId, Instant now) {
        AnalyticsDatabase.initialize();
        String sql = """
                UPDATE finance_sync_state SET next_run_at=?, updated_at=?
                WHERE shop_id=? AND stream_name IN ('FINANCE_RECENT','ADVERTISING_RECENT','OZON_FINANCE_RECENT')
                  AND phase='PERIODIC_3' AND status!='IN_PROGRESS'
                """;
        try (Connection connection = AnalyticsDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, now.toString());
            statement.setString(2, now.toString());
            statement.setInt(3, shopId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Không thể lên lịch đồng bộ tài chính", exception);
        }
    }

    private void updateState(int shopId, String streamName, String phase, String status,
                             LocalDate from, LocalDate to, String cursor, Instant nextRunAt,
                             Instant nextAllowedAt, Instant lastSuccessAt, String lastError) {
        AnalyticsDatabase.initialize();
        String sql = """
                UPDATE finance_sync_state SET phase=?, status=?, window_from=?, window_to=?, cursor=?,
                    next_run_at=?, next_allowed_at=?, last_success_at=?, last_error=?, updated_at=?
                WHERE shop_id=? AND stream_name=?
                """;
        try (Connection connection = AnalyticsDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, phase);
            statement.setString(2, status);
            statement.setString(3, text(from));
            statement.setString(4, text(to));
            statement.setString(5, cursor == null || cursor.isBlank() ? "0" : cursor);
            statement.setString(6, text(nextRunAt));
            statement.setString(7, text(nextAllowedAt));
            statement.setString(8, text(lastSuccessAt));
            statement.setString(9, lastError);
            statement.setString(10, Instant.now().toString());
            statement.setInt(11, shopId);
            statement.setString(12, streamName);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Không thể lưu checkpoint tài chính", exception);
        }
    }

    public void upsertFinanceRows(int shopId, Collection<FinanceRawRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        AnalyticsDatabase.initialize();
        String sql = """
                INSERT INTO finance_raw(
                    shop_id, rrd_id, report_id, business_date, currency, doc_type, operation_name,
                    order_id, nm_id, vendor_code, sku, quantity, is_return, retail_amount, for_pay,
                    commission_cost, acquiring_cost, logistics_cost, storage_cost, acceptance_cost,
                    penalty_cost, deduction_cost, additional_payment, other_cost, advertising_cost,
                    raw_json, synced_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(shop_id, rrd_id) DO UPDATE SET
                    report_id=excluded.report_id, business_date=excluded.business_date,
                    currency=excluded.currency, doc_type=excluded.doc_type,
                    operation_name=excluded.operation_name, order_id=excluded.order_id,
                    nm_id=excluded.nm_id, vendor_code=excluded.vendor_code, sku=excluded.sku,
                    quantity=excluded.quantity, is_return=excluded.is_return,
                    retail_amount=excluded.retail_amount, for_pay=excluded.for_pay,
                    commission_cost=excluded.commission_cost, acquiring_cost=excluded.acquiring_cost,
                    logistics_cost=excluded.logistics_cost, storage_cost=excluded.storage_cost,
                    acceptance_cost=excluded.acceptance_cost, penalty_cost=excluded.penalty_cost,
                    deduction_cost=excluded.deduction_cost, additional_payment=excluded.additional_payment,
                    other_cost=excluded.other_cost, advertising_cost=excluded.advertising_cost,
                    raw_json=excluded.raw_json, synced_at=excluded.synced_at
                """;
        Set<String> affectedDates = new LinkedHashSet<>();
        try (Connection connection = AnalyticsDatabase.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int pending = 0;
                for (FinanceRawRow row : rows) {
                    bindFinance(statement, shopId, row);
                    statement.addBatch();
                    affectedDates.add(row.businessDate());
                    if (++pending % BATCH_SIZE == 0) {
                        statement.executeBatch();
                    }
                }
                if (pending % BATCH_SIZE != 0) {
                    statement.executeBatch();
                }
                recomputeDays(connection, shopId, affectedDates);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Không thể lưu dữ liệu tài chính", exception);
        }
    }

    public void upsertAdvertisingRows(int shopId, Collection<AdvertisingRawRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        AnalyticsDatabase.initialize();
        String sql = """
                INSERT INTO advertising_raw(
                    shop_id, source_key, business_date, update_number, update_time, advertising_id,
                    campaign_name, advertising_type, payment_type, amount, raw_json, synced_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(shop_id, source_key) DO UPDATE SET
                    business_date=excluded.business_date, update_number=excluded.update_number,
                    update_time=excluded.update_time, advertising_id=excluded.advertising_id,
                    campaign_name=excluded.campaign_name, advertising_type=excluded.advertising_type,
                    payment_type=excluded.payment_type, amount=excluded.amount,
                    raw_json=excluded.raw_json, synced_at=excluded.synced_at
                """;
        Set<String> affectedDates = new LinkedHashSet<>();
        try (Connection connection = AnalyticsDatabase.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int pending = 0;
                for (AdvertisingRawRow row : rows) {
                    bindAdvertising(statement, shopId, row);
                    statement.addBatch();
                    affectedDates.add(row.businessDate());
                    if (++pending % BATCH_SIZE == 0) {
                        statement.executeBatch();
                    }
                }
                if (pending % BATCH_SIZE != 0) {
                    statement.executeBatch();
                }
                recomputeDays(connection, shopId, affectedDates);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Không thể lưu chi phí quảng cáo", exception);
        }
    }

    private void recomputeDays(Connection connection, int shopId, Collection<String> dates) throws SQLException {
        String sql = """
                INSERT INTO finance_daily(
                    shop_id, business_date, currency, gross_sales, returns_amount, net_payout,
                    commission_cost, acquiring_cost, logistics_cost, storage_cost, acceptance_cost,
                    penalty_cost, deduction_cost, additional_payment, other_cost, advertising_cost,
                    order_count, units_sold, units_returned, updated_at
                )
                SELECT ?, ?, f.currency, f.gross_sales, f.returns_amount, f.net_payout,
                       f.commission_cost, f.acquiring_cost, f.logistics_cost, f.storage_cost,
                       f.acceptance_cost, f.penalty_cost, f.deduction_cost, f.additional_payment,
                       f.other_cost, f.advertising_cost + a.advertising_cost,
                       f.order_count, f.units_sold, f.units_returned, ?
                FROM (
                    SELECT COALESCE(NULLIF(MAX(currency), ''), 'RUB') AS currency,
                           COALESCE(SUM(CASE WHEN is_return=0 THEN ABS(retail_amount) ELSE 0 END), 0) AS gross_sales,
                           COALESCE(SUM(CASE WHEN is_return=1 THEN ABS(retail_amount) ELSE 0 END), 0) AS returns_amount,
                           COALESCE(SUM(CASE WHEN is_return=1 THEN -ABS(for_pay)
                                ELSE for_pay END), 0) AS net_payout,
                           COALESCE(SUM(CASE WHEN is_return=1 THEN -ABS(commission_cost)
                                ELSE commission_cost END), 0) AS commission_cost,
                           COALESCE(SUM(CASE WHEN is_return=1 THEN -ABS(acquiring_cost)
                                ELSE acquiring_cost END), 0) AS acquiring_cost,
                           COALESCE(SUM(ABS(logistics_cost)), 0) AS logistics_cost,
                           COALESCE(SUM(ABS(storage_cost)), 0) AS storage_cost,
                           COALESCE(SUM(ABS(acceptance_cost)), 0) AS acceptance_cost,
                           COALESCE(SUM(ABS(penalty_cost)), 0) AS penalty_cost,
                           COALESCE(SUM(ABS(deduction_cost)), 0) AS deduction_cost,
                           COALESCE(SUM(additional_payment), 0) AS additional_payment,
                           COALESCE(SUM(ABS(other_cost) + ABS(acceptance_cost)
                               + ABS(deduction_cost)), 0) AS other_cost,
                           COALESCE(SUM(ABS(advertising_cost)), 0) AS advertising_cost,
                           COUNT(DISTINCT CASE WHEN is_return=0 THEN order_id END) AS order_count,
                           CAST(COALESCE(SUM(CASE WHEN is_return=0 THEN ABS(quantity) ELSE 0 END), 0) AS INTEGER) AS units_sold,
                           CAST(COALESCE(SUM(CASE WHEN is_return=1 THEN ABS(quantity) ELSE 0 END), 0) AS INTEGER) AS units_returned
                    FROM finance_raw WHERE shop_id=? AND business_date=?
                ) f
                CROSS JOIN (
                    SELECT COALESCE(SUM(ABS(amount)), 0) AS advertising_cost
                    FROM advertising_raw WHERE shop_id=? AND business_date=?
                ) a
                WHERE 1=1
                ON CONFLICT(shop_id, business_date) DO UPDATE SET
                    currency=excluded.currency, gross_sales=excluded.gross_sales,
                    returns_amount=excluded.returns_amount, net_payout=excluded.net_payout,
                    commission_cost=excluded.commission_cost, acquiring_cost=excluded.acquiring_cost,
                    logistics_cost=excluded.logistics_cost, storage_cost=excluded.storage_cost,
                    acceptance_cost=excluded.acceptance_cost, penalty_cost=excluded.penalty_cost,
                    deduction_cost=excluded.deduction_cost, additional_payment=excluded.additional_payment,
                    other_cost=excluded.other_cost,
                    advertising_cost=excluded.advertising_cost, order_count=excluded.order_count,
                    units_sold=excluded.units_sold, units_returned=excluded.units_returned,
                    updated_at=excluded.updated_at
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String date : dates) {
                int index = 1;
                statement.setInt(index++, shopId);
                statement.setString(index++, date);
                statement.setString(index++, Instant.now().toString());
                statement.setInt(index++, shopId);
                statement.setString(index++, date);
                statement.setInt(index++, shopId);
                statement.setString(index, date);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public void deleteShopData(int shopId) {
        AnalyticsDatabase.initialize();
        try (Connection connection = AnalyticsDatabase.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement finance = connection.prepareStatement("DELETE FROM finance_raw WHERE shop_id=?");
                 PreparedStatement advertising = connection.prepareStatement("DELETE FROM advertising_raw WHERE shop_id=?");
                 PreparedStatement daily = connection.prepareStatement("DELETE FROM finance_daily WHERE shop_id=?");
                 PreparedStatement state = connection.prepareStatement("DELETE FROM finance_sync_state WHERE shop_id=?")) {
                for (PreparedStatement statement : List.of(finance, advertising, daily, state)) {
                    statement.setInt(1, shopId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Không thể xoá dữ liệu tài chính của cửa hàng", exception);
        }
    }

    private void bindFinance(PreparedStatement statement, int shopId, FinanceRawRow row) throws SQLException {
        int index = 1;
        statement.setInt(index++, shopId);
        statement.setString(index++, row.rrdId());
        statement.setString(index++, row.reportId());
        statement.setString(index++, row.businessDate());
        statement.setString(index++, row.currency());
        statement.setString(index++, row.docType());
        statement.setString(index++, row.operationName());
        statement.setString(index++, row.orderId());
        statement.setString(index++, row.nmId());
        statement.setString(index++, row.vendorCode());
        statement.setString(index++, row.sku());
        statement.setDouble(index++, row.quantity());
        statement.setInt(index++, row.returned() ? 1 : 0);
        statement.setDouble(index++, row.retailAmount());
        statement.setDouble(index++, row.forPay());
        statement.setDouble(index++, row.commissionCost());
        statement.setDouble(index++, row.acquiringCost());
        statement.setDouble(index++, row.logisticsCost());
        statement.setDouble(index++, row.storageCost());
        statement.setDouble(index++, row.acceptanceCost());
        statement.setDouble(index++, row.penaltyCost());
        statement.setDouble(index++, row.deductionCost());
        statement.setDouble(index++, row.additionalPayment());
        statement.setDouble(index++, row.otherCost());
        statement.setDouble(index++, row.advertisingCost());
        statement.setString(index++, row.rawJson());
        statement.setString(index, Instant.now().toString());
    }

    private void bindAdvertising(PreparedStatement statement, int shopId, AdvertisingRawRow row) throws SQLException {
        int index = 1;
        statement.setInt(index++, shopId);
        statement.setString(index++, row.sourceKey());
        statement.setString(index++, row.businessDate());
        statement.setString(index++, row.updateNumber());
        statement.setString(index++, row.updateTime());
        statement.setString(index++, row.advertisingId());
        statement.setString(index++, row.campaignName());
        statement.setInt(index++, row.advertisingType());
        statement.setString(index++, row.paymentType());
        statement.setDouble(index++, row.amount());
        statement.setString(index++, row.rawJson());
        statement.setString(index, Instant.now().toString());
    }

    private FinanceSyncState readState(ResultSet result) throws SQLException {
        return new FinanceSyncState(
                result.getInt("shop_id"), result.getString("stream_name"), result.getString("api_family"),
                result.getString("phase"), result.getString("status"), date(result.getString("anchor_date")),
                date(result.getString("window_from")), date(result.getString("window_to")),
                result.getString("cursor"), instant(result.getString("next_run_at")),
                instant(result.getString("next_allowed_at")), instant(result.getString("last_success_at")),
                result.getString("last_error"));
    }

    private static String compactError(String message) {
        if (message == null) return null;
        String compact = message.replaceAll("\\s+", " ").strip();
        return compact.length() <= 500 ? compact : compact.substring(0, 500);
    }

    private static LocalDate date(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
