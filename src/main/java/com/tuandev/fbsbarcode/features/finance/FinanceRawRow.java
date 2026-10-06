package com.tuandev.fbsbarcode.features.finance;

public record FinanceRawRow(
        String rrdId,
        String reportId,
        String businessDate,
        String currency,
        String docType,
        String operationName,
        String orderId,
        String nmId,
        String vendorCode,
        String sku,
        double quantity,
        boolean returned,
        double retailAmount,
        double forPay,
        double commissionCost,
        double acquiringCost,
        double logisticsCost,
        double storageCost,
        double acceptanceCost,
        double penaltyCost,
        double deductionCost,
        double additionalPayment,
        double otherCost,
        double advertisingCost,
        String rawJson
) {
}
