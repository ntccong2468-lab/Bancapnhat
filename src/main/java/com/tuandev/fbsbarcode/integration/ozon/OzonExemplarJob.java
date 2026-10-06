package com.tuandev.fbsbarcode.integration.ozon;

public record OzonExemplarJob(
        long id,
        int shopId,
        String postingNumber,
        OzonExemplarJobStage stage,
        String requestFingerprint,
        String safeErrorCode,
        String mutationAttemptedAt,
        String createdAt,
        String updatedAt) {
}
