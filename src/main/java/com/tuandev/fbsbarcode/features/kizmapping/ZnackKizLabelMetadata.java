package com.tuandev.fbsbarcode.features.kizmapping;

public record ZnackKizLabelMetadata(String productName, String gender, String size) {
    public ZnackKizLabelMetadata {
        productName = safe(productName);
        gender = safe(gender);
        size = safe(size);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
