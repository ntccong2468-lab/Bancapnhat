package com.tuandev.fbsbarcode.integration.ozon;

public record OzonSyncReport(int products, int postings, int items) {
    public OzonSyncReport {
        if (products < 0 || postings < 0 || items < 0) {
            throw new IllegalArgumentException("Ozon sync counts must not be negative");
        }
    }
}
