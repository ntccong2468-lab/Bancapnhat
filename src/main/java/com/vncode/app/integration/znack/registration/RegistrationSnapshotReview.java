package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.util.*;

/** Fresh facts for the whole frozen selection, without one network read per WB size. */
public final class RegistrationSnapshotReview {
    @FunctionalInterface public interface Reader{ProductSnapshot read(Shop shop,ProductKey key)throws Exception;}
    public record Problem(ProductKey key,String code,List<String> missingFields){
        public Problem{Objects.requireNonNull(key);Objects.requireNonNull(code);missingFields=List.copyOf(missingFields);}
    }
    public record Result(List<ProductSnapshot> snapshots,List<Problem> problems){
        public Result{snapshots=List.copyOf(snapshots);problems=List.copyOf(problems);}
    }
    private final Reader reader;
    public RegistrationSnapshotReview(){reader=null;}
    public RegistrationSnapshotReview(Reader reader){this.reader=Objects.requireNonNull(reader);}
    public List<ProductSnapshot> read(Shop shop,List<ProductKey> selected)throws Exception{
        return collect(shop,selected,false).snapshots();
    }
    /** Only proven, card-local WB failures may be offered for explicit exclusion.
     * Authentication, cancellation, transport and owner changes still stop preparation. */
    public Result readAvailable(Shop shop,List<ProductKey> selected)throws Exception{
        return collect(shop,selected,true);
    }
    private Result collect(Shop shop,List<ProductKey> selected,boolean partial)throws Exception{
        ListingIdentity.require(shop);var snapshots=new LinkedHashMap<String,ProductSnapshot>();var results=new ArrayList<ProductSnapshot>();var repository=new ProductRepository();long retained=0;
        var keys=new LinkedHashSet<>(selected);
        var problems=new ArrayList<Problem>();var failedCards=new HashMap<String,Problem>();
        try(var checks=ListingIdentity.batchChecks(shop);
            var batch=reader==null&&shop.getMarketplace()==Marketplace.WILDBERRIES&&keys.stream().map(ProductKey::cardId).distinct().limit(6).count()>5
                    ?new com.vncode.app.integration.wb.WbRegistrationSnapshotBatch(shop,List.copyOf(keys)):null){
        for(var key:keys)ListingIdentity.require(shop,key);
        Reader fresh=reader!=null?reader:batch!=null?(owner,key)->batch.read(key)
                :shop.getMarketplace()==Marketplace.WILDBERRIES?(owner,key)->new com.vncode.app.integration.wb.WbProductSnapshotReader().readForRegistration(owner,key)
                :(owner,key)->new com.vncode.app.integration.ozon.OzonProductSnapshotReader().readForSynchronization(owner,key);
        for(var key:keys){
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("ZNACK_PREFLIGHT_CANCELLED");ListingIdentity.require(shop,key);
            var previousFailure=failedCards.get(key.cardId());
            if(previousFailure!=null){problems.add(new Problem(key,previousFailure.code(),previousFailure.missingFields()));continue;}
            var snapshot=snapshots.get(key.cardId());
            if(snapshot==null){
                try{snapshot=fresh.read(shop,key);}
                catch(com.vncode.app.integration.wb.WbProductSnapshotReader.CardReadException unavailable){
                    if(!partial||shop.getMarketplace()!=Marketplace.WILDBERRIES||!key.equals(unavailable.key())
                            ||!Set.of("WB_CARD_NOT_FOUND","WB_CARD_INCOMPLETE","WB_VARIANT_NOT_FOUND").contains(unavailable.code()))throw unavailable;
                    var problem=new Problem(key,unavailable.code(),unavailable.missingFields());problems.add(problem);
                    if(!unavailable.code().equals("WB_VARIANT_NOT_FOUND"))failedCards.put(key.cardId(),problem);
                    continue;
                }
                if(snapshot==null||!snapshot.key().equals(key)||!ListingIdentity.fingerprint(shop).equals(snapshot.metadata().get("owner")))throw new SecurityException("SOURCE_PRODUCT_NOT_OBSERVED");
                if(shop.getMarketplace()==Marketplace.WILDBERRIES&&!hasVariant(snapshot,key)){
                    if(!partial||shop.getMarketplace()!=Marketplace.WILDBERRIES)throw new SecurityException("SOURCE_PRODUCT_NOT_OBSERVED");
                    problems.add(new Problem(key,"WB_VARIANT_NOT_FOUND",List.of("chrtID")));continue;
                }
                retained+=(long)ProductJson.GSON.toJson(snapshot).length()*2;
                if(retained>128L*1024*1024)throw new IllegalArgumentException("REGISTRATION_REVIEW_TOO_LARGE");
                repository.saveVerifiedSnapshot(shop,snapshot);snapshots.put(key.cardId(),snapshot);
            }
            if(!snapshot.key().equals(key)){
                if(!hasVariant(snapshot,key)){
                    if(!partial||shop.getMarketplace()!=Marketplace.WILDBERRIES)throw new SecurityException("SOURCE_PRODUCT_NOT_OBSERVED");
                    problems.add(new Problem(key,"WB_VARIANT_NOT_FOUND",List.of("chrtID")));continue;
                }
                snapshot=new ProductSnapshot(key,snapshot.revision(),snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),snapshot.attributes(),snapshot.variants(),snapshot.dimensions(),snapshot.documents(),snapshot.checkedAt(),snapshot.metadata());
                repository.saveSnapshot(snapshot);
            }
            retained+=512L+(long)(key.cardId().length()+key.variantId().length())*2;if(retained>128L*1024*1024)throw new IllegalArgumentException("REGISTRATION_REVIEW_TOO_LARGE");results.add(snapshot);
        }
        }
        ListingIdentity.require(shop);
        if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("ZNACK_PREFLIGHT_CANCELLED");
        return new Result(results,problems);
    }
    private static boolean hasVariant(ProductSnapshot snapshot,ProductKey key){return snapshot.variants().stream().anyMatch(variant->key.variantId().equals(variant.id())||key.marketplace()!=Marketplace.WILDBERRIES&&key.variantId().equals(variant.sellerArticle()));}
}
