package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.ProductKey;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.products.ProductFieldNames;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonProductSnapshotReader;
import com.vncode.app.integration.ozon.OzonShopBrand;
import com.vncode.app.models.Shop;
import java.util.*;

/** Read and explicitly approve one existing published Catalog card for one Ozon SKU. */
public final class OzonExistingCardLinkService {
    @FunctionalInterface interface ReviewReader { Review read(Shop shop,ProductKey key,String gtin)throws Exception; }
    private final ReviewReader reader;
    public OzonExistingCardLinkService(){this.reader=null;}
    OzonExistingCardLinkService(ReviewReader reader){this.reader=Objects.requireNonNull(reader);}
    /** The review is constructed only after current Catalog and Ozon observations succeed. */
    public record Review(ProductKey key,String gtin,long goodId,String sku,String ozonTitle,String ozonArticle,
                         String ozonColor,String ozonSize,String catalogArticle,String catalogColor,String catalogSize,
                         String tnved,String owner,String source,String revision,String catalogRevision) {}

    public Review read(Shop shop,ProductKey key,String gtin)throws Exception {
        if(reader!=null)return reader.read(shop,key,gtin);
        if(shop==null||key.shopId()!=shop.getId()||key.marketplace()!=Marketplace.OZON||shop.getMarketplace()!=Marketplace.OZON)throw new SecurityException("shop_mismatch");
        ListingIdentity.require(shop);if(!RegisteredGtinValidator.isValid(gtin))throw new IllegalArgumentException("invalid_gtin");
        var scope=RegisteredGtinProof.current(shop);RegisteredGtinProof.requireProduction(scope);String owner=ListingIdentity.fingerprint(shop);
        var session=ZnackCardRegistrationWorkflow.productionCatalog(shop);session.scope().assertCurrent(shop);
        var rows=NationalCatalogGtinSource.forSelection(shop,Set.of(gtin),true).read(shop.getId());
        if(rows.size()!=1)throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");RegisteredGtin card=rows.getFirst();
        if(!scope.source().equals(card.source())||!RegisteredGtinProof.sameCode(gtin,card.gtin())||!RegisteredGtinProof.currentObservation(card,java.time.Instant.now()))throw new IllegalStateException("unverified_catalog_gtin");
        long id;try{id=Long.parseLong(card.details().single("@catalog_id"));if(id<=0)throw new NumberFormatException();}catch(RuntimeException invalid){throw new IllegalStateException("CATALOG_IDENTITY_CHANGED",invalid);}
        var observed=session.catalog().observedCardStatus(session.token(),gtin,id);
        if(observed==null||observed.goodId()!=id||!observed.tradeUnit()||observed.progress()==null||!observed.progress().signed()||!"published".equalsIgnoreCase(observed.rawStatus())||!RegisteredGtinProof.sameCode(observed.gtin(),gtin))throw new IllegalStateException("CATALOG_PUBLICATION_PENDING");
        if(!card.article().equals(observed.details().single("article"))||!card.color().equals(com.vncode.app.features.gtinsync.GtinColorIdentity.value(observed.details().values("color")))
                ||!card.size().equals(observed.details().single("size"))||!card.details().values("tnved").equals(observed.details().values("tnved")))throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");
        Runnable guard=()->{session.scope().assertCurrent(shop);ListingIdentity.require(shop);};
        if(!session.catalog().verifiedPublishedCardOwner(session.token(),gtin,id,scope.inn(),guard))throw new IllegalStateException("CATALOG_OWNER_UNCONFIRMED");
        var target=new com.vncode.app.features.products.ProductKey(shop.getId(),Marketplace.OZON,key.productId(),key.variantId());
        var product=new OzonShopBrand().apply(shop,new OzonProductSnapshotReader().readForRegistrationProof(shop,target));
        String sku=product.metadata().getOrDefault("sku","");var variants=product.variants().stream().filter(v->key.variantId().equals(v.id())).toList();
        if(variants.size()!=1||!sku.matches("[1-9][0-9]{0,18}")||new java.math.BigInteger(sku).compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE))>0)throw new IllegalStateException("GTIN_VARIANT_IDENTITY_UNVERIFIED");
        var variant=variants.getFirst();String size=variant.size().isBlank()?variant.russianSize():variant.size();
        if(!OzonSkuGtinMatcher.sameColor(variant.color(),card.color())||!GtinVariantIdentity.sizeMatches(card,size,variant.russianSize()))throw new IllegalStateException("GTIN_VARIANT_IDENTITY_UNVERIFIED");
        var catalogCodes=card.details().values("tnved").stream().map(ProductFieldNames::tnvedCode).filter(v->!v.isBlank()).distinct().toList();
        // Seller reviews the same physical variant even when marketplace tariff metadata differs.
        // Keep the Catalog's own full tariff code for purchases; do not rewrite Ozon here.
        if(catalogCodes.size()!=1||!catalogCodes.getFirst().matches("[0-9]{10}"))throw new IllegalStateException("FULL_TNVED_REQUIRED");
        guard.run();String revision=RegistrationReviewedValues.productRevision(product);
        String catalogRevision=ListingIdentity.hash(new com.google.gson.Gson().toJson(card.details())+"\n"+card.article()+"\n"+card.color()+"\n"+card.size()+"\n"+id);
        // Keep a minimal KIZ identity observation and its scoped Catalog readback;
        // do not replace the seller's product snapshot or certificates/documents.
        com.vncode.app.features.kiz.KizCrossMarketplaceProof.cacheObservedOzonSnapshot(shop,product,card,id);guard.run();
        OzonReviewedVariantProof.observe(shop,product,card);guard.run();
        new GtinSyncRepository().mergeCatalog(shop.getId(),Marketplace.OZON,List.of(card));
        return new Review(key,card.gtin(),id,sku,product.title(),product.vendorArticle(),variant.color(),size,card.article(),card.color(),card.size(),catalogCodes.getFirst(),owner,scope.source(),revision,catalogRevision);
    }
    /** Called only after seller explicitly reviews this exact pair; it does not post to Ozon. */
    public void approve(Shop shop,Review reviewed)throws Exception {
        approveBatch(shop,List.of(Objects.requireNonNull(reviewed)));
    }
    public void approveBatch(Shop shop,List<Review> reviewedRows)throws Exception {
        if(reviewedRows.isEmpty()||reviewedRows.size()>1000)throw new IllegalArgumentException("GTIN_SELECTION_LIMIT");
        com.vncode.app.features.shop.ShopOperationCoordinator.withActiveShop(shop.getId(),()->{
            var links=new LinkedHashMap<ProductKey,OzonReviewedGtinLinks.Link>();var codes=new HashSet<String>();
            for(var reviewed:reviewedRows){
                if(links.containsKey(reviewed.key())||!codes.add(RegisteredGtinProof.canonicalCode(reviewed.gtin())))throw new IllegalArgumentException("GTIN_MULTIPLE_OZON_VARIANTS");
                Review current=read(shop,reviewed.key(),reviewed.gtin());
                if(!reviewed.equals(current))throw new IllegalStateException("GTIN_REVIEW_CHANGED");
                String revision=reader==null?OzonReviewedVariantProof.approvedRevision(shop,reviewed.key(),reviewed.revision(),reviewed.catalogRevision()):reviewed.revision();
                links.put(reviewed.key(),new OzonReviewedGtinLinks.Link(reviewed.gtin(),reviewed.goodId(),reviewed.sku(),reviewed.owner(),reviewed.source(),revision));
            }
            OzonReviewedGtinLinks.saveBatch(shop,links);
            return null;
        });
    }
    /** Refresh only stale reviewed native variants; never approve a changed identity or write Ozon. */
    public com.vncode.app.features.kiz.KizCrossMarketplaceProof.RefreshResult refreshApproved(Shop shop,Collection<String> skus,java.util.function.BooleanSupplier cancelled)throws java.io.IOException {
        if(shop==null||shop.getMarketplace()!=Marketplace.OZON||skus==null||skus.size()>10000)throw new IllegalArgumentException("shop_mismatch");
        var saved=new LinkedHashMap<ProductKey,OzonReviewedGtinLinks.Link>();
        try(var c=com.vncode.app.config.Database.getConnection()){
            try(var p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='ozon_reviewed_gtin_links'");var r=p.executeQuery()){if(!r.next())return new com.vncode.app.features.kiz.KizCrossMarketplaceProof.RefreshResult(0,0,Map.of());}
            try(var p=c.prepareStatement("SELECT product_id,offer_id,gtin,good_id,sku,owner,source,revision FROM ozon_reviewed_gtin_links WHERE shop_id=?")){
                p.setInt(1,shop.getId());try(var r=p.executeQuery()){while(r.next())if((skus.isEmpty()||skus.contains(r.getString(5)))&&OzonReviewedVariantProof.version2(r.getString(8)))saved.put(new ProductKey(shop.getId(),Marketplace.OZON,r.getString(1),r.getString(2)),new OzonReviewedGtinLinks.Link(r.getString(3),r.getLong(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8)));}
            }
        }catch(java.sql.SQLException e){throw new java.io.IOException("SYNC_STORAGE",e);}
        var issues=new LinkedHashMap<String,String>();int attempted=0,verified=0;long start=System.nanoTime();
        for(var entry:saved.entrySet()){
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("KIZ_REQUEST_CANCELLED");
            if(!OzonReviewedVariantProof.needsRefresh(shop,entry.getKey()))continue;
            var link=entry.getValue();
            if(attempted>=10||System.nanoTime()-start>java.time.Duration.ofSeconds(10).toNanos()){issues.put(link.sku(),"KIZ_CROSS_REFRESH_DEFERRED");continue;}
            attempted++;
            try{
                var current=read(shop,entry.getKey(),link.gtin());
                if(!link.owner().equals(current.owner())||!link.source().equals(current.source())||!link.sku().equals(current.sku())||link.goodId()!=current.goodId()
                        ||!link.revision().equals(OzonReviewedVariantProof.approvedRevision(shop,current.key(),current.revision(),current.catalogRevision())))issues.put(link.sku(),"GTIN_REVIEW_CHANGED");else verified++;
            }catch(SecurityException changed){throw changed;}
            catch(Exception failure){if(failure instanceof InterruptedException){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("KIZ_REQUEST_CANCELLED");}issues.put(link.sku(),com.vncode.app.features.sync.SyncFailure.of(failure,0).code());}
        }
        return new com.vncode.app.features.kiz.KizCrossMarketplaceProof.RefreshResult(attempted,verified,issues);
    }
}
