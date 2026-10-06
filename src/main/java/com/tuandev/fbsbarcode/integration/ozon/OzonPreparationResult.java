package com.tuandev.fbsbarcode.integration.ozon;

public record OzonPreparationResult(
        String postingNumber,
        String stage,
        int exemplarCount,
        boolean shipReady,
        boolean reconciliationRequired,
        String safeErrorCode) {
}
