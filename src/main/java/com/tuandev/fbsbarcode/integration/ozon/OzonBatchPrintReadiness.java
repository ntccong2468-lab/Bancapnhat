package com.tuandev.fbsbarcode.integration.ozon;

import java.util.List;

/** Read-only readiness result for the packing-page "print all" action. */
public record OzonBatchPrintReadiness(
        boolean ready,
        List<OzonPrintReadiness> postings,
        List<OzonPrintReadiness.GtinAvailability> gtinAvailability) {
    public OzonBatchPrintReadiness {
        postings = postings == null ? List.of() : List.copyOf(postings);
        gtinAvailability = gtinAvailability == null ? List.of() : List.copyOf(gtinAvailability);
    }
}
