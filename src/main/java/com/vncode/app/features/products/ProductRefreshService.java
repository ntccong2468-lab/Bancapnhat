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
import java.io.InterruptedIOException;
import java.util.*;

/** Explicit network refresh; navigation and paging continue to use local projections. */
public final class ProductRefreshService {
    @FunctionalInterface public interface CatalogRefresher {int refresh(Shop shop)throws IOException;}
    public record Failure(ProductKey key,String code,List<String> missingFields,int httpStatus){
        public Failure{Objects.requireNonNull(key);Objects.requireNonNull(code);missingFields=List.copyOf(missingFields);}
    }
    public record Report(int products,int snapshots,int failed,List<Failure> failures){
        public Report{failures=List.copyOf(failures);}
        public Report(int products,int snapshots,int failed){this(products,snapshots,failed,List.of());}
    }
    public record Progress(int completed,int total,int failed){}
    private final CatalogRefresher catalog;private final ProductSnapshotReader reader;private final ProductRepository repository;
    public ProductRefreshService(){this(s->s.getMarketplace()==Marketplace.OZON?new OzonSyncWorkflow().syncCatalog(s):new WbSyncWorkflow().syncAllProducts(s),synchronizationReader(),new ProductRepository());}
    private static ProductSnapshotReader synchronizationReader(){
        var wb=new WbProductSnapshotReader();var ozon=new OzonProductSnapshotReader();
        return new ProductSnapshotReader(){
            // These readers return complete fresh facts without a second internal persistence.
            public ProductSnapshot read(Shop shop,ProductKey key)throws IOException{
                return shop.getMarketplace()==Marketplace.OZON?ozon.readForGtinRegistrationProof(shop,key):wb.readForRegistration(shop,key);
            }
            public Batch openBatch(Shop shop,List<ProductKey> keys)throws IOException{
                return shop.getMarketplace()==Marketplace.WILDBERRIES?wb.openBatch(shop,keys):ProductSnapshotReader.super.openBatch(shop,keys);
            }
        };
    }
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
            int complete=0,failed=0;var failures=new ArrayList<Failure>();
            progress.accept(new Progress(0,cards.size(),0));
            for(int start=0;start<cards.size();start+=10_000){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Product refresh cancelled");
                var chunk=cards.subList(start,Math.min(start+10_000,cards.size()));
                // A list/auth/network failure while preparing a batch is not a missing product.
                try(var batch=reader.openBatch(shop,chunk)){
                    for(var key:chunk){
                        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Product refresh cancelled");
                        try{
                            ListingIdentity.require(shop,key);LicenseRuntime.require(SELLER_API);
                            var snapshot=batch.read(key);
                            if(!snapshot.key().equals(key))throw new SecurityException("Foreign product refresh snapshot");
                            repository.saveVerifiedSnapshot(shop,snapshot);complete++;
                        }catch(InterruptedIOException interrupted){
                            if(interrupted instanceof java.net.SocketTimeoutException&&!Thread.currentThread().isInterrupted()){
                                failures.add(failure(key,interrupted));failed++;
                            }else throw interrupted;
                        }
                        catch(com.vncode.app.integration.listing.ListingRejectedException rejected){
                            if(rejected.statusCode()==401||rejected.statusCode()==403)throw rejected;
                            failures.add(failure(key,rejected));failed++;
                        }catch(IOException unavailable){failures.add(failure(key,unavailable));failed++;}
                        progress.accept(new Progress(complete+failed,cards.size(),failed));
                    }
                }
            }
            return new Report(products,complete,failed,failures);
        });
    }
    public ProductSnapshot refreshCard(Shop shop,ProductKey key)throws IOException{
        ListingIdentity.require(shop,key);LicenseRuntime.require(SELLER_API);var snapshot=reader.read(shop,key);repository.saveVerifiedSnapshot(shop,snapshot);return snapshot;
    }
    private static Failure failure(ProductKey key,IOException error){
        if(error instanceof WbProductSnapshotReader.CardReadException card)return new Failure(key,card.code(),card.missingFields(),0);
        if(error instanceof com.vncode.app.integration.listing.ListingRateLimitException)return new Failure(key,"PRODUCT_DETAILS_RATE_LIMIT",List.of(),429);
        if(error instanceof com.vncode.app.integration.listing.ListingRejectedException rejected)return new Failure(key,"PRODUCT_DETAILS_HTTP_ERROR",List.of(),rejected.statusCode());
        if(error instanceof java.net.SocketTimeoutException)return new Failure(key,"PRODUCT_DETAILS_TIMEOUT",List.of(),0);
        return new Failure(key,"PRODUCT_DETAILS_UNAVAILABLE",List.of(),0);
    }
}
