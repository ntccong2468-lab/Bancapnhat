package com.vncode.app.integration.ozon;

import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Shop;
import com.vncode.app.features.license.LicenseRuntime;
import static com.vncode.app.features.license.LicenseModels.Permission.SELLER_API;
import java.io.IOException;

public final class OzonSyncWorkflow {
    private final java.util.function.Function<Shop,OzonApiClient> clients;
    public OzonSyncWorkflow(){this(shop->new OzonApiClient(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey())));}
    public OzonSyncWorkflow(java.util.function.Function<Shop,OzonApiClient> clients){this.clients=java.util.Objects.requireNonNull(clients);}
    public OzonConnectionCheck checkConnection(Shop shop) throws IOException {
        MarketplaceGuard.requireOzon(shop);
        LicenseRuntime.require(SELLER_API);
        return client(shop).checkConnection();
    }

    public OzonSyncReport syncOverview(Shop shop) throws IOException {
        return syncOverview(shop,true);
    }
    public OzonSyncReport syncOverviewIfNeeded(Shop shop)throws IOException{
        return syncOverview(shop,false);
    }
    private OzonSyncReport syncOverview(Shop shop,boolean refreshCatalog)throws IOException{
        MarketplaceGuard.requireOzon(shop);
        LicenseRuntime.require(SELLER_API);
        OzonApiClient api = client(shop);
        OzonPostingSyncService postingSync = new OzonPostingSyncService(shop.getId(), api);
        postingSync.syncUnfulfilled();
        OzonSyncReport postings = postingSync.sync();
        reconcilePackages(shop,api);
        postingSync.refreshActiveDetails();
        var catalog=new OzonCatalogSyncService(shop.getId(),api);
        int products = refreshCatalog?catalog.syncFromStart():catalog.syncIfNeeded();
        return new OzonSyncReport(products, postings.postings(), postings.items());
    }

    /** Lightweight queue refresh used when opening the FBS workspace or its Packing tab. */
    public int syncCurrentPostings(Shop shop) throws IOException {
        MarketplaceGuard.requireOzon(shop);
        LicenseRuntime.require(SELLER_API);
        var api=client(shop);int changed=new OzonPostingSyncService(shop.getId(),api).syncUnfulfilled();
        reconcilePackages(shop,api);return changed;
    }

    /** Refreshes one posting before a print decision so stale list data cannot omit a KIZ rule. */
    public OzonPostingDto refreshPosting(Shop shop, String postingNumber) throws IOException {
        MarketplaceGuard.requireOzon(shop);
        LicenseRuntime.require(SELLER_API);
        return new OzonPostingSyncService(shop.getId(), client(shop)).refresh(postingNumber, false);
    }

    public int syncCatalog(Shop shop)throws IOException{
        MarketplaceGuard.requireOzon(shop);LicenseRuntime.require(SELLER_API);return new OzonCatalogSyncService(shop.getId(),client(shop)).syncFromStart();
    }
    private OzonApiClient client(Shop shop) {
        return clients.apply(shop);
    }

    private static void reconcilePackages(Shop shop,OzonApiClient api){
        try{new OzonShipService().reconcilePendingReadOnly(shop,api,3);}
        catch(RuntimeException waiting){org.slf4j.LoggerFactory.getLogger(OzonSyncWorkflow.class).warn("Ozon package readback is pending; normal synchronization completed: {}",waiting.getClass().getSimpleName());}
    }

}
