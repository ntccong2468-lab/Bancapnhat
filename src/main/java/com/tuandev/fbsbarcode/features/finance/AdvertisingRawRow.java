package com.tuandev.fbsbarcode.features.finance;

public record AdvertisingRawRow(
        String sourceKey,
        String businessDate,
        String updateNumber,
        String updateTime,
        String advertisingId,
        String campaignName,
        int advertisingType,
        String paymentType,
        double amount,
        String rawJson
) {
}
