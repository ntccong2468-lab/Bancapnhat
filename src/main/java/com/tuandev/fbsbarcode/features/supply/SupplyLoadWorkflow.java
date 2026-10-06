package com.tuandev.fbsbarcode.features.supply;

import com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard;
import com.tuandev.fbsbarcode.integration.wb.WbSupplyWorkflow;
import com.tuandev.fbsbarcode.integration.wb.WbSyncWorkflow;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;

import java.io.IOException;
import java.util.List;

public class SupplyLoadWorkflow {
    private final WbSyncWorkflow wbSyncWorkflow = new WbSyncWorkflow();
    private final WbSupplyWorkflow wbSupplyWorkflow = new WbSupplyWorkflow();

    public List<Order> loadLocal(Shop shop, String supplyId) {
        MarketplaceGuard.requireWildberries(shop);
        return wbSupplyWorkflow.loadOrdersForSupplyLocal(shop, supplyId);
    }

    public List<Order> refreshSupplyData(Shop shop, String supplyId) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        wbSyncWorkflow.syncSupplyOrdersAndStatuses(shop, supplyId);
        return wbSupplyWorkflow.loadOrdersForSupply(shop, supplyId);
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
