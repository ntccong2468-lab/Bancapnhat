package com.tuandev.fbsbarcode.integration.ozon;

import com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard;
import com.tuandev.fbsbarcode.models.Shop;
import java.io.IOException;

public final class OzonSyncWorkflow {
    public OzonConnectionCheck checkConnection(Shop shop) throws IOException {
        MarketplaceGuard.requireOzon(shop);
        return client(shop).checkConnection();
    }

    public OzonSyncReport syncOverview(Shop shop) throws IOException {
        MarketplaceGuard.requireOzon(shop);
        OzonApiClient api = client(shop);
        OzonPostingSyncService postingSync = new OzonPostingSyncService(shop.getId(), api);
        postingSync.syncUnfulfilled();
        OzonSyncReport postings = postingSync.sync();
        postingSync.refreshActiveDetails();
        int products = new OzonCatalogSyncService(shop.getId(), api).sync();
        return new OzonSyncReport(products, postings.postings(), postings.items());
    }

    /** Lightweight queue refresh used when opening the FBS workspace or its Packing tab. */
    public int syncCurrentPostings(Shop shop) throws IOException {
        MarketplaceGuard.requireOzon(shop);
        return new OzonPostingSyncService(shop.getId(), client(shop)).syncUnfulfilled();
    }

    /** Refreshes one posting before a print decision so stale list data cannot omit a KIZ rule. */
    public OzonPostingDto refreshPosting(Shop shop, String postingNumber) throws IOException {
        MarketplaceGuard.requireOzon(shop);
        return new OzonPostingSyncService(shop.getId(), client(shop)).refresh(postingNumber, false);
    }

    private static OzonApiClient client(Shop shop) {
        return new OzonApiClient(shop.getId(), new OzonCredentials(shop.getClientId(), shop.getApiKey()));
    }

}
