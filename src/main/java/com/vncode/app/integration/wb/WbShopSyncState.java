package com.vncode.app.integration.wb;

public record WbShopSyncState(
        String productsCursorUpdatedAt,
        Long productsCursorNmId,
        String productsLastSyncedAt,
        long suppliesNext,
        String suppliesLastSyncedAt,
        long ordersNext,
        String ordersLastSyncedAt,
        Long ordersWindowFrom,
        Long ordersWindowTo,
        String lastSyncError
) {
}
