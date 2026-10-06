package com.tuandev.fbsbarcode.features.print.history;

public record PrintHistoryJobSummary(
        long id,
        int shopId,
        String shopName,
        String supplyId,
        String supplyName,
        String printedAt,
        int itemCount,
        Integer templateId,
        String templateName,
        String templateLayoutJson,
        String status,
        String errorMessage,
        String marketplace
) {
    public PrintHistoryJobSummary(
            long id, int shopId, String shopName, String supplyId, String supplyName, String printedAt,
            int itemCount, Integer templateId, String templateName, String templateLayoutJson,
            String status, String errorMessage) {
        this(id, shopId, shopName, supplyId, supplyName, printedAt, itemCount, templateId, templateName,
                templateLayoutJson, status, errorMessage, "WILDBERRIES");
    }

    public boolean canReprint() {
        return "success".equalsIgnoreCase(status)
                && "WILDBERRIES".equalsIgnoreCase(marketplace);
    }
}
