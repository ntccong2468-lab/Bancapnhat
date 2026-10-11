package com.vncode.app.integration.ozon;

import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Shop;
import java.io.IOException;

public final class OzonSyncWorkflow {
    private final java.util.function.Function<Shop,OzonApiClient> clients;
    public OzonSyncWorkflow(){this(shop->new OzonApiClient(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey())));}
    public OzonSyncWorkflow(java.util.function.Function<Shop,OzonApiClient> clients){this.clients=java.util.Objects.requireNonNull(clients);}
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
        int products = new OzonCatalogSyncService(shop.getId(), api).syncFromStart();
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

    public int syncCatalog(Shop shop)throws IOException{
        MarketplaceGuard.requireOzon(shop);return new OzonCatalogSyncService(shop.getId(),client(shop)).syncFromStart();
    }
    private OzonApiClient client(Shop shop) {
        return clients.apply(shop);
    }

}
