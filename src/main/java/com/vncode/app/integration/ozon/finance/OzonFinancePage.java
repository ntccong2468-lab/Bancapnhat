package com.vncode.app.integration.ozon.finance;

import com.vncode.app.features.finance.FinanceRawRow;

import java.util.List;

public record OzonFinancePage(List<FinanceRawRow> rows, String nextCursor, boolean endOfReport) {
    public OzonFinancePage {
        rows = List.copyOf(rows);
    }
}
