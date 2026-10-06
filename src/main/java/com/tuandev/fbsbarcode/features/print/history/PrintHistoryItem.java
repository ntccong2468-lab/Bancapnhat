package com.tuandev.fbsbarcode.features.print.history;

public record PrintHistoryItem(
        long printJobId,
        int sortIndex,
        long orderId,
        String brand,
        String name,
        String subjectName,
        String size,
        String ruSize,
        String color,
        String article,
        String barcode,
        String sticker,
        String stickerCode,
        String kiz,
        String imageCacheKey,
        String externalOrderId,
        String externalItemId
) {
    public PrintHistoryItem(
            long printJobId, int sortIndex, long orderId, String brand, String name, String subjectName,
            String size, String ruSize, String color, String article, String barcode, String sticker,
            String stickerCode, String kiz, String imageCacheKey) {
        this(printJobId, sortIndex, orderId, brand, name, subjectName, size, ruSize, color, article,
                barcode, sticker, stickerCode, kiz, imageCacheKey, null, null);
    }
}
