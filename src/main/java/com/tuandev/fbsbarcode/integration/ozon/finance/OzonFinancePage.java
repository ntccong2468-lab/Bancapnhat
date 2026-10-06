package com.tuandev.fbsbarcode.integration.ozon.finance;

import com.tuandev.fbsbarcode.features.finance.FinanceRawRow;

import java.util.List;

public record OzonFinancePage(List<FinanceRawRow> rows, String nextCursor, boolean endOfReport) {
    public OzonFinancePage {
        rows = List.copyOf(rows);
    }
}
