package com.tuandev.fbsbarcode.features.shop;

import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.features.shop.ShopRepository;

import java.util.List;
import java.util.Optional;

public class ShopWorkflow {
    private final ShopDialogService shopDialogService = new ShopDialogService();
    private final ShopRepository shopRepository = new ShopRepository();

    public List<Shop> loadShops() {
        return shopRepository.findAll();
    }

    public Optional<Shop> requestCreateShop() {
        return shopDialogService.showCreateDialog();
    }

    public int createShop(Shop shop) {
        return shopRepository.insert(shop);
    }

    public Optional<Shop> requestUpdateShop(Shop shop) {
        return shopDialogService.showUpdateDialog(shop);
    }

    public int updateShop(int id, Shop shop) {
        return shopRepository.update(id, shop);
    }

    public int deleteShop(int id) {
        return shopRepository.delete(id);
    }
}
