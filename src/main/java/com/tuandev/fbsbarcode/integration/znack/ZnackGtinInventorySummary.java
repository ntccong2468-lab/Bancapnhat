package com.tuandev.fbsbarcode.integration.znack;

import java.time.Instant;

public record ZnackGtinInventorySummary(String gtin, String productName, String category, int available,
                                        int reserved, int consumed, int discardable, int mappingRuleCount,
                                        String latestOrderStatus, Long latestPipelineId, String latestPipelineStage,
                                        String latestError, Instant syncedAt) {

    /** Case-insensitive match against the GTIN, product name or category; {@code query} must be lower-case. */
    public boolean matchesSearch(String query) {
        return contains(gtin, query) || contains(productName, query) || contains(category, query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(query);
    }
}
