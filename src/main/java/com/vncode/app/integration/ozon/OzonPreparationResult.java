package com.vncode.app.integration.ozon;

public record OzonPreparationResult(
        String postingNumber,
        String stage,
        int exemplarCount,
        boolean shipReady,
        boolean reconciliationRequired,
        String safeErrorCode) {
}
