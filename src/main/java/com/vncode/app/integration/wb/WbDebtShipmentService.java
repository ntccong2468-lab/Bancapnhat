package com.vncode.app.integration.wb;

import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Order;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.vncode.app.features.license.LicenseModels.Permission.SELLER_API;

/** Read-only WB re-shipment membership; local cache is used only for order details. */
public final class WbDebtShipmentService {
    private static final int PAGE_SIZE = 1000;
    private static final int ID_BATCH = 1000;
    private static final int MAX_PAGES_PER_WINDOW = 20_000;
    private static final int ARCHIVE_MONTHS = 24;
    private static final int MAX_ARCHIVE_PAGES = 120;
    private final WbApiClient api;
    private final WbOrderRepository orders;
    private final WbSupplyRepository supplies;

    public WbDebtShipmentService() { this(new WbApiClient(), new WbOrderRepository(), new WbSupplyRepository()); }
    WbDebtShipmentService(WbApiClient api, WbOrderRepository orders, WbSupplyRepository supplies) {
        this.api = api; this.orders = orders; this.supplies = supplies;
    }

    public record Shipment(String id, String name, String createdAt, List<Order> orders) {
        public Shipment { orders = List.copyOf(orders); }
    }
    public record Snapshot(List<Shipment> shipments, boolean complete, String warning) {
        public Snapshot { shipments = List.copyOf(shipments); }
    }

    public Snapshot load(Shop shop) throws IOException {
        check(shop);
        WbReshipmentResponse response = api.getReshipments(shop.getApiKey());
        if (response == null || response.getOrders() == null) throw new IOException("WB_RESHIPMENT_INVALID_RESPONSE");
        Map<String, List<Long>> groups = new LinkedHashMap<>();
        Set<Long> ids = new LinkedHashSet<>();
        Set<String> pairs = new LinkedHashSet<>();
        for (WbReshipmentResponse.Item item : response.getOrders()) {
            if (item == null || item.getOrderId() == null || item.getOrderId() <= 0 || item.getSupplyId() == null
                    || !item.getSupplyId().matches("[A-Za-z0-9_-]{1,128}")
                    || !pairs.add(item.getSupplyId() + "\u0000" + item.getOrderId()))
                throw new IOException("WB_RESHIPMENT_INVALID_RESPONSE");
            ids.add(item.getOrderId());
            groups.computeIfAbsent(item.getSupplyId(), k -> new ArrayList<>()).add(item.getOrderId());
        }
        check(shop);
        Map<Long, Order> local = loadLocal(shop.getId(), ids);
        boolean detailReadFailed = false;
        boolean detailRateLimited = false;
        if (local.size() < ids.size()) {
            try { fetchMissingDetails(shop, ids, local); }
            catch (IOException ex) {
                if (ex instanceof InterruptedIOException) throw ex;
                detailReadFailed = true;
                detailRateLimited = ex instanceof WbApiException wb && wb.isRateLimited();
            }
            check(shop);
            local = loadLocal(shop.getId(), ids);
        }
        Map<Long, WbOrderStatusDto> statuses = new HashMap<>();
        boolean statusReadFailed = false;
        boolean statusRateLimited = false;
        List<Long> all = List.copyOf(ids);
        for (int i = 0; i < all.size(); i += ID_BATCH) {
            check(shop);
            List<Long> batch = all.subList(i, Math.min(i + ID_BATCH, all.size()));
            try {
                var result = api.getOrderStatuses(shop.getApiKey(), batch);
                WbFbsResponseValidator.statuses(result, batch);
                for (var row : result.getOrders()) statuses.put(row.getId(), row);
            } catch (IOException ex) {
                if (ex instanceof InterruptedIOException) throw ex;
                statusReadFailed = true;
                statusRateLimited = ex instanceof WbApiException wb && wb.isRateLimited();
                break;
            }
        }
        check(shop);
        List<Shipment> result = new ArrayList<>();
        boolean supplyReadFailed = false;
        for (var entry : groups.entrySet()) {
            var cached = supplies.findSupplySummary(shop.getId(), entry.getKey());
            if (cached == null) {
                try {
                    check(shop);
                    var detail = api.getSupplyDetail(shop.getApiKey(), entry.getKey());
                    if (detail == null || !entry.getKey().equals(detail.getId()) || detail.getDone() == null)
                        throw new IOException("FBS_FOREIGN_SUPPLY");
                    check(shop);
                    supplies.saveSupplies(shop.getId(), List.of(detail));
                    cached = supplies.findSupplySummary(shop.getId(), entry.getKey());
                } catch (IOException ex) {
                    if (ex instanceof InterruptedIOException) throw ex;
                    supplyReadFailed = true;
                }
            }
            String name = cached == null || cached.getName() == null || cached.getName().isBlank() ? entry.getKey() : cached.getName();
            String date = cached == null ? "" : cached.getCreatedAt();
            List<Order> items = new ArrayList<>();
            for (Long id : entry.getValue()) {
                Order row = local.get(id);
                if (row == null) { row = new Order(); row.setId(id); }
                var status = statuses.get(id);
                // Do not present an old cached value as a current WB status.
                row.setSupplierStatus(status == null ? null : status.getSupplierStatus());
                row.setWbStatus(status == null ? null : status.getWbStatus());
                items.add(row);
            }
            result.add(new Shipment(entry.getKey(), name, date == null ? "" : date, items));
        }
        long missingProductInfo = local.values().stream().filter(row -> blank(row.getName()) || blank(row.getArticle()) || blank(row.getSize()) || blank(row.getImageUrl()) || row.getPrice() == null).count();
        boolean complete = !detailReadFailed && !statusReadFailed && !supplyReadFailed && local.size() == ids.size() && statuses.size() == ids.size() && missingProductInfo == 0;
        String warning = "WB chỉ đưa đơn vào danh sách này sau thời gian quy định kể từ khi shipment được quét; đơn còn chờ có thể chưa xuất hiện.";
        if (local.size() < ids.size() || detailReadFailed)
            warning += " Chưa đọc đủ chi tiết " + (ids.size() - local.size()) + " đơn trong dữ liệu lưu và lịch sử WB đã tra cứu (tối đa 24 tháng lưu trữ, 120 trang/lần); vẫn hiển thị mã đơn do WB trả về.";
        if (statusReadFailed || statuses.size() < ids.size())
            warning += " Chưa đọc đủ trạng thái WB; hãy thử cập nhật lại.";
        if (missingProductInfo > 0) warning += " Chưa đủ ảnh hoặc thông tin sản phẩm của " + missingProductInfo + " đơn trong dữ liệu đã lưu.";
        if (supplyReadFailed) warning += " Chưa đọc được tên một số shipment; đang hiển thị mã shipment.";
        if (detailRateLimited || statusRateLimited) warning += " WB đang giới hạn yêu cầu (429); hãy cập nhật lại sau.";
        return new Snapshot(result, complete, warning);
    }

