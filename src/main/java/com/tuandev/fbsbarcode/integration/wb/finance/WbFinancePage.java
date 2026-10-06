package com.tuandev.fbsbarcode.integration.wb.finance;

import com.tuandev.fbsbarcode.features.finance.FinanceRawRow;

import java.util.List;

public record WbFinancePage(List<FinanceRawRow> rows, String nextCursor, boolean endOfReport) {
    public WbFinancePage {
        rows = List.copyOf(rows);
    }
}
