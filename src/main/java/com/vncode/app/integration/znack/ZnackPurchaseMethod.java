package com.vncode.app.integration.znack;

import java.util.Locale;

/** No method is inferred again from mutable shop preferences after a purchase is paid. */
public enum ZnackPurchaseMethod {
    PRODUCTION("LP_INTRODUCE_GOODS"), COMMISSION("LK_INDI_COMMISSIONING");
    private final String documentType;
    ZnackPurchaseMethod(String documentType) { this.documentType = documentType; }
    public String documentType() { return documentType; }
    public String introductionDocumentType() { return documentType; }
    public boolean requiresProductionMetadata() { return this == PRODUCTION; }
    public static ZnackPurchaseMethod fromCode(String value) {
        if (value == null || value.isBlank()) return PRODUCTION;
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Unsupported purchase method", error); }
    }
    public static ZnackPurchaseMethod fromStored(String value) { return fromCode(value); }
}
