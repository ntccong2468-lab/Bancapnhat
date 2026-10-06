package com.tuandev.fbsbarcode.integration.ozon;

import java.util.List;

/** Read-only result used before WCode asks the user where to save an Ozon label. */
public record OzonPrintReadiness(
        String postingNumber,
        boolean ready,
        boolean postingAvailable,
        int requiredKiz,
        List<GtinAvailability> gtinAvailability,
        List<String> missingSkus,
        List<String> unsupportedRequirements,
        String preparationStage) {
    public OzonPrintReadiness {
        postingNumber = postingNumber == null ? "" : postingNumber;
        gtinAvailability = gtinAvailability == null ? List.of() : List.copyOf(gtinAvailability);
        missingSkus = missingSkus == null ? List.of() : List.copyOf(missingSkus);
        unsupportedRequirements = unsupportedRequirements == null ? List.of() : List.copyOf(unsupportedRequirements);
        preparationStage = preparationStage == null ? "" : preparationStage;
    }

    public record GtinAvailability(String gtin, int required, int available) {
        public GtinAvailability {
            if (gtin == null || gtin.isBlank() || required < 0 || available < 0) {
                throw new IllegalArgumentException("Invalid Ozon GTIN availability.");
            }
        }

        public boolean sufficient() {
            return available >= required;
        }
    }
}
