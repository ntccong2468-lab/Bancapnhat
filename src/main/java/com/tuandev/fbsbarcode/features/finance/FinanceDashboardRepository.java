package com.tuandev.fbsbarcode.features.finance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Read model for the UI. This repository intentionally reads finance_daily only. */
public class FinanceDashboardRepository {
    public FinanceDashboardSnapshot load(int shopId, LocalDate from, LocalDate to) {
        AnalyticsDatabase.initialize();
        String sql = """
                SELECT business_date, currency, gross_sales, returns_amount, net_payout,
                       commission_cost, logistics_cost, storage_cost, penalty_cost,
                       other_cost, additional_payment, advertising_cost, order_count, units_sold
                FROM finance_daily
                WHERE shop_id=? AND business_date BETWEEN ? AND ?
                ORDER BY business_date DESC
                """;
        List<FinanceDaily> days = new ArrayList<>();
        try (Connection connection = AnalyticsDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shopId);
            statement.setString(2, from.toString());
            statement.setString(3, to.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    days.add(new FinanceDaily(
                            LocalDate.parse(result.getString("business_date")),
                            result.getString("currency"),
                            result.getDouble("gross_sales"),
                            result.getDouble("returns_amount"),
                            result.getDouble("net_payout"),
                            result.getDouble("commission_cost"),
                            result.getDouble("logistics_cost"),
                            result.getDouble("storage_cost"),
                            result.getDouble("penalty_cost"),
                            result.getDouble("other_cost"),
                            result.getDouble("additional_payment"),
                            result.getDouble("advertising_cost"),
                            result.getLong("order_count"),
                            result.getLong("units_sold")));
                }
            }
            return FinanceDashboardSnapshot.fromDays(days);
        } catch (SQLException exception) {
            throw new IllegalStateException("Không thể đọc dashboard tài chính", exception);
        }
    }
}
