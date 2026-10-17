package com.vncode.app.features.fbosupply;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonApiClient;
import com.vncode.app.integration.ozon.OzonCredentials;
import com.vncode.app.integration.wb.fbw.WbFbwApiClient;
import com.vncode.app.models.Shop;
import com.vncode.app.features.license.LicenseRuntime;
import static com.vncode.app.features.license.LicenseModels.Permission.SELLER_API;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

public final class FboSupplySyncService {
    private static final int OZON_PAGE_LIMIT = 100;
    private static final int OZON_MAX_LIST_PAGES = 5;
    private static final List<String> OZON_STATES = List.of(
            "DATA_FILLING", "READY_TO_SUPPLY", "ACCEPTED_AT_SUPPLY_WAREHOUSE", "IN_TRANSIT",
            "ACCEPTANCE_AT_STORAGE_WAREHOUSE", "REPORTS_CONFIRMATION_AWAITING", "REPORT_REJECTED",
            "COMPLETED", "REJECTED_AT_SUPPLY_WAREHOUSE", "CANCELLED", "OVERDUE");

    private final FboSupplyRepository repository;
    private final Function<Shop,WbFbwApiClient> wb;
    private final Function<Shop,OzonApiClient> ozon;

    public FboSupplySyncService() {
        this(new FboSupplyRepository());
    }

    public FboSupplySyncService(FboSupplyRepository repository) {
        this(repository, shop -> new WbFbwApiClient(shop.getId(), shop.getApiKey()),
                shop -> new OzonApiClient(shop.getId(), new OzonCredentials(shop.getClientId(), shop.getApiKey())));
    }

    FboSupplySyncService(FboSupplyRepository repository, Function<Shop,WbFbwApiClient> wb,
                         Function<Shop,OzonApiClient> ozon) {
        this.repository = repository;
        this.wb = wb;
        this.ozon = ozon;
    }

    public List<FboSupplyOrder> cachedOrders(Shop shop) {
        return repository.findOrders(shop);
    }

    public List<FboSupplyItem> cachedItems(Shop shop, String orderId) {
        return repository.findItems(shop, orderId);
    }

    public List<FboSupplyOrder> syncOrders(Shop shop) throws IOException {
        requireShop(shop);
        LicenseRuntime.require(SELLER_API);
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        if (shop.getMarketplace() == Marketplace.WILDBERRIES) {
            var client = wb.apply(shop);
            var seen = new HashSet<String>();
            boolean ended = false;
            for (int page = 0; page < 1000; page++) {
                checkInterrupted();
                LicenseRuntime.require(SELLER_API);
                JsonArray summaries = client.listSupplies(1000, page * 1000);
                if (summaries.size() > 1000) throw new IOException("FBO_INVALID_RESPONSE");
                for (var element : summaries) {
                    if (!element.isJsonObject() || !seen.add(requireId(element.getAsJsonObject(), "preorderID")))
                        throw new IOException("FBO_REPEATED_ORDER");
                }
                com.vncode.app.integration.listing.ListingIdentity.require(shop);
                repository.upsertWbSummaries(shop.getId(), summaries);
                if (summaries.size() < 1000) { ended = true; break; }
            }
            if (!ended) throw new IOException("FBO_PAGE_LIMIT");
            repository.markSyncSuccess(shop.getId(), Marketplace.WILDBERRIES, null);
        } else {
            syncOzonOrders(shop);
        }
        return repository.findOrders(shop);
    }

    private void syncOzonOrders(Shop shop) throws IOException {
        OzonApiClient client = ozon.apply(shop);
        JsonObject newest = client.listFboSupplyOrders(OZON_STATES, "", OZON_PAGE_LIMIT);
        JsonArray newestIds = persistOzonPage(shop, client, newest);
        String nextNewest = string(newest, "last_id");
        if (pageEnded(newestIds, "", nextNewest)) {
            repository.markSyncSuccess(shop.getId(), Marketplace.OZON, "");
            return;
        }

        String storedCursor = repository.findOzonCursor(shop.getId());
        String cursor = storedCursor == null || storedCursor.isBlank() ? nextNewest : storedCursor;
        String checkpoint = cursor;
        var seen = new HashSet<String>();
        seen.add(""); seen.add(cursor);
        for (int page = 1; page < OZON_MAX_LIST_PAGES; page++) {
            checkInterrupted();
            LicenseRuntime.require(SELLER_API);
            JsonObject listed = client.listFboSupplyOrders(OZON_STATES, cursor, OZON_PAGE_LIMIT);
            JsonArray ids = persistOzonPage(shop, client, listed);
            String next = string(listed, "last_id");
            checkpoint = pageEnded(ids, cursor, next) ? "" : next;
            if (!checkpoint.isBlank() && !seen.add(checkpoint)) throw new IOException("FBO_REPEATED_CURSOR");
            repository.saveOzonCursor(shop.getId(), checkpoint);
            if (checkpoint.isBlank()) break;
            cursor = checkpoint;
        }
        repository.markSyncSuccess(shop.getId(), Marketplace.OZON, checkpoint);
    }

