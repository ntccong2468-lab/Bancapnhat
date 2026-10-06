package com.tuandev.fbsbarcode.features.finance;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinanceDashboardSnapshotTest {
    @Test
    void aggregatesCommissionAndCalculatesSharesAgainstSelectedRevenue() {
        FinanceDashboardSnapshot snapshot = FinanceDashboardSnapshot.fromDays(List.of(
                day(LocalDate.of(2026, 8, 24), 1_000, 100, 700, 120),
                day(LocalDate.of(2026, 8, 25), 500, 50, 350, 60)));

        assertEquals(180, snapshot.commissionCost(), 0.001);
        assertEquals(100, snapshot.percentageOfRevenue(snapshot.grossSales()), 0.001);
        assertEquals(12, snapshot.percentageOfRevenue(snapshot.commissionCost()), 0.001);
        assertEquals(-10, snapshot.percentageOfRevenue(-150), 0.001);
    }

    @Test
    void doesNotInventAPercentageWhenSelectedRevenueIsZero() {
        FinanceDashboardSnapshot snapshot = FinanceDashboardSnapshot.fromDays(List.of());

        assertTrue(Double.isNaN(snapshot.percentageOfRevenue(10)));
    }

    private static FinanceDaily day(LocalDate date, double gross, double returns,
                                    double payout, double commission) {
        return new FinanceDaily(date, "RUB", gross, returns, payout, commission,
                20, 5, 3, 2, 0, 10, 1, 1);
    }
}
