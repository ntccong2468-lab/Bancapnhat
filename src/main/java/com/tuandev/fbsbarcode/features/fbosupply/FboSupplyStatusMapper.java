package com.tuandev.fbsbarcode.features.fbosupply;

import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;

import java.util.Locale;

public final class FboSupplyStatusMapper {
    private FboSupplyStatusMapper() {
    }

    public static FboSupplyStatusGroup map(Marketplace marketplace, String rawStatus) {
        String status = rawStatus == null ? "" : rawStatus.strip().toUpperCase(Locale.ROOT);
        if (marketplace == Marketplace.WILDBERRIES) {
            return switch (status) {
                case "1" -> FboSupplyStatusGroup.PREPARING;
                case "2", "3" -> FboSupplyStatusGroup.READY;
                case "4", "6" -> FboSupplyStatusGroup.IN_PROGRESS;
                case "5" -> FboSupplyStatusGroup.COMPLETED;
                default -> FboSupplyStatusGroup.UNKNOWN;
            };
        }
        return switch (status) {
            case "DATA_FILLING" -> FboSupplyStatusGroup.PREPARING;
            case "READY_TO_SUPPLY" -> FboSupplyStatusGroup.READY;
            case "ACCEPTED_AT_SUPPLY_WAREHOUSE", "IN_TRANSIT", "ACCEPTANCE_AT_STORAGE_WAREHOUSE" ->
                    FboSupplyStatusGroup.IN_PROGRESS;
            case "REPORTS_CONFIRMATION_AWAITING" -> FboSupplyStatusGroup.REVIEW;
            case "REPORT_REJECTED", "REJECTED_AT_SUPPLY_WAREHOUSE", "OVERDUE" -> FboSupplyStatusGroup.ISSUE;
            case "COMPLETED" -> FboSupplyStatusGroup.COMPLETED;
            case "CANCELLED" -> FboSupplyStatusGroup.CANCELLED;
            default -> FboSupplyStatusGroup.UNKNOWN;
        };
    }
}
