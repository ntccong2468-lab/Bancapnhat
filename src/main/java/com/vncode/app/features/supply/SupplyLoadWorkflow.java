package com.vncode.app.features.supply;

import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.integration.wb.WbSupplyWorkflow;
import com.vncode.app.integration.wb.WbSyncWorkflow;
import com.vncode.app.models.Order;
import com.vncode.app.models.Shop;

import java.io.IOException;
import java.util.List;

public class SupplyLoadWorkflow {
    private final WbSyncWorkflow wbSyncWorkflow = new WbSyncWorkflow();
    private final WbSupplyWorkflow wbSupplyWorkflow = new WbSupplyWorkflow();

    public List<Order> loadLocal(Shop shop, String supplyId) {
        MarketplaceGuard.requireWildberries(shop);
        return wbSupplyWorkflow.loadOrdersForSupplyData(shop, supplyId);
    }

    /** A local status read has no new sticker/image/KIZ evidence. Keep the displayed cache for
     * the same order and variant; export still performs its independent remote KIZ verification. */
    public static void preserveDisplayCache(List<Order> previous,List<Order> fresh){
        var byId=new java.util.HashMap<Long,Order>();
        for(var row:previous)byId.put(row.getId(),row);
        for(var row:fresh){
            var old=byId.get(row.getId());if(old==null)continue;
            if(row.getSticker()==null||row.getSticker().isBlank())row.setSticker(old.getSticker());
            if(row.getStickerTail()==null||row.getStickerTail().isBlank())row.setStickerTail(old.getStickerTail());
            if(row.getStickerCode()==null||row.getStickerCode().isBlank())row.setStickerCode(old.getStickerCode());
            if(row.getImage()==null&&java.util.Objects.equals(row.getImageUrl(),old.getImageUrl()))row.setImage(old.getImage());
            if(java.util.Objects.equals(row.getNmId(),old.getNmId())&&java.util.Objects.equals(row.getSize(),old.getSize())&&java.util.Objects.equals(row.getColor(),old.getColor())&&java.util.Objects.equals(row.getBarcode(),old.getBarcode())){
                if(row.getKiz()==null||row.getKiz().isBlank())row.setKiz(old.getKiz());
                if(old.isRequiresKiz())row.setRequiresKiz(true);
            }
        }
    }

    public List<Order> refreshSupplyData(Shop shop, String supplyId) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        wbSyncWorkflow.syncSupplyOrdersAndStatuses(shop, supplyId);
        return wbSupplyWorkflow.loadOrdersForSupplyDisplay(shop, supplyId);
    }

    public boolean hasMissingProducts(Shop shop, String supplyId) {
        MarketplaceGuard.requireWildberries(shop);
        return wbSupplyWorkflow.hasMissingProducts(shop, supplyId);
    }

    public List<Order> enrichStickers(Shop shop, List<Order> orders) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        wbSupplyWorkflow.enrichOrderStickers(shop, orders);
        return orders;
    }

    public void ensureImages(List<Order> orders) {
        wbSupplyWorkflow.ensureOrderImages(orders);
    }
}
