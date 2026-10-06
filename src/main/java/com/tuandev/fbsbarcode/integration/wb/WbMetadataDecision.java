package com.tuandev.fbsbarcode.integration.wb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record WbMetadataDecision(long orderId, List<String> missingRequiredMeta, List<String> invalidMeta) {
    private static final Set<String> DELIVERY_RELEVANT_KEYS = Set.of("imei", "uin", "sgtin", "gtin");

    public static WbMetadataDecision from(WbOrderMetaDetailsResponse.OrderMetadata order) {
        long orderId = order == null || order.getOrderId() == null ? 0L : order.getOrderId();
        if (order == null) {
            return new WbMetadataDecision(orderId, List.of(), List.of());
        }

        List<String> missing = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        if (order.getMetaDetails().isEmpty()) {
            for (String legacyKey : order.getRequiredMeta()) {
                String key = normalizeKey(legacyKey);
                if (DELIVERY_RELEVANT_KEYS.contains(key)) {
                    missing.add(key);
                }
            }
        }

        for (WbOrderMetaDetailsResponse.MetaDetail detail : order.getMetaDetails()) {
            String key = normalizeKey(detail.getKey());
            if (!DELIVERY_RELEVANT_KEYS.contains(key)) {
                continue;
            }
            boolean required = Boolean.TRUE.equals(detail.getRequired())
                    || "required".equalsIgnoreCase(nullToEmpty(detail.getRequirementType()))
                    || order.getRequiredMeta().stream().map(WbMetadataDecision::normalizeKey).anyMatch(key::equals);
            if (!required) {
                continue;
            }
            boolean hasValue = detail.getValue() != null && !detail.getValue().isBlank();
            boolean filled = detail.getFilled() == null ? hasValue : detail.getFilled();
            boolean valid = detail.getValid() == null || detail.getValid();
            String status = normalizeKey(detail.getStatus());
            if (!filled || "missing".equals(status) || "empty".equals(status)) {
                missing.add(key);
            } else if (!valid || "invalid".equals(status) || "fail".equals(status) || "failed".equals(status)) {
                invalid.add(key);
            }
        }

        return new WbMetadataDecision(orderId, distinct(missing), distinct(invalid));
    }

    public boolean blocksDelivery() {
        return !missingRequiredMeta.isEmpty() || !invalidMeta.isEmpty();
    }

    private static List<String> distinct(List<String> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static String normalizeKey(String value) {
        return nullToEmpty(value).trim().toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
