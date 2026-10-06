package com.tuandev.fbsbarcode.features.ozon;

import com.tuandev.fbsbarcode.integration.ozon.OzonPostingItemDto;
import com.tuandev.fbsbarcode.integration.ozon.OzonProductDto;

/** Product grouping fields loaded from the synchronized Ozon product card. */
public record OzonProductVariant(String product, String article, String color, String size) {
    public static OzonProductVariant from(OzonPostingItemDto item) {
        if (item == null) return new OzonProductVariant("", "", "", "");
        return new OzonProductVariant(first(item.name(), item.sku()), first(item.offerId(), item.sku()), "", "");
    }

    public static OzonProductVariant from(OzonPostingItemDto item, OzonProductDto productCard) {
        if (item == null) return new OzonProductVariant("", "", "", "");
        if (productCard == null) return from(item);
        return new OzonProductVariant(
                first(productCard.name(), first(item.name(), item.sku())),
                first(productCard.article(), first(item.offerId(), item.sku())),
                safe(productCard.color()), safe(productCard.size()));
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? (fallback == null ? "" : fallback) : preferred;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
