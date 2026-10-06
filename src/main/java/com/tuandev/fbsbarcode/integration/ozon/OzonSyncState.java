package com.tuandev.fbsbarcode.integration.ozon;

public record OzonSyncState(
        int shopId,
        String productsLastId,
        String productsLastSyncedAt,
        String postingsChangedSince,
        String postingsLastSyncedAt,
        String lastError) {
    public static OzonSyncState empty(int shopId) {
        return new OzonSyncState(shopId, "", "", "", "", "");
    }
}