    private Map<Long, Order> loadLocal(int shopId, Set<Long> ids) {
        var result = new HashMap<Long, Order>();
        var all = List.copyOf(ids);
        for (int i = 0; i < all.size(); i += ID_BATCH)
            for (Order order : orders.getOrders(shopId, all.subList(i, Math.min(i + ID_BATCH, all.size())))) result.put(order.getId(), order);
        return result;
    }

    private void fetchMissingDetails(Shop shop, Set<Long> ids, Map<Long, Order> local) throws IOException {
        Set<Long> missing = new LinkedHashSet<>(ids); missing.removeAll(local.keySet());
        Instant to = Instant.now();
        // The live-orders endpoint covers the last three months and at most 30 days per request.
        for (int window = 0; window < 3 && !missing.isEmpty(); window++) {
            // Stay inside the documented three-calendar-month boundary in every month.
            Instant from = to.minus(window == 2 ? 29 : 30, ChronoUnit.DAYS);
            long cursor = 0;
            boolean ended = false;
            for (int page = 0; page < MAX_PAGES_PER_WINDOW; page++) {
                check(shop);
                var response = api.getOrders(shop.getApiKey(), cursor, PAGE_SIZE, from.getEpochSecond(), to.getEpochSecond());
                WbFbsResponseValidator.orders(response);
                long next = WbFbsResponseValidator.next(cursor, response.getNext(), response.getOrders().size(), PAGE_SIZE);
                var found = response.getOrders().stream().filter(row -> missing.contains(row.getId())).toList();
                if (!found.isEmpty()) {
                    check(shop);
                    orders.saveOrders(shop.getId(), found);
                    found.forEach(row -> missing.remove(row.getId()));
                }
                if (response.getOrders().size() < PAGE_SIZE || missing.isEmpty()) { ended = true; break; }
                cursor = next;
            }
            if (!ended) throw new IOException("FBS_CURSOR_LIMIT");
            to = from;
        }
        if (!missing.isEmpty()) fetchArchive(shop, missing);
    }

    private void fetchArchive(Shop shop, Set<Long> missing) throws IOException {
        YearMonth month = YearMonth.now(ZoneOffset.UTC).minusMonths(3);
        int used = 0;
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        for (int i = 0; i < ARCHIVE_MONTHS && !missing.isEmpty(); i++, month = month.minusMonths(1)) {
            long cursor = 0;
            boolean ended = false;
            for (int page = 0; page < MAX_PAGES_PER_WINDOW && used < MAX_ARCHIVE_PAGES; page++, used++) {
                check(shop);
                if (System.nanoTime() >= deadline) throw new IOException("WB_ARCHIVE_SCAN_DEFERRED");
                if (used > 0) {
                    try { Thread.sleep(200); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new InterruptedIOException("FBS_SYNC_CANCELLED"); }
                }
                var response = api.getArchiveOrders(shop.getApiKey(), month.getYear(), month.getMonthValue(), cursor, PAGE_SIZE);
                if (response == null || response.getOrders() == null || response.getOrders().size() > PAGE_SIZE)
                    throw new IOException("WB_ARCHIVE_INVALID_RESPONSE");
                Set<Long> seen = new LinkedHashSet<>();
                List<WbOrderDto> found = new ArrayList<>();
                for (var row : response.getOrders()) {
                    if (row == null || row.getId() == null || row.getId() <= 0 || !seen.add(row.getId()))
                        throw new IOException("WB_ARCHIVE_INVALID_RESPONSE");
                    if (missing.contains(row.getId()) && row.hasProduct()) found.add(row.toOrder());
                }
                if (!found.isEmpty()) {
                    check(shop);
                    orders.saveOrders(shop.getId(), found);
                    found.forEach(row -> missing.remove(row.getId()));
                }
                if (response.getOrders().size() < PAGE_SIZE || missing.isEmpty() || Long.valueOf(0).equals(response.getNext())) { ended = true; break; }
                cursor = WbFbsResponseValidator.next(cursor, response.getNext(), response.getOrders().size(), PAGE_SIZE);
            }
            if (!ended) throw new IOException("WB_ARCHIVE_CURSOR_LIMIT");
        }
    }

    private static void check(Shop shop) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("FBS_SYNC_CANCELLED");
        LicenseRuntime.require(SELLER_API);
        ListingIdentity.require(shop);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
