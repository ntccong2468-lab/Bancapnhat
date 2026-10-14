package com.vncode.app.features.fbo;

import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.GtinNormalizer;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.znack.ZnackGtinInventoryService;
import com.vncode.app.models.Kiz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FboKizPrintPlanner {
    private final FboVerifiedGtinRepository mappingRepository;
    private final ZnackGtinInventoryService inventoryService;

    public FboKizPrintPlanner() {
        this(new FboVerifiedGtinRepository(), new ZnackGtinInventoryService());
    }

    FboKizPrintPlanner(FboVerifiedGtinRepository mappingRepository, ZnackGtinInventoryService inventoryService) {
        this.mappingRepository = mappingRepository;
        this.inventoryService = inventoryService;
    }

    public FboPrintPlan plan(int shopId,List<FboBarcodePrintItem> items){return plan(shopId,Marketplace.WILDBERRIES,items);}

    public FboPrintPlan plan(int shopId,Marketplace market,List<FboBarcodePrintItem> items) {
        LicenseRuntime.require(Permission.PRINT_NEW);
        List<FboBarcodePrintItem> safeItems = items == null ? List.of() : items.stream()
                .filter(item -> item != null && item.product() != null && item.quantity() > 0).toList();
        if (safeItems.isEmpty()) return new FboPrintPlan(List.of(), List.of());

        if(safeItems.size()>10000||safeItems.stream().mapToLong(FboBarcodePrintItem::quantity).sum()>100000)throw new IllegalArgumentException("FBO batch is too large");
        Map<FboProductSku,String> mappings=mappingRepository.resolve(shopId,market,safeItems.stream().map(FboBarcodePrintItem::product).filter(FboProductSku::requiresKiz).distinct().toList());
        Map<String, Integer> neededByGtin = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (FboBarcodePrintItem item : safeItems) {
            if (!item.product().requiresKiz()) continue;
            String gtin = mappings.get(item.product());
            if (gtin == null) {
                missing.add("nmId " + item.product().nmId() + " | article " + safe(item.product().vendorCode()));
            } else {
                neededByGtin.merge(gtin, item.quantity(), Integer::sum);
            }
        }
        if (!missing.isEmpty()) throw new IllegalStateException("Products requiring KIZ are not mapped:\n" + String.join("\n", missing));

        Map<String, List<Kiz>> reservedByGtin = new HashMap<>();
        List<Kiz> allReserved = new ArrayList<>();
        try {
            if(!neededByGtin.isEmpty()) {
                LicenseRuntime.require(Permission.PRINT_NEW);
                reservedByGtin.putAll(inventoryService.reserveAvailableBulk(shopId,neededByGtin));
                reservedByGtin.values().forEach(allReserved::addAll);
            }
        } catch (RuntimeException e) {
            inventoryService.release(shopId, allReserved);
            throw e;
        }

        Map<String, Integer> nextIndex = new HashMap<>();
        List<FboPrintPage> pages = new ArrayList<>();
        int pairNumber = 1;
        try {
            for (FboBarcodePrintItem item : safeItems) {
                LicenseRuntime.require(Permission.PRINT_NEW);
                for (int i = 0; i < item.quantity(); i++) {
                    String code = null;
                    if (item.product().requiresKiz()) {
                        String gtin = GtinNormalizer.normalize(mappings.get(item.product()));
                        code = reservedByGtin.get(gtin).get(nextIndex.merge(gtin, 1, Integer::sum) - 1).getCode();
                    }
                    FboProductSku verified=item.product().requiresKiz()?item.product().withVerifiedGtin(mappings.get(item.product())):item.product();
                    pages.add(FboPrintPage.barcode(verified, pairNumber));
                    pages.add(FboPrintPage.barcode(verified, pairNumber));
                    if (code != null && !code.isBlank()) {
                        pages.add(FboPrintPage.kiz(verified, code, pairNumber));
                    }
                    pairNumber++;
                }
            }
            LicenseRuntime.require(Permission.PRINT_NEW);
            return new FboPrintPlan(List.copyOf(pages), List.copyOf(allReserved));
        } catch (RuntimeException error) {
            inventoryService.release(shopId, allReserved);
            throw error;
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
