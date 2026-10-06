package com.tuandev.fbsbarcode.integration.ozon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class OzonRequirementGuard {
    private OzonRequirementGuard() {
    }

    public static PreparationPlan plan(OzonPostingDto posting, Map<String, String> skuToGtin) {
        return plan(posting, skuToGtin, Set.of());
    }

    public static PreparationPlan plan(
            OzonPostingDto posting,
            Map<String, String> skuToGtin,
            Set<String> exemptSkus) {
        Objects.requireNonNull(posting, "posting");
        Map<String, String> mappings = skuToGtin == null ? Map.of() : Map.copyOf(skuToGtin);
        Set<String> exemptions = exemptSkus == null ? Set.of() : Set.copyOf(exemptSkus);
        if (posting.requirements().blocksPreparation()) {
            throw new UnsupportedRequirementException(posting.requirements().unsupportedRequirements());
        }
        if (!posting.isSinglePackageSupported()) {
            throw new UnsupportedRequirementException(List.of("partial_or_multibox_package"));
        }
        Set<String> mandatory = Set.copyOf(posting.requirements().mandatoryMarkProductIds());
        Set<String> optional = Set.copyOf(posting.requirements().optionalMarkProductIds());
        List<RequiredItem> required = new ArrayList<>();
        for (OzonPostingItemDto item : posting.items()) {
            boolean mandatoryMark = mandatory.contains(item.productId());
            boolean optionalMark = optional.contains(item.productId());
            boolean userExempt = !item.sku().isBlank() && exemptions.contains(item.sku());
            if (userExempt && !mandatoryMark) continue;
            String gtin = mappings.get(item.sku());
            if (gtin == null || gtin.isBlank()) {
                throw new MissingMappingException(item.sku().isBlank() ? item.productId() : item.sku());
            }
            required.add(new RequiredItem(
                    item.itemIndex(), item.productId(), item.sku(), gtin, item.quantity(), mandatoryMark || optionalMark));
        }
        if (!mandatory.isEmpty()) {
            Set<String> covered = required.stream().map(RequiredItem::productId).collect(java.util.stream.Collectors.toSet());
            if (!covered.containsAll(mandatory)) {
                throw new MissingMappingException("mandatory_product");
            }
        }
        return new PreparationPlan(posting.postingNumber(), List.copyOf(required));
    }

    public static boolean requiresAny(OzonPostingDto posting, Set<String> exemptSkus) {
        Objects.requireNonNull(posting, "posting");
        Set<String> exemptions = exemptSkus == null ? Set.of() : Set.copyOf(exemptSkus);
        Set<String> mandatory = Set.copyOf(posting.requirements().mandatoryMarkProductIds());
        return posting.items().stream().anyMatch(item -> mandatory.contains(item.productId())
                || item.sku().isBlank() || !exemptions.contains(item.sku()));
    }

    public record RequiredItem(
            int itemIndex, String productId, String sku, String gtin, int quantity, boolean mandatory) {
    }

    public record PreparationPlan(String postingNumber, List<RequiredItem> items) {
        public int exemplarCount() {
            return items.stream().mapToInt(RequiredItem::quantity).sum();
        }
    }

    public static final class MissingMappingException extends IllegalStateException {
        public MissingMappingException(String sku) {
            super("An Ozon item requiring KIZ has no SKU to GTIN mapping: " + safe(sku));
        }

        private static String safe(String value) {
            return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        }
    }

    public static final class UnsupportedRequirementException extends IllegalStateException {
        private final List<String> requirements;

        public UnsupportedRequirementException(List<String> requirements) {
            super("This posting has an Ozon requirement that WCode does not support yet.");
            this.requirements = requirements == null ? List.of() : List.copyOf(requirements);
        }

        public List<String> requirements() {
            return requirements;
        }
    }
}
