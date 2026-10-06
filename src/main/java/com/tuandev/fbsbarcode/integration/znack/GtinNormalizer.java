package com.tuandev.fbsbarcode.integration.znack;

public final class GtinNormalizer {
    public static final String TECHNICAL_GTIN_PURCHASE_UNSUPPORTED =
            "Technical GTINs in the 0290-0299 range cannot be used for PRODUCTION KIZ orders.";

    private GtinNormalizer() {
    }

    public static String normalize(String value) {
        String gtin = value == null ? "" : value.trim();
        if (gtin.isEmpty() || !gtin.chars().allMatch(digit -> digit >= '0' && digit <= '9') || gtin.length() > 14) {
            throw new IllegalArgumentException("GTIN must contain no more than 14 digits.");
        }
        return "0".repeat(14 - gtin.length()) + gtin;
    }

    public static boolean isTechnicalRange(String value) {
        return normalize(value).startsWith("029");
    }

    public static String requireProductionOrderable(String value) {
        String gtin = normalize(value);
        if (isTechnicalRange(gtin)) {
            throw new IllegalArgumentException(TECHNICAL_GTIN_PURCHASE_UNSUPPORTED);
        }
        return gtin;
    }
}
