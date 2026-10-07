package com.vncode.app.integration.wb.finance;

import com.vncode.app.features.finance.FinanceRawRow;

import java.util.List;

public record WbFinancePage(List<FinanceRawRow> rows, String nextCursor, boolean endOfReport) {
    public WbFinancePage {
        rows = List.copyOf(rows);
    }
}
