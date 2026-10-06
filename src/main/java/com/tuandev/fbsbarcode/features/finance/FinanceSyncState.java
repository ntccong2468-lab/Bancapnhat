package com.tuandev.fbsbarcode.features.finance;

import java.time.Instant;
import java.time.LocalDate;

public record FinanceSyncState(
        int shopId,
        String streamName,
        String apiFamily,
        String phase,
        String status,
        LocalDate anchorDate,
        LocalDate windowFrom,
        LocalDate windowTo,
        String cursor,
        Instant nextRunAt,
        Instant nextAllowedAt,
        Instant lastSuccessAt,
        String lastError
) {
    public boolean due(Instant now) {
        return nextRunAt == null || !nextRunAt.isAfter(now);
    }
}
