package com.tuandev.fbsbarcode.features.fbosupply;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.integration.ozon.OzonApiClient;
import com.tuandev.fbsbarcode.integration.ozon.OzonCredentials;
import com.tuandev.fbsbarcode.integration.wb.fbw.WbFbwApiClient;
import com.tuandev.fbsbarcode.models.Shop;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class FboSupplySyncService {
    private static final int OZON_PAGE_LIMIT = 100;
    private static final int OZON_MAX_LIST_PAGES = 5;
    private static final List<String> OZON_STATES = List.of(
            "DATA_FILLING", "READY_TO_SUPPLY", "ACCEPTED_AT_SUPPLY_WAREHOUSE", "IN_TRANSIT",
            "ACCEPTANCE_AT_STORAGE_WAREHOUSE", "REPORTS_CONFIRMATION_AWAITING", "REPORT_REJECTED",
            "COMPLETED", "REJECTED_AT_SUPPLY_WAREHOUSE", "CANCELLED", "OVERDUE");

    private final FboSupplyRepository repository;

    public FboSupplySyncService() {
        this(new FboSupplyRepository());
    }

    public FboSupplySyncService(FboSupplyRepository repository) {
        this.repository = repository;
    }

    public List<FboSupplyOrder> cachedOrders(Shop shop) {
        return repository.findOrders(shop);
    }

    public List<FboSupplyItem> cachedItems(Shop shop, String orderId) {
        return repository.findItems(shop, orderId);
    }

    public List<FboSupplyOrder> syncOrders(Shop shop) throws IOException {
        requireShop(shop);
        if (shop.getMarketplace() == Marketplace.WILDBERRIES) {
            JsonArray summaries = new WbFbwApiClient(shop.getId(), shop.getApiKey()).listSupplies(1000, 0);
            repository.upsertWbSummaries(shop.getId(), summaries);
            repository.markSyncSuccess(shop.getId(), Marketplace.WILDBERRIES, null);
        } else {
            syncOzonOrders(shop);
        }
        return repository.findOrders(shop);
    }

    private void syncOzonOrders(Shop shop) throws IOException {
        OzonApiClient client = new OzonApiClient(
                shop.getId(), new OzonCredentials(shop.getClientId(), shop.getApiKey()));
        JsonObject newest = client.listFboSupplyOrders(OZON_STATES, "", OZON_PAGE_LIMIT);
        JsonArray newestIds = persistOzonPage(shop.getId(), client, newest);
        String nextNewest = string(newest, "last_id");
        if (pageEnded(newestIds, "", nextNewest)) {
            repository.markSyncSuccess(shop.getId(), Marketplace.OZON, "");
            return;
        }

        String storedCursor = repository.findOzonCursor(shop.getId());
        String cursor = storedCursor == null || storedCursor.isBlank() ? nextNewest : storedCursor;
        String checkpoint = cursor;
        for (int page = 1; page < OZON_MAX_LIST_PAGES; page++) {
            JsonObject listed = client.listFboSupplyOrders(OZON_STATES, cursor, OZON_PAGE_LIMIT);
            JsonArray ids = persistOzonPage(shop.getId(), client, listed);
            String next = string(listed, "last_id");
            checkpoint = pageEnded(ids, cursor, next) ? "" : next;
            repository.saveOzonCursor(shop.getId(), checkpoint);
            if (checkpoint.isBlank()) break;
            cursor = checkpoint;
        }
        repository.markSyncSuccess(shop.getId(), Marketplace.OZON, checkpoint);
    }

    private JsonArray persistOzonPage(int shopId, OzonApiClient client, JsonObject listed) throws IOException {
        JsonArray ids = array(listed, "order_ids");
        List<String> orderIds = new ArrayList<>();
        for (JsonElement element : ids) {
            if (element != null && element.isJsonPrimitive()) {
                String id = element.getAsString();
                if (id != null && id.matches("[1-9][0-9]{0,38}")) orderIds.add(id);
            }
        }
        for (int from = 0; from < orderIds.size(); from += 50) {
            List<String> batch = orderIds.subList(from, Math.min(orderIds.size(), from + 50));
            repository.upsertOzonDetails(shopId, client.getFboSupplyOrders(batch));
        }
        return ids;
    }

    private static boolean pageEnded(JsonArray ids, String cursor, String next) {
        return ids.size() < OZON_PAGE_LIMIT || next == null || next.isBlank() || next.equals(cursor);
    }

    public List<FboSupplyItem> syncItems(Shop shop, FboSupplyOrder order) throws IOException {
        requireShop(shop);
        if (order == null || order.shopId() != shop.getId()) {
            throw new IllegalArgumentException("A current FBO supply order is required");
        }
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
        WbFbwApiClient client = new WbFbwApiClient(shop.getId(), shop.getApiKey());
        JsonObject detail = client.getSupply(lookupId, usePreorderId);
        JsonArray allGoods = new JsonArray();
        for (int offset = 0, page = 0; page < 10; page++, offset += 1000) {
            JsonArray goods = client.getGoods(lookupId, usePreorderId, 1000, offset);
            goods.forEach(allGoods::add);
            if (goods.size() < 1000) break;
        }
        repository.upsertWbDetail(shop.getId(), order.orderId(), detail, allGoods);
    }

    private void syncOzonItems(Shop shop, FboSupplyOrder order) throws IOException {
        OzonApiClient client = new OzonApiClient(
                shop.getId(), new OzonCredentials(shop.getClientId(), shop.getApiKey()));
        for (FboSupplyRepository.OzonSupplyRef supply : repository.findOzonSupplyRefs(shop.getId(), order.orderId())) {
            if (supply.bundleId() == null || supply.bundleId().isBlank()) continue;
            JsonArray allItems = new JsonArray();
            String cursor = "";
            for (int page = 0; page < 100; page++) {
                JsonObject response = client.getFboSupplyBundle(List.of(supply.bundleId()), cursor, 100);
                JsonArray items = array(response, "items");
                items.forEach(allItems::add);
                boolean hasNext = bool(response, "has_next");
                String next = string(response, "last_id");
                if (!hasNext || next == null || next.isBlank() || next.equals(cursor)) break;
                cursor = next;
            }
            repository.replaceOzonItems(shop.getId(), order.orderId(), supply.supplyId(), allItems);
        }
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
