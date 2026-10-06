package com.tuandev.fbsbarcode.features.supply;

import com.tuandev.fbsbarcode.shared.ConfigService;

public class OrderSortPreferenceService {
    private static final String KEY = "supply_order_sort_options";

    public OrderSortOptions load() {
        String value = ConfigService.getConfigValue(KEY);
        if (value == null || value.isBlank()) {
            return OrderSortOptions.defaultOptions();
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 4) {
            return OrderSortOptions.defaultOptions();
        }
        return new OrderSortOptions(
                parse(parts[0], true),
                parse(parts[1], true),
                parse(parts[2], true),
                parse(parts[3], true)
        );
    }

    public void save(OrderSortOptions options) {
        OrderSortOptions safe = options == null ? OrderSortOptions.defaultOptions() : options;
        String value = safe.bySubject() + "," + safe.byArticle() + "," + safe.byColor() + "," + safe.bySize();
        ConfigService.setConfigValue(KEY, value);
    }

    private boolean parse(String value, boolean fallback) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return fallback;
    }
}
