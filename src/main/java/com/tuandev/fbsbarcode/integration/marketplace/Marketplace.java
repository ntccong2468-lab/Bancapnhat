package com.tuandev.fbsbarcode.integration.marketplace;

import java.util.Locale;

/** Marketplace identity is persisted with the shop and must never be inferred from credentials. */
public enum Marketplace {
    WILDBERRIES,
    OZON;

    public static Marketplace fromDatabase(String value) {
        if (value == null || value.isBlank()) {
            return WILDBERRIES;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unsupported marketplace stored for shop", exception);
        }
    }

    public String badge() {
        return this == OZON ? "Ozon" : "WB";
    }
}
