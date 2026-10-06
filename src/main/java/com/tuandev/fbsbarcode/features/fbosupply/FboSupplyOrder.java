package com.tuandev.fbsbarcode.features.fbosupply;

import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;

public record FboSupplyOrder(
        int shopId,
        Marketplace marketplace,
        String orderId,
        String supplyId,
        String orderNumber,
        String rawStatus,
        FboSupplyStatusGroup statusGroup,
        String warehouseName,
        String plannedAt,
        String updatedAt,
        int quantity,
        int acceptedQuantity,
        String detailText) {
    public String displayNumber() {
        if (orderNumber != null && !orderNumber.isBlank()) return orderNumber;
        if (supplyId != null && !supplyId.isBlank()) return supplyId;
        return orderId == null ? "" : orderId;
    }
}