    private JsonArray persistOzonPage(Shop shop, OzonApiClient client, JsonObject listed) throws IOException {
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        if(listed == null || !listed.has("order_ids")||!listed.get("order_ids").isJsonArray())throw new IOException("FBO_INVALID_RESPONSE");
        JsonArray ids = array(listed, "order_ids");
        if (ids.size() > OZON_PAGE_LIMIT) throw new IOException("FBO_INVALID_RESPONSE");
        List<String> orderIds = new ArrayList<>();
        Set<String> distinct = new HashSet<>();
        for (JsonElement element : ids) {
            String id = element != null && element.isJsonPrimitive() ? element.getAsString() : "";
            if (!id.matches("[1-9][0-9]{0,38}") || !distinct.add(id)) throw new IOException("FBO_INVALID_ORDER_IDS");
            orderIds.add(id);
        }
        for (int from = 0; from < orderIds.size(); from += 50) {
            LicenseRuntime.require(SELLER_API);
            List<String> batch = orderIds.subList(from, Math.min(orderIds.size(), from + 50));
            JsonObject details=client.getFboSupplyOrders(batch);
            if (details == null || !details.has("orders") || !details.get("orders").isJsonArray())
                throw new IOException("FBO_INCOMPLETE_ORDER_DETAILS");
            var returned = new HashSet<String>();
            for (var element : details.getAsJsonArray("orders")) {
                if (!element.isJsonObject()) throw new IOException("FBO_INCOMPLETE_ORDER_DETAILS");
                var row = element.getAsJsonObject();
                if (!returned.add(requireId(row, "order_id")) || string(row, "state") == null || string(row, "state").isBlank())
                    throw new IOException("FBO_INCOMPLETE_ORDER_DETAILS");
            }
            if (!returned.equals(new HashSet<>(batch))) throw new IOException("FBO_INCOMPLETE_ORDER_DETAILS");
            com.vncode.app.integration.listing.ListingIdentity.require(shop);
            repository.upsertOzonDetails(shop.getId(), details);
        }
        return ids;
    }

    private static boolean pageEnded(JsonArray ids, String cursor, String next) throws IOException {
        if (ids.size() < OZON_PAGE_LIMIT) return true;
        if (next == null || next.isBlank() || next.equals(cursor)) throw new IOException("FBO_INVALID_CURSOR");
        return false;
    }

    /** A bounded history pass must be continued before the stream can report completion. */
    public String pendingOrdersCursor(Shop shop) {
        if (shop.getMarketplace() != Marketplace.OZON) return "";
        String cursor = repository.findOzonCursor(shop.getId());
        return cursor == null ? "" : cursor;
    }

    public List<FboSupplyItem> syncItems(Shop shop, FboSupplyOrder order) throws IOException {
        requireShop(shop);
        if (order == null || order.shopId() != shop.getId() || order.marketplace() != shop.getMarketplace()) {
            throw new IllegalArgumentException("A current FBO supply order is required");
        }
        LicenseRuntime.require(SELLER_API);
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        if (shop.getMarketplace() == Marketplace.WILDBERRIES) {
            syncWbItems(shop, order);
        } else {
            syncOzonItems(shop, order);
        }
        return repository.findItems(shop, order.orderId());
    }

