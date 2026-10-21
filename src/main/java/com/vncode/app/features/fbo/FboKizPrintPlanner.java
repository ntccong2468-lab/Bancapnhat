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
        var shop=new com.vncode.app.features.shop.ShopRepository().findById(shopId);
        if(shop==null||shop.getMarketplace()!=market)throw new IllegalArgumentException("FBO_SHOP_CHANGED");
        var demands=new ArrayList<com.vncode.app.features.kiz.KizCategoryAllocator.Demand>();
        for(int i=0;i<safeItems.size();i++)if(safeItems.get(i).product().requiresKiz())demands.add(new com.vncode.app.features.kiz.KizCategoryAllocator.Demand(Integer.toString(i),safeItems.get(i).product().productId(),safeItems.get(i).quantity()));
        Map<String,List<Kiz>> categories=demands.isEmpty()?Map.of():new com.vncode.app.features.kiz.KizCategoryAllocator().reservePartial(shop,demands);
        Map<String,List<Kiz>> reservedByGtin=new HashMap<>();List<Kiz> allReserved=new ArrayList<>();categories.values().forEach(allReserved::addAll);
        try {
            var neededByGtin=new LinkedHashMap<String,Integer>();var missing=new ArrayList<String>();
            for(int i=0;i<safeItems.size();i++){
                var item=safeItems.get(i);if(!item.product().requiresKiz())continue;
                int remainder=item.quantity()-categories.getOrDefault(Integer.toString(i),List.of()).size();
                if(remainder==0)continue;String gtin=mappings.get(item.product());
                if(gtin==null)missing.add("nmId "+item.product().nmId()+" | article "+safe(item.product().vendorCode()));
                else neededByGtin.merge(gtin,remainder,Integer::sum);
            }
            if(!missing.isEmpty())throw new IllegalStateException("Products requiring new KIZ are not mapped:\n"+String.join("\n",missing));
            if(!neededByGtin.isEmpty()){
                LicenseRuntime.require(Permission.PRINT_NEW);reservedByGtin.putAll(inventoryService.reserveAvailableBulk(shopId,neededByGtin));reservedByGtin.values().forEach(allReserved::addAll);
            }
        }catch(RuntimeException failure){inventoryService.release(shopId,allReserved);throw failure;}

        Map<String, Integer> nextIndex = new HashMap<>();
        List<FboPrintPage> pages = new ArrayList<>();
        int pairNumber = 1;
        try {
            for (int itemIndex=0;itemIndex<safeItems.size();itemIndex++) {
                FboBarcodePrintItem item=safeItems.get(itemIndex);
                var categoryCodes=categories.getOrDefault(Integer.toString(itemIndex),List.of());
                LicenseRuntime.require(Permission.PRINT_NEW);
                for (int i = 0; i < item.quantity(); i++) {
                    String code = null;
                    if (item.product().requiresKiz()) {
                        if(i<categoryCodes.size())code=categoryCodes.get(i).getCode();
                        else {String gtin=GtinNormalizer.normalize(mappings.get(item.product()));code=reservedByGtin.get(gtin).get(nextIndex.merge(gtin,1,Integer::sum)-1).getCode();}
                    }
                    FboProductSku verified=item.product().requiresKiz()&&mappings.containsKey(item.product())?item.product().withVerifiedGtin(mappings.get(item.product())):item.product();
                    pages.add(FboPrintPage.barcode(verified, pairNumber,i<categoryCodes.size()));
                    pages.add(FboPrintPage.barcode(verified, pairNumber,i<categoryCodes.size()));
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
