package com.tuandev.fbsbarcode.ui.workspace;

import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.models.Shop;

import java.util.ArrayList;
import java.util.List;

final class WorkspaceState {
    private List<Shop> shops = new ArrayList<>();
    private List<Order> displayedOrders = new ArrayList<>();
    private List<Order> loadedOrdersRaw = new ArrayList<>();
    private Shop selectedShop;
    private Integer pendingSelectShopId;
    private String loadedSupplyId;
    private String loadedSupplyName;
    private boolean selectedShopTokenValid = true;
    private String selectedShopTokenMessage;
    private long supplyRequestToken;

    List<Shop> getShops() {
        return shops;
    }

    void setShops(List<Shop> shops) {
        this.shops = shops == null ? new ArrayList<>() : new ArrayList<>(shops);
    }

    List<Order> getDisplayedOrders() {
        return displayedOrders;
    }

    void setDisplayedOrders(List<Order> displayedOrders) {
        this.displayedOrders = displayedOrders == null ? new ArrayList<>() : new ArrayList<>(displayedOrders);
    }

    List<Order> getLoadedOrdersRaw() {
        return loadedOrdersRaw;
    }

    void setLoadedOrdersRaw(List<Order> loadedOrdersRaw) {
        this.loadedOrdersRaw = loadedOrdersRaw == null ? new ArrayList<>() : new ArrayList<>(loadedOrdersRaw);
    }

    Shop getSelectedShop() {
        return selectedShop;
    }

    void setSelectedShop(Shop selectedShop) {
        this.selectedShop = selectedShop;
    }

    Integer getPendingSelectShopId() {
        return pendingSelectShopId;
    }

    void setPendingSelectShopId(Integer pendingSelectShopId) {
        this.pendingSelectShopId = pendingSelectShopId;
    }

    String getLoadedSupplyId() {
        return loadedSupplyId;
    }

    void setLoadedSupplyId(String loadedSupplyId) {
        this.loadedSupplyId = loadedSupplyId;
    }

    String getLoadedSupplyName() {
        return loadedSupplyName;
    }

    void setLoadedSupplyName(String loadedSupplyName) {
        this.loadedSupplyName = loadedSupplyName;
    }

    boolean isSelectedShopTokenValid() {
        return selectedShopTokenValid;
    }

    void setSelectedShopTokenValid(boolean selectedShopTokenValid) {
        this.selectedShopTokenValid = selectedShopTokenValid;
    }

    String getSelectedShopTokenMessage() {
        return selectedShopTokenMessage;
    }

    void setSelectedShopTokenMessage(String selectedShopTokenMessage) {
        this.selectedShopTokenMessage = selectedShopTokenMessage;
    }

    long nextSupplyRequestToken() {
        return ++supplyRequestToken;
    }

    long getSupplyRequestToken() {
        return supplyRequestToken;
    }

    List<Order> getOrders() {
        return loadedOrdersRaw;
    }

    void setOrders(List<Order> orders) {
        this.loadedOrdersRaw = orders == null ? new ArrayList<>() : new ArrayList<>(orders);
        this.displayedOrders = this.loadedOrdersRaw;
    }

    Shop removeShopAndChooseFallback(int shopId) {
        int removedIndex = -1;
        for (int index = 0; index < shops.size(); index++) {
            if (shops.get(index).getId() == shopId) {
                removedIndex = index;
                break;
            }
        }
        if (removedIndex >= 0) shops.remove(removedIndex);
        if (selectedShop == null || selectedShop.getId() != shopId) return selectedShop;
        selectedShop = null;
        if (shops.isEmpty()) return null;
        return shops.get(Math.min(Math.max(0, removedIndex), shops.size() - 1));
    }

    void clearWorkspace() {
        selectedShop = null;
        selectedShopTokenValid = true;
        selectedShopTokenMessage = null;
        clearLoadedSupply();
        displayedOrders.clear();
    }

    void clearLoadedSupply() {
        supplyRequestToken++;
        loadedSupplyId = null;
        loadedSupplyName = null;
        loadedOrdersRaw = new ArrayList<>();
        displayedOrders = new ArrayList<>();
    }
}
