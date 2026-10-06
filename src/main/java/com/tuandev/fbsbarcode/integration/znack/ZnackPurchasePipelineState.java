package com.tuandev.fbsbarcode.integration.znack;

import com.tuandev.fbsbarcode.integration.znack.ZnackModels.PurchaseStage;

import java.time.Instant;

public record ZnackPurchasePipelineState(long id, int shopId, String gtin, int quantity, Long orderId,
                                         PurchaseStage stage, String errorMessage, Instant createdAt,
                                         Instant updatedAt) {
    public boolean active() {
        return stage != PurchaseStage.COMPLETED && stage != PurchaseStage.INTRODUCED
                && stage != PurchaseStage.FAILED
                && stage != PurchaseStage.INTRODUCTION_FAILED
                && stage != PurchaseStage.INTRODUCTION_SKIPPED_MISSING_DOCUMENTS
                && stage != PurchaseStage.INTRODUCTION_SKIPPED_MISSING_METADATA
                && stage != PurchaseStage.WAITING_INTRODUCTION_DOCUMENTS;
    }
}
