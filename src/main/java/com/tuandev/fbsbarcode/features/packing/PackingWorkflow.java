package com.tuandev.fbsbarcode.features.packing;

import com.tuandev.fbsbarcode.features.print.SupplyBarcodePdfExporter;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryService;
import com.tuandev.fbsbarcode.integration.wb.WbActionLogRepository;
import com.tuandev.fbsbarcode.integration.wb.WbApiClient;
import com.tuandev.fbsbarcode.integration.wb.WbCreateSupplyResponse;
import com.tuandev.fbsbarcode.integration.wb.WbOrderRepository;
import com.tuandev.fbsbarcode.integration.wb.WbOrderSyncService;
import com.tuandev.fbsbarcode.integration.wb.WbProductSyncService;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyRepository;
import com.tuandev.fbsbarcode.integration.wb.WbSupplySummary;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyWorkflow;
import com.tuandev.fbsbarcode.integration.wb.WbSyncWorkflow;
import com.tuandev.fbsbarcode.integration.wb.WbApiException;
import com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class PackingWorkflow {
    private static final int ADD_ORDER_BATCH_SIZE = 100;
    private static final DateTimeFormatter SHIPMENT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final WbApiClient apiClient = new WbApiClient();
    private final WbOrderRepository orderRepository = new WbOrderRepository();
    private final WbProductSyncService productSyncService = new WbProductSyncService();
    private final WbSupplyRepository supplyRepository = new WbSupplyRepository();
    private final WbSupplyWorkflow supplyWorkflow = new WbSupplyWorkflow();
    private final WbSyncWorkflow syncWorkflow = new WbSyncWorkflow();
    private final WbOrderSyncService orderSyncService = new WbOrderSyncService();
    private final PrintHistoryService printHistoryService = new PrintHistoryService();
    private final WbActionLogRepository actionLogRepository = new WbActionLogRepository();

    public PackingBoard loadBoard(Shop shop) {
        MarketplaceGuard.requireWildberries(shop);
        PackingBoard board = loadBoardData(shop);
        List<Order> newOrders = supplyWorkflow.populateCachedOrderImages(board.newOrders());
        return new PackingBoard(newOrders, board.preparationSupplies(), board.dispatchSupplies());
    }

    public PackingBoard loadBoardData(Shop shop) {
        MarketplaceGuard.requireWildberries(shop);
        List<Order> newOrders = orderRepository.getOrdersForPackingStatus(shop.getId(), "new");
        List<WbSupplySummary> allSupplies = supplyWorkflow.getSupplies(shop.getId());
        List<WbSupplySummary> preparationSupplies = allSupplies.stream()
                .filter(supply -> !supply.isDone())
                .toList();
        List<WbSupplySummary> dispatchSupplies = allSupplies.stream()
                .filter(WbSupplySummary::isDone)
                .toList();
        return new PackingBoard(newOrders, preparationSupplies, dispatchSupplies);
    }

    public void refreshBoardData(Shop shop) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        try {
            orderSyncService.syncNewOrders(shop);
            syncMissingProductsForNewOrders(shop);
            syncWorkflow.refetchSupplies(shop);
        } catch (IOException ex) {
            if (isTimeout(ex)) {
                return;
            }
            throw ex;
        }
    }

    public List<Order> loadSupplyOrders(Shop shop, String supplyId) {
        MarketplaceGuard.requireWildberries(shop);
        return supplyWorkflow.loadOrdersForSupplyLocal(shop, supplyId);
    }

    public String defaultShipmentName() {
        return "Shipment " + LocalDate.now().format(SHIPMENT_DATE_FORMAT);
    }

    public String createShipment(Shop shop, String name, List<Long> orderIds) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        Boolean orderB2b = validateOrderB2bSelection(shop.getId(), orderIds);
        String requestJson = "{\"name\":\"" + sanitize(name) + "\",\"orders\":" + orderIds + "}";
        try {
            WbCreateSupplyResponse response = apiClient.createSupply(shop.getApiKey(), name);
            String supplyId = response == null ? null : response.getId();
            if (supplyId == null || supplyId.isBlank()) {
                throw new IOException("WB không trả về supplyId sau khi tạo shipment");
            }
            supplyRepository.saveCreatedSupply(shop.getId(), supplyId, name, orderB2b);
            addOrdersToSupply(shop, supplyId, orderIds);
            actionLogRepository.record(shop.getId(), "CREATE_SUPPLY", supplyId, orderIds, "success", requestJson, "{\"id\":\"" + supplyId + "\"}", null);
            return supplyId;
        } catch (IOException | RuntimeException ex) {
            actionLogRepository.record(shop.getId(), "CREATE_SUPPLY", null, orderIds, "failed", requestJson, null, ex.getMessage());
            throw ex;
        }
    }

    public void addOrdersToSupply(Shop shop, String supplyId, List<Long> orderIds) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        List<Long> safeOrderIds = orderIds == null ? List.of() : new ArrayList<>(orderIds);
        validateSupplyB2bCompatibility(shop.getId(), supplyId, safeOrderIds);
        try {
            for (int i = 0; i < safeOrderIds.size(); i += ADD_ORDER_BATCH_SIZE) {
                List<Long> batch = safeOrderIds.subList(i, Math.min(i + ADD_ORDER_BATCH_SIZE, safeOrderIds.size()));
                apiClient.addOrdersToSupply(shop.getApiKey(), supplyId, batch);
                actionLogRepository.record(shop.getId(), "ADD_ORDERS_TO_SUPPLY", supplyId, batch, "success", "{\"orders\":" + batch + "}", null, null);
            }
            // Reflect successful WB assignment locally even if follow-up sync is slow.
            orderRepository.replaceSupplyOrders(shop.getId(), supplyId, safeOrderIds);
            try {
                syncWorkflow.syncSupplyOrdersAndStatuses(shop, supplyId);
            } catch (IOException ex) {
                if (!isTimeout(ex)) {
                    throw ex;
                }
            }
        } catch (IOException | RuntimeException ex) {
            actionLogRepository.record(shop.getId(), "ADD_ORDERS_TO_SUPPLY", supplyId, safeOrderIds, "failed", "{\"orders\":" + safeOrderIds + "}", null, ex.getMessage());
            throw ex;
        }
    }

    public void deliverSupply(Shop shop, WbSupplySummary supply) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        DeliveryPreflight preflight = inspectDelivery(shop.getId(), supply);
        if (!preflight.labelsPrinted()) {
            throw new IllegalStateException("Сначала распечатайте этикетки для поставки.");
        }
        if (!preflight.kizComplete()) {
            throw new IllegalStateException("В поставке есть товары с обязательной маркировкой без KIZ.");
        }
        validateMetadataBeforeDelivery(shop, supply.getSupplyId());
        try {
            apiClient.deliverSupply(shop.getApiKey(), supply.getSupplyId());
            supplyRepository.markSupplyDelivered(shop.getId(), supply.getSupplyId());
            syncWorkflow.syncSupplyOrdersAndStatuses(shop, supply.getSupplyId());
            actionLogRepository.record(shop.getId(), "DELIVER_SUPPLY", supply.getSupplyId(), List.of(), "success", null, null, null);
        } catch (IOException | RuntimeException ex) {
            actionLogRepository.record(shop.getId(), "DELIVER_SUPPLY", supply.getSupplyId(), List.of(), "failed", null, null, ex.getMessage());
            throw ex;
        }
    }

    public byte[] getSupplyBarcode(Shop shop, WbSupplySummary supply) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        try {
            byte[] bytes = apiClient.getSupplyBarcode(shop.getApiKey(), supply.getSupplyId(), "png");
            actionLogRepository.record(shop.getId(), "GET_SUPPLY_BARCODE", supply.getSupplyId(), List.of(), "success", null, "bytes=" + bytes.length, null);
            return bytes;
        } catch (IOException | RuntimeException ex) {
            actionLogRepository.record(shop.getId(), "GET_SUPPLY_BARCODE", supply.getSupplyId(), List.of(), "failed", null, null, ex.getMessage());
            throw ex;
        }
    }

    public byte[] getSupplyBarcodePdf(Shop shop, WbSupplySummary supply) throws IOException {
        byte[] imageBytes = getSupplyBarcode(shop, supply);
        return SupplyBarcodePdfExporter.exportSingleLabel(imageBytes);
    }

    public boolean canDeliver(int shopId, WbSupplySummary supply) {
        return inspectDelivery(shopId, supply).ready();
    }

    public DeliveryPreflight inspectDelivery(int shopId, WbSupplySummary supply) {
        boolean supplyReady = supply != null && !supply.isDone() && supply.getItemCount() > 0;
        boolean labelsPrinted = supplyReady
                && printHistoryService.hasSuccessfulJobForSupply(shopId, supply.getSupplyId());
        boolean kizComplete = supplyReady
                && !orderRepository.hasRequiredMetaWithoutPrintedKiz(shopId, supply.getSupplyId());
        List<String> blockers = new ArrayList<>();
        if (!supplyReady) blockers.add("supply_not_ready");
        if (supplyReady && !labelsPrinted) blockers.add("labels_missing");
        if (supplyReady && !kizComplete) blockers.add("kiz_missing");
        return new DeliveryPreflight(
                blockers.isEmpty(), labelsPrinted, kizComplete, List.copyOf(blockers));
    }

    private void syncMissingProductsForNewOrders(Shop shop) throws IOException {
        if (!orderRepository.hasMissingProductsForPackingStatus(shop.getId(), "new")) {
            return;
        }
        try {
            productSyncService.sync(shop);
        } catch (WbApiException ex) {
            if (ex.isContentPermissionError()) {
                return;
            }
            if (ex.isRateLimited()) {
                return;
            }
            throw ex;
        }
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
    }

    private Boolean validateOrderB2bSelection(int shopId, List<Long> orderIds) {
        List<Boolean> values = orderRepository.findKnownB2bValuesForOrders(shopId, orderIds);
        if (values.size() > 1) {
            throw new IllegalStateException("Нельзя смешивать B2B и B2C заказы в одной поставке.");
        }
        return values.isEmpty() ? null : values.getFirst();
    }

    private void validateSupplyB2bCompatibility(int shopId, String supplyId, List<Long> orderIds) {
        Boolean orderB2b = validateOrderB2bSelection(shopId, orderIds);
        Boolean supplyB2b = supplyRepository.getSupplyB2b(shopId, supplyId);
        if (orderB2b != null && supplyB2b != null && !orderB2b.equals(supplyB2b)) {
            throw new IllegalStateException("Тип B2B/B2C заказов не совпадает с выбранной поставкой.");
        }
    }

    private void validateMetadataBeforeDelivery(Shop shop, String supplyId) throws IOException {
        List<Long> orderIds = orderRepository.getOrderIdsForSupply(shop.getId(), supplyId);
        if (orderIds.isEmpty()) {
            return;
        }
        var response = apiClient.getOrderMetadata(shop.getApiKey(), orderIds);
        if (response == null || response.getOrders().isEmpty()) {
            return;
        }
        List<String> blockers = response.getOrders().stream()
                .map(com.tuandev.fbsbarcode.integration.wb.WbMetadataDecision::from)
                .filter(com.tuandev.fbsbarcode.integration.wb.WbMetadataDecision::blocksDelivery)
                .map(decision -> {
                    List<String> parts = new ArrayList<>();
                    if (!decision.missingRequiredMeta().isEmpty()) {
                        parts.add("missing " + decision.missingRequiredMeta());
                    }
                    if (!decision.invalidMeta().isEmpty()) {
                        parts.add("invalid " + decision.invalidMeta());
                    }
                    return decision.orderId() + ": " + String.join(", ", parts);
                })
                .collect(Collectors.toList());
        if (!blockers.isEmpty()) {
            throw new IllegalStateException("WB не разрешает передачу поставки: не заполнены или неверны IMEI/UIN/SGTIN/GTIN для заказов " + String.join("; ", blockers));
        }
    }

    private boolean isTimeout(IOException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof InterruptedIOException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public record PackingBoard(List<Order> newOrders, List<WbSupplySummary> preparationSupplies, List<WbSupplySummary> dispatchSupplies) {
    }

    public record DeliveryPreflight(
            boolean ready, boolean labelsPrinted, boolean kizComplete, List<String> blockers) {
        public DeliveryPreflight {
            blockers = List.copyOf(blockers);
        }
    }
}
