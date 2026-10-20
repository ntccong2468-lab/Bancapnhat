package com.vncode.app.integration.wb;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

/** Shared by continuous polling and manual refresh: partial success must never clear local state. */
public final class WbFbsResponseValidator {
    private WbFbsResponseValidator() {}
    public static void orders(WbOrdersResponse response) throws IOException {
        if (response == null || response.getOrders() == null) throw invalid();
        var seen = new HashSet<Long>();
        for (var row : response.getOrders())
            if (row == null || row.getId() == null || row.getId() <= 0 || !seen.add(row.getId())) throw invalid();
    }
    public static long supplies(WbSuppliesResponse response, long cursor, int limit) throws IOException {
        if (response == null || response.getSupplies() == null || response.getSupplies().size() > limit) throw invalid();
        var seen = new HashSet<String>();
        for (var row : response.getSupplies())
            if (row == null || row.getId() == null || row.getId().isBlank() || row.getDone() == null || !seen.add(row.getId())) throw invalid();
        return next(cursor, response.getNext(), response.getSupplies().size(), limit);
    }
    public static long next(long cursor, Long next, int count, int limit) throws IOException {
        if (count > limit) throw invalid();
        if (count == limit && (next == null || next <= cursor)) throw new IOException("FBS_INVALID_CURSOR");
        if (next != null && next < 0) throw new IOException("FBS_INVALID_CURSOR");
        return next == null ? cursor : next;
    }
    public static List<Long> supplyIds(WbSupplyOrderIdsResponse response) throws IOException {
        if (response == null || response.getOrderIds() == null) throw invalid();
        var ids = response.getOrderIds(); var seen = new HashSet<Long>();
        for (var id : ids) if (id == null || id <= 0 || !seen.add(id)) throw invalid();
        return ids;
    }
    public static void statuses(WbOrderStatusesResponse response, List<Long> expected) throws IOException {
        if (response == null || response.getOrders() == null) throw invalid();
        var wanted = new HashSet<>(expected); var seen = new HashSet<Long>();
        for (var row : response.getOrders()) {
            if (row == null || row.getId() == null || !wanted.contains(row.getId()) || !seen.add(row.getId())) throw new IOException("FBS_FOREIGN_ORDER");
            if (row.getSupplierStatus() == null || row.getSupplierStatus().isBlank() || row.getWbStatus() == null || row.getWbStatus().isBlank()) throw new IOException("FBS_INCOMPLETE_RESPONSE");
        }
        if (!seen.equals(wanted)) throw new IOException("FBS_INCOMPLETE_RESPONSE");
    }
    private static IOException invalid() { return new IOException("FBS_INVALID_RESPONSE"); }
}
