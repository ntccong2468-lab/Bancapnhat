package com.tuandev.fbsbarcode.integration.ozon;

public record OzonPostingItemDto(
        int itemIndex,
        String productId,
        String sku,
        String offerId,
        String name,
        int quantity,
        String currencyCode,
        String price) {
    public OzonPostingItemDto {
        if (itemIndex < 0 || quantity <= 0) {
            throw new IllegalArgumentException("Ozon posting item index and quantity must be valid");
        }
        productId = safe(productId, 256);
        sku = safe(sku, 256);
        offerId = safe(offerId, 512);
        name = safe(name, 1000);
        currencyCode = safe(currencyCode, 16);
        price = safe(price, 64);
    }

    private static String safe(String value, int maximum) {
        if (value == null) return "";
        String result = value.replaceAll("\\p{Cntrl}", " ").strip();
        return result.length() <= maximum ? result : result.substring(0, maximum);
    }
}