    private void syncWbItems(Shop shop, FboSupplyOrder order) throws IOException {
        boolean usePreorderId = order.supplyId() == null || order.supplyId().isBlank();
        String lookupId = usePreorderId ? order.orderId() : order.supplyId();
        WbFbwApiClient client = wb.apply(shop);
        JsonObject detail = client.getSupply(lookupId, usePreorderId);
        if (detail == null || string(detail, "statusID") == null) throw new IOException("FBO_INVALID_RESPONSE");
        JsonArray allGoods = new JsonArray();
        boolean ended = false;
        for (int offset = 0, page = 0; page < 1000; page++, offset += 1000) {
            checkInterrupted();
            LicenseRuntime.require(SELLER_API);
            JsonArray goods = client.getGoods(lookupId, usePreorderId, 1000, offset);
            goods.forEach(allGoods::add);
            if (goods.size() > 1000) throw new IOException("FBO_INVALID_RESPONSE");
            if (goods.size() < 1000) { ended = true; break; }
        }
        if (!ended) throw new IOException("FBO_PAGE_LIMIT");
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        repository.upsertWbDetail(shop.getId(), order.orderId(), detail, allGoods);
    }

    private void syncOzonItems(Shop shop, FboSupplyOrder order) throws IOException {
        OzonApiClient client = ozon.apply(shop);
        for (FboSupplyRepository.OzonSupplyRef supply : repository.findOzonSupplyRefs(shop.getId(), order.orderId())) {
            if (supply.bundleId() == null || supply.bundleId().isBlank()) continue;
            JsonArray allItems = new JsonArray();
            String cursor = "";
            var seen = new HashSet<String>(); seen.add(cursor);
            var seenItems = new HashSet<String>();
            boolean ended = false;
            for (int page = 0; page < 100; page++) {
                checkInterrupted();
                LicenseRuntime.require(SELLER_API);
                JsonObject response = client.getFboSupplyBundle(List.of(supply.bundleId()), cursor, 100);
                if (response == null || !response.has("items") || !response.get("items").isJsonArray()
                        || !response.has("has_next") || !response.get("has_next").isJsonPrimitive()
                        || !response.getAsJsonPrimitive("has_next").isBoolean()) throw new IOException("FBO_INVALID_RESPONSE");
                JsonArray items = array(response, "items");
                if (items.size() > 100) throw new IOException("FBO_INVALID_RESPONSE");
                for (var element : items) {
                    if (!element.isJsonObject()) throw new IOException("FBO_INVALID_ITEM");
                    var item = element.getAsJsonObject();
                    String sku = requireId(item, "sku");
                    String product = string(item, "product_id");
                    if (!seenItems.add(sku + ":" + (product == null ? "" : product))) throw new IOException("FBO_REPEATED_ITEM");
                    try {
                        if (item.get("quantity").getAsBigDecimal().intValueExact() < 0) throw new IllegalArgumentException();
                    } catch (RuntimeException invalid) { throw new IOException("FBO_INVALID_QUANTITY"); }
                }
                items.forEach(allItems::add);
                boolean hasNext = bool(response, "has_next");
                String next = string(response, "last_id");
                if (!hasNext) { ended = true; break; }
                if (items.isEmpty() || next == null || next.isBlank() || !seen.add(next)) throw new IOException("FBO_INVALID_CURSOR");
                cursor = next;
            }
            if (!ended) throw new IOException("FBO_PAGE_LIMIT");
            com.vncode.app.integration.listing.ListingIdentity.require(shop);
            repository.replaceOzonItems(shop.getId(), order.orderId(), supply.supplyId(), allItems);
        }
    }

    private static String requireId(JsonObject row, String key) throws IOException {
        String id = string(row, key);
        if (id == null || !id.matches("[1-9][0-9]{0,38}")) throw new IOException("FBO_INVALID_IDENTIFIER");
        return id;
    }

    private static void checkInterrupted() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("FBO_SYNC_INTERRUPTED");
    }

    public boolean isOrderListStale(Shop shop) {
        if (shop == null) return false;
        String value = repository.findLastSyncedAt(shop.getId(), shop.getMarketplace());
        if (value == null) return true;
        try {
            return Instant.parse(value).plus(Duration.ofMinutes(10)).isBefore(Instant.now());
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    public String lastSyncedAt(Shop shop) {
        return shop == null ? null : repository.findLastSyncedAt(shop.getId(), shop.getMarketplace());
    }

    private static void requireShop(Shop shop) {
        if (shop == null || shop.getId() <= 0) throw new IllegalArgumentException("A valid shop is required");
        if (shop.getApiKey() == null || shop.getApiKey().isBlank()) {
            throw new IllegalArgumentException("The shop API credential is required");
        }
        if (shop.getMarketplace() == Marketplace.OZON
                && (shop.getClientId() == null || shop.getClientId().isBlank())) {
            throw new IllegalArgumentException("The Ozon Client ID is required");
        }
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return null;
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        try {
            return value != null && !value.isJsonNull() && value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
