package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.util.*;

/** Fresh facts for the whole frozen selection, without one network read per WB size. */
public final class RegistrationSnapshotReview {
    @FunctionalInterface public interface Reader{ProductSnapshot read(Shop shop,ProductKey key)throws Exception;}
    private final Reader reader;
    public RegistrationSnapshotReview(){this((shop,key)->shop.getMarketplace()==Marketplace.OZON?new com.vncode.app.integration.ozon.OzonProductSnapshotReader().readForSynchronization(shop,key):new com.vncode.app.integration.wb.WbProductSnapshotReader().readForSynchronization(shop,key));}
    public RegistrationSnapshotReview(Reader reader){this.reader=Objects.requireNonNull(reader);}
    public List<ProductSnapshot> read(Shop shop,List<ProductKey> selected)throws Exception{
        ListingIdentity.require(shop);var snapshots=new LinkedHashMap<String,ProductSnapshot>();var results=new ArrayList<ProductSnapshot>();var repository=new ProductRepository();long retained=0;
        for(var key:new LinkedHashSet<>(selected)){
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("ZNACK_PREFLIGHT_CANCELLED");ListingIdentity.require(shop,key);
            var snapshot=snapshots.get(key.cardId());
            if(snapshot==null){snapshot=reader.read(shop,key);if(snapshot==null||!snapshot.key().equals(key)||!ListingIdentity.fingerprint(shop).equals(snapshot.metadata().get("owner")))throw new SecurityException("SOURCE_PRODUCT_NOT_OBSERVED");retained+=(long)ProductJson.GSON.toJson(snapshot).length()*2;repository.saveVerifiedSnapshot(shop,snapshot);snapshots.put(key.cardId(),snapshot);}
            if(!snapshot.key().equals(key)){
                if(snapshot.variants().stream().noneMatch(variant->key.variantId().equals(variant.id())||key.variantId().equals(variant.sellerArticle())))throw new SecurityException("SOURCE_PRODUCT_NOT_OBSERVED");
                snapshot=new ProductSnapshot(key,snapshot.revision(),snapshot.title(),snapshot.description(),snapshot.brand(),snapshot.vendorArticle(),snapshot.categoryId(),snapshot.media(),snapshot.attributes(),snapshot.variants(),snapshot.dimensions(),snapshot.documents(),snapshot.checkedAt(),snapshot.metadata());
                repository.saveSnapshot(snapshot);
            }
            retained+=512L+(long)(key.cardId().length()+key.variantId().length())*2;if(retained>128L*1024*1024)throw new IllegalArgumentException("REGISTRATION_REVIEW_TOO_LARGE");results.add(snapshot);
        }
        return List.copyOf(results);
    }
}
