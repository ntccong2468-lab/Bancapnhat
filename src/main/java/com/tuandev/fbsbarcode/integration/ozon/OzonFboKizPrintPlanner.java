package com.tuandev.fbsbarcode.integration.ozon;

import com.tuandev.fbsbarcode.features.fbo.FboBarcodePrintItem;
import com.tuandev.fbsbarcode.features.fbo.FboPrintPage;
import com.tuandev.fbsbarcode.features.fbo.FboPrintPlan;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventoryService;
import com.tuandev.fbsbarcode.models.Kiz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds Ozon FBO labels in physical order: two product barcodes, then one KIZ page. */
public final class OzonFboKizPrintPlanner {
    private final OzonProductGtinMappingRepository mappingRepository;
    private final ZnackGtinInventoryService inventoryService;

    public OzonFboKizPrintPlanner() {
        this(new OzonProductGtinMappingRepository(), new ZnackGtinInventoryService());
    }

    OzonFboKizPrintPlanner(
            OzonProductGtinMappingRepository mappingRepository,
            ZnackGtinInventoryService inventoryService) {
        this.mappingRepository = mappingRepository;
        this.inventoryService = inventoryService;
    }

    public FboPrintPlan plan(int shopId, List<FboBarcodePrintItem> items) {
        List<FboBarcodePrintItem> safeItems = items == null ? List.of() : items.stream()
                .filter(item -> item != null && item.product() != null && item.quantity() > 0)
                .toList();
        if (safeItems.isEmpty()) return new FboPrintPlan(List.of(), List.of());

        Map<String, String> mappings = mappingRepository.findResolvedBySku(shopId);
        Map<String, Integer> neededByGtin = new LinkedHashMap<>();
        Map<String, String> gtinBySku = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (FboBarcodePrintItem item : safeItems) {
            if (!item.product().requiresKiz()) continue;
            String catalogSku = item.product().catalogSku();
            String gtin = mappings.get(catalogSku);
            if (gtin == null || gtin.isBlank()) {
                missing.add("SKU " + safe(catalogSku) + " | article " + safe(item.product().vendorCode()));
                continue;
            }
            gtinBySku.put(catalogSku, gtin);
            neededByGtin.merge(gtin, item.quantity(), Integer::sum);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Products requiring KIZ are not mapped:\n" + String.join("\n", missing));
        }

        Map<String, List<Kiz>> reservedByGtin = new HashMap<>();
        List<Kiz> allReserved = new ArrayList<>();
        try {
            for (Map.Entry<String, Integer> entry : neededByGtin.entrySet()) {
                List<Kiz> reserved = inventoryService.reserveAvailable(shopId, entry.getKey(), entry.getValue());
                reservedByGtin.put(entry.getKey(), reserved);
                allReserved.addAll(reserved);
            }
        } catch (RuntimeException error) {
            inventoryService.release(shopId, allReserved);
            throw error;
        }

        Map<String, Integer> nextIndex = new HashMap<>();
        List<FboPrintPage> pages = new ArrayList<>();
        int pairNumber = 1;
        for (FboBarcodePrintItem item : safeItems) {
            for (int unit = 0; unit < item.quantity(); unit++) {
                pages.add(FboPrintPage.barcode(item.product(), pairNumber));
                pages.add(FboPrintPage.barcode(item.product(), pairNumber));
                if (item.product().requiresKiz()) {
                    String gtin = gtinBySku.get(item.product().catalogSku());
                    int index = nextIndex.merge(gtin, 1, Integer::sum) - 1;
                    pages.add(FboPrintPage.kiz(item.product(), reservedByGtin.get(gtin).get(index).getCode(), pairNumber));
                }
                pairNumber++;
            }
        }
        return new FboPrintPlan(List.copyOf(pages), List.copyOf(allReserved));
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
