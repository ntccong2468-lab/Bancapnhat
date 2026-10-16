package com.vncode.app.features.sync;

import com.vncode.app.features.catalogsync.CatalogSyncModels.Selection;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.products.*;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.wb.*;
import com.vncode.app.models.Shop;
import java.util.*;

/** Fresh reads prepare a review; only the existing confirmed Catalog queue can write. */
public final class ZnackGtinReviewService {
    @FunctionalInterface interface MarketplaceRefresh {void refresh(Shop shop)throws Exception;}
    private final MarketplaceRefresh marketplace;
    private final ZnackAutoSyncService source;
    private final ProductSnapshotReader products;
    private final SyncRunRepository reports=new SyncRunRepository();
    public ZnackGtinReviewService(){this(shop->{
        var ready=new RegistrationSources().refresh(shop,step->{});
        if(!ready.ready())throw new IllegalStateException(ready.reason());
    },new ZnackAutoSyncService(false),(shop,key)->shop.getMarketplace()==Marketplace.OZON?new OzonProductSnapshotReader().readForSynchronization(shop,key):new WbProductSnapshotReader().readForSynchronization(shop,key));}
    ZnackGtinReviewService(MarketplaceRefresh marketplace,ZnackAutoSyncService source,ProductSnapshotReader products){this.marketplace=marketplace;this.source=source;this.products=products;}
    public String prepare(Shop shop)throws Exception {
        return ShopOperationCoordinator.withActiveShop(shop.getId(),()->{
            ListingIdentity.require(shop);marketplace.refresh(shop);ListingIdentity.require(shop);
            return source.review(shop,new RegistrationSources().require(shop).run());
        });
    }
    public List<SyncRunRepository.Item> page(Shop shop,String run,int offset){require(shop,run);return reports.items(run,offset,300);}
    public List<Selection> select(Shop shop,String run,List<SyncRunRepository.Item> rows)throws Exception {
        return ShopOperationCoordinator.withActiveShop(shop.getId(),()->{
            require(shop,run);
            if(rows.isEmpty()||rows.size()>1000||rows.stream().map(SyncRunRepository.Item::id).distinct().count()!=rows.size())throw new IllegalArgumentException("CATALOG_SELECTION_LIMIT");
            var known=new HashMap<String,SyncRunRepository.Item>();
            for(int offset=0;;offset+=1000){var page=reports.items(run,offset,1000);page.forEach(row->known.put(row.id(),row));if(page.size()<1000)break;}
            var result=new ArrayList<Selection>();
            for(var row:rows){
                if(!row.equals(known.get(row.id()))||!eligible(row))throw new IllegalArgumentException("GTIN_REVIEW_CHANGED");
                new RegistrationSources().requireMember(shop,row.productId(),row.variantId());
                RegisteredGtinProof.requireCurrent(shop,row.gtin());
                var key=new ProductKey(shop.getId(),shop.getMarketplace(),row.productId(),row.variantId());
                var fresh=products.read(shop,key);
                if(!key.equals(fresh.key()))throw new SecurityException("PRODUCT_MISMATCH");
                var variant=fresh.variants().stream().filter(v->v.id().equals(key.variantId())).findFirst().orElseThrow(()->new IllegalArgumentException("VARIANT_NOT_FOUND"));
                if(!same(row.color(),variant.color())||!same(row.size(),variant.size()))throw new IllegalArgumentException("GTIN_VARIANT_CONFLICT");
                if(shop.getMarketplace()==Marketplace.OZON){
                    if(row.sku().isBlank()||!row.sku().equals(fresh.metadata().get("sku")))throw new IllegalArgumentException("OZON_SKU_CHANGED");
                    String title=com.google.gson.JsonParser.parseString(row.evidence()).getAsJsonObject().get("title").getAsString();
                    if(title.isBlank()||!same(title,fresh.title()))throw new IllegalArgumentException("OZON_TITLE_CHANGED");
                }
                result.add(new Selection(new GtinSyncModels.ProductKey(shop.getId(),shop.getMarketplace(),row.productId(),row.variantId()),row.gtin()));
            }
            return List.copyOf(result);
        });
    }
    public static boolean eligible(SyncRunRepository.Item row){return row.state().equals("AWAITING_CONFIRMATION")&&!row.productId().startsWith("catalog:")&&!row.gtin().isBlank();}
    private void require(Shop shop,String run){
        ListingIdentity.require(shop);var report=reports.find(run).orElseThrow(()->new IllegalArgumentException("SYNC_JOB_NOT_FOUND"));
        if(report.shopId()!=shop.getId()||!report.marketplace().equals(shop.getMarketplace().name())||!report.kind().equals("znack_marketplace"))throw new SecurityException("SYNC_OWNER_CHANGED");
        // Repository methods also check the captured credential fingerprint, not just the ID.
        reports.assertOwner(shop,run);new RegistrationSources().require(shop);
    }
    private static boolean same(String left,String right){return GtinMatchingService.normalize(left).equals(GtinMatchingService.normalize(right));}
}
