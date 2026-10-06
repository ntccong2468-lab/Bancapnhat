package com.tuandev.fbsbarcode.features.finance;

import java.time.LocalDate;

record FinanceWorkItem(
        FinanceSyncState state,
        String phase,
        LocalDate from,
        LocalDate to,
        String cursor,
        boolean newWindow,
        int priority
) {
}
