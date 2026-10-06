package com.tuandev.fbsbarcode.features.finance;

import java.time.LocalDate;

public record FinanceDaily(
        LocalDate date,
        String currency,
        double grossSales,
        double returnsAmount,
        double netPayout,
        double commissionCost,
        double logisticsCost,
        double storageCost,
        double penaltyCost,
        double otherCost,
        double additionalPayment,
        double advertisingCost,
        long orderCount,
        long unitsSold
) {
    public double netProfit() {
        return netPayout - penaltyCost - logisticsCost - storageCost - otherCost
                + additionalPayment - advertisingCost;
    }
}
