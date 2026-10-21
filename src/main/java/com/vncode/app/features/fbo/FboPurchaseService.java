package com.vncode.app.features.fbo;

import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.util.*;

/** Enqueue only a fresh, explicitly approved shortage; the existing durable pipeline performs it. */
public final class FboPurchaseService {
    public void enqueue(Shop shop,List<FboBarcodePrintItem> selection,FboPreflightService.Result approved,boolean confirmed)throws Exception {
        if(!confirmed||approved.toBuy().isEmpty())throw new IllegalArgumentException("Purchase confirmation required");
        ShopOperationCoordinator.withActiveShop(shop.getId(),()->{
            ListingIdentity.require(shop);var fresh=new FboPreflightService().inspect(shop,selection);
            if(!fresh.toBuy().equals(approved.toBuy()))throw new IllegalStateException("FBO_PURCHASE_PREVIEW_CHANGED");
            var repository=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName()));
            var settings=repository.getSettings();var coordinator=ZnackPurchaseCoordinator.create(repository);
            // Validate the complete batch before creating the first purchase checkpoint.
            for(var entry:fresh.toBuy().entrySet())coordinator.validatePrerequisites(settings,entry.getKey(),entry.getValue());
            String request=UUID.randomUUID().toString();
            for(var entry:fresh.toBuy().entrySet()) {
                ListingIdentity.require(shop);
                coordinator.enqueue(settings,entry.getKey(),entry.getValue(),requestKey(request,entry.getKey()));
            }
            coordinator.resumeAsync(settings);return null;
        });
    }
    /** One canonical UUID per GTIN within this seller-confirmed intent. */
    static String requestKey(String intent,String gtin){
        return UUID.nameUUIDFromBytes((UUID.fromString(intent)+"\n"+GtinNormalizer.normalize(gtin)).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}
