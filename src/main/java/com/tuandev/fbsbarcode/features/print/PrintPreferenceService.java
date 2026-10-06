package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.shared.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrintPreferenceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PrintPreferenceService.class);
    private static final String PAGE_ORDER_KEY = "print_page_order";
    private static final String BARCODE_COPIES_KEY = "print_barcode_copies";

    public PrintJobOptions load() {
        PrintPageOrder pageOrder = parsePageOrder(ConfigService.getConfigValue(PAGE_ORDER_KEY));
        int barcodeCopies = parsePositiveInt(ConfigService.getConfigValue(BARCODE_COPIES_KEY), 1);
        return new PrintJobOptions(pageOrder, barcodeCopies).normalized();
    }

    public void save(PrintJobOptions options) {
        PrintJobOptions normalized = options == null ? PrintJobOptions.defaults() : options.normalized();
        ConfigService.setConfigValue(PAGE_ORDER_KEY, normalized.pageOrder().name());
        ConfigService.setConfigValue(BARCODE_COPIES_KEY, String.valueOf(normalized.barcodeCopies()));
    }

    private PrintPageOrder parsePageOrder(String value) {
        if (value == null || value.isBlank()) {
            return PrintPageOrder.BARCODE_THEN_STICKER;
        }
        try {
            return PrintPageOrder.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            LOGGER.warn("Invalid print page order config: {}", value);
            return PrintPageOrder.BARCODE_THEN_STICKER;
        }
    }

    private int parsePositiveInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ex) {
            LOGGER.warn("Invalid positive integer config: {}", value);
            return fallback;
        }
    }
}
