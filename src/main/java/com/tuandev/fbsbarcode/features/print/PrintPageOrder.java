package com.tuandev.fbsbarcode.features.print;

public enum PrintPageOrder {
    BARCODE_THEN_STICKER("print_options.page_order.barcode_then_sticker"),
    STICKER_THEN_BARCODE("print_options.page_order.sticker_then_barcode");

    private final String key;

    PrintPageOrder(String key) {
        this.key = key;
    }

    public String label() {
        return com.tuandev.fbsbarcode.shared.I18nService.getInstance().tr(key);
    }

    @Override
    public String toString() {
        return label();
    }
}
