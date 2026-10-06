package com.tuandev.fbsbarcode.features.fbo;

public record FboBarcodePrintItem(FboProductSku product, int quantity) {
    public int pageCount() {
        int pagesPerUnit = product != null && product.requiresKiz() ? 3 : 2;
        return Math.max(0, quantity) * pagesPerUnit;
    }
}
