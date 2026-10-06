package com.tuandev.fbsbarcode.features.fbo;

public record FboProductSku(
        long nmId,
        String vendorCode,
        String subjectName,
        String brand,
        String title,
        String color,
        String size,
        String ruSize,
        String sku,
        String imageUrl,
        boolean requiresKiz,
        String catalogSku
) {
    public FboProductSku {
        catalogSku = catalogSku == null || catalogSku.isBlank() ? sku : catalogSku.trim();
    }

    public FboProductSku(
            long nmId,
            String vendorCode,
            String subjectName,
            String brand,
            String title,
            String color,
            String size,
            String ruSize,
            String sku,
            String imageUrl,
            boolean requiresKiz
    ) {
        this(nmId, vendorCode, subjectName, brand, title, color, size, ruSize,
                sku, imageUrl, requiresKiz, sku);
    }
}
