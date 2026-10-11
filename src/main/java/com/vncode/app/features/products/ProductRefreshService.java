package com.vncode.app.features.products;

import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.wb.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.*;

/** Explicit network refresh; navigation and paging continue to use local projections. */
public final class ProductRefreshService {
    @FunctionalInterface public interface CatalogRefresher {int refresh(Shop shop)throws IOException;}
    public record Report(int products,int snapshots,int failed){}
    private final CatalogRefresher catalog;private final ProductSnapshotReader reader;private final ProductRepository repository;
    public ProductRefreshService(){this(s->s.getMarketplace()==Marketplace.OZON?new OzonSyncWorkflow().syncCatalog(s):new WbSyncWorkflow().syncProducts(s),(s,k)->s.getMarketplace()==Marketplace.OZON?new OzonProductSnapshotReader().read(s,k):new WbProductSnapshotReader().read(s,k),new ProductRepository());}
    public ProductRefreshService(CatalogRefresher catalog,ProductSnapshotReader reader,ProductRepository repository){this.catalog=Objects.requireNonNull(catalog);this.reader=Objects.requireNonNull(reader);this.repository=Objects.requireNonNull(repository);}
    public Report refresh(Shop shop,ProductQuery query)throws IOException{
        ListingIdentity.require(shop);if(query.shopId()!=shop.getId()||query.marketplace()!=shop.getMarketplace())throw new SecurityException("Foreign product refresh");
        return ShopOperationCoordinator.withActiveShop(shop.getId(),()->{
            int products=catalog.refresh(shop);ListingIdentity.require(shop);
            var page=repository.loadPage(query);var cards=new LinkedHashMap<String,ProductKey>();for(var row:page.rows())cards.putIfAbsent(row.key().cardId(),row.key());
            int complete=0,failed=0;
            for(var key:cards.values()){
                if(Thread.currentThread().isInterrupted())throw new IOException("Product refresh cancelled");
                try{refreshCard(shop,key);complete++;}catch(IOException unavailable){failed++;}
            }
            return new Report(products,complete,failed);
        });
    }
    public ProductSnapshot refreshCard(Shop shop,ProductKey key)throws IOException{
        ListingIdentity.require(shop,key);var snapshot=reader.read(shop,key);repository.saveVerifiedSnapshot(shop,snapshot);return snapshot;
    }
}
