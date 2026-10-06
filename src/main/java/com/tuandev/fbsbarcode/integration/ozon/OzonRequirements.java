package com.tuandev.fbsbarcode.integration.ozon;

import java.util.List;

public record OzonRequirements(
        List<String> mandatoryMarkProductIds,
        List<String> optionalMarkProductIds,
        List<String> unsupportedRequirements) {
    public OzonRequirements {
        mandatoryMarkProductIds = immutable(mandatoryMarkProductIds);
        optionalMarkProductIds = immutable(optionalMarkProductIds);
        unsupportedRequirements = immutable(unsupportedRequirements);
    }

    public boolean blocksPreparation() {
        return !unsupportedRequirements.isEmpty();
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(1000)
                .toList();
    }
}
