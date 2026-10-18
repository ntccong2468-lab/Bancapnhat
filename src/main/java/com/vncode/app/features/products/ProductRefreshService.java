package com.vncode.app.features.products;

import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.wb.*;
import com.vncode.app.models.Shop;
import com.vncode.app.features.license.LicenseRuntime;
import static com.vncode.app.features.license.LicenseModels.Permission.SELLER_API;
import java.io.IOException;
import java.util.*;

/** Explicit network refresh; navigation and paging continue to use local projections. */
public final class ProductRefreshService {
    @FunctionalInterface public interface CatalogRefresher {int refresh(Shop shop)throws IOException;}
    public record Report(int products,int snapshots,int failed){}
    public record Progress(int completed,int total,int failed){}
    private final CatalogRefresher catalog;private final ProductSnapshotReader reader;private final ProductRepository repository;
    public ProductRefreshService(){this(s->s.getMarketplace()==Marketplace.OZON?new OzonSyncWorkflow().syncCatalog(s):new WbSyncWorkflow().syncAllProducts(s),(s,k)->s.getMarketplace()==Marketplace.OZON?new OzonProductSnapshotReader().readForSynchronization(s,k):new WbProductSnapshotReader().readForSynchronization(s,k),new ProductRepository());}
    public ProductRefreshService(CatalogRefresher catalog,ProductSnapshotReader reader,ProductRepository repository){this.catalog=Objects.requireNonNull(catalog);this.reader=Objects.requireNonNull(reader);this.repository=Objects.requireNonNull(repository);}
    public Report refresh(Shop shop,ProductQuery query)throws IOException{
        return refresh(shop,query,p->{});
    }
    public Report refresh(Shop shop,ProductQuery query,java.util.function.Consumer<Progress> progress)throws IOException{
        ListingIdentity.require(shop);if(query.shopId()!=shop.getId()||query.marketplace()!=shop.getMarketplace())throw new SecurityException("Foreign product refresh");
        return ShopOperationCoordinator.withActiveShop(shop.getId(),()->{
            LicenseRuntime.require(SELLER_API);
            int products=catalog.refresh(shop);ListingIdentity.require(shop);
            var cards=repository.synchronizationKeys(shop.getId(),shop.getMarketplace());
            int complete=0,failed=0;
            progress.accept(new Progress(0,cards.size(),0));
            for(var key:cards){
                if(Thread.currentThread().isInterrupted())throw new IOException("Product refresh cancelled");
                try{refreshCard(shop,key);complete++;}catch(IOException unavailable){failed++;}
                progress.accept(new Progress(complete+failed,cards.size(),failed));
            }
            return new Report(products,complete,failed);
        });
    }
    public ProductSnapshot refreshCard(Shop shop,ProductKey key)throws IOException{
        ListingIdentity.require(shop,key);LicenseRuntime.require(SELLER_API);var snapshot=reader.read(shop,key);repository.saveVerifiedSnapshot(shop,snapshot);return snapshot;
    }
}
