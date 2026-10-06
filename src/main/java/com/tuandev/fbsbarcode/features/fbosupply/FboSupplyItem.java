package com.tuandev.fbsbarcode.features.fbosupply;

public record FboSupplyItem(
        String itemKey,
        String imageUrl,
        String name,
        String article,
        String sku,
        String barcode,
        String size,
        String color,
        int quantity,
        int acceptedQuantity,
        Boolean requiresKiz) {
}
