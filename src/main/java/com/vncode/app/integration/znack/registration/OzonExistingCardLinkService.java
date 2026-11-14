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

/** Link an existing published Catalog card to one native Ozon SKU by review or unique model/size. */
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
        return read(shop,key,gtin,false);
    }
    /** Refresh a machine-verified exact model/size pair without claiming seller approval. */
    public Review readExact(Shop shop,ProductKey key,String gtin)throws Exception {
        return read(shop,key,gtin,true);
    }
    private Review read(Shop shop,ProductKey key,String gtin,boolean exact)throws Exception {
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
        var snapshots=new OzonProductSnapshotReader();
        var product=new OzonShopBrand().apply(shop,exact?snapshots.readForGtinSync(shop,target):snapshots.readForRegistrationProof(shop,target));
        String sku=product.metadata().getOrDefault("sku","");var variants=product.variants().stream().filter(v->key.variantId().equals(v.id())).toList();
        if(variants.size()!=1||!sku.matches("[1-9][0-9]{0,18}")||new java.math.BigInteger(sku).compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE))>0)throw new IllegalStateException("GTIN_VARIANT_IDENTITY_UNVERIFIED");
        var variant=variants.getFirst();String size=variant.size().isBlank()?variant.russianSize():variant.size();
        if(exact?!OzonExactVariantIdentity.sameIdentity(product,card):!OzonSkuGtinMatcher.sameColor(variant.color(),card.color())||!GtinVariantIdentity.sizeMatches(card,size,variant.russianSize()))throw new IllegalStateException("GTIN_VARIANT_IDENTITY_UNVERIFIED");
        var catalogCodes=card.details().values("tnved").stream().map(ProductFieldNames::tnvedCode).filter(v->!v.isBlank()).distinct().toList();
        // Seller reviews the same physical variant even when marketplace tariff metadata differs.
        // Keep the Catalog's own full tariff code for purchases; do not rewrite Ozon here.
        if(!exact&&(catalogCodes.size()!=1||!catalogCodes.getFirst().matches("[0-9]{10}")))throw new IllegalStateException("FULL_TNVED_REQUIRED");
        guard.run();String revision=RegistrationReviewedValues.productRevision(product);
        String catalogRevision=ListingIdentity.hash(new com.google.gson.Gson().toJson(card.details())+"\n"+card.article()+"\n"+card.color()+"\n"+card.size()+"\n"+id);
        // Keep a minimal KIZ identity observation and its scoped Catalog readback;
        // do not replace the seller's product snapshot or certificates/documents.
        com.vncode.app.features.kiz.KizCrossMarketplaceProof.cacheObservedOzonSnapshot(shop,product,card,id);guard.run();
        if(exact)OzonReviewedVariantProof.observeExact(shop,product,card);else OzonReviewedVariantProof.observe(shop,product,card);guard.run();
        new GtinSyncRepository().mergeCatalog(shop.getId(),Marketplace.OZON,List.of(card));
        return new Review(key,card.gtin(),id,sku,product.title(),product.vendorArticle(),variant.color(),size,card.article(),card.color(),card.size(),catalogCodes.size()==1?catalogCodes.getFirst():"",owner,scope.source(),revision,catalogRevision);
    }
    /** Explicit local mapping/apply action only. No seller write, purchase or barcode-readback claim.
     * The complete owned Catalog cache participates, so passing a selected singleton cannot hide ambiguity. */
    public OzonReviewedGtinLinks.Link bindExactUnique(Shop shop,com.vncode.app.features.products.ProductSnapshot product,List<RegisteredGtin> candidates){
        if(shop==null||shop.getMarketplace()!=Marketplace.OZON||product==null||product.key().shopId()!=shop.getId()||product.key().marketplace()!=Marketplace.OZON)throw new SecurityException("shop_mismatch");
        ListingIdentity.require(shop);com.vncode.app.integration.znack.CatalogReadRevision.requireComplete(shop.getId());
        String source=RegisteredGtinProof.source(shop);var all=new ArrayList<>(new GtinSyncRepository().catalog(shop.getId(),Marketplace.OZON));all.addAll(candidates);
        var matches=new LinkedHashMap<String,RegisteredGtin>();
        for(var card:all){
            if(!source.equals(card.source())||!OzonExactVariantIdentity.sameIdentity(product,card))continue;
            if(!RegisteredGtinValidator.isValid(card.gtin()))throw new IllegalArgumentException("invalid_gtin");
            String code=RegisteredGtinProof.canonicalCode(card.gtin());var previous=matches.get(code);
            if(previous!=null&&!previous.details().single("@catalog_id").equals(card.details().single("@catalog_id")))throw new IllegalStateException("GTIN_AMBIGUOUS");
            if(previous==null||card.syncedAt().isAfter(previous.syncedAt()))matches.put(code,card);
        }
        if(matches.isEmpty())throw new IllegalStateException("GTIN_CARD_NOT_FOUND");
        if(matches.size()!=1)throw new IllegalStateException("GTIN_AMBIGUOUS");
        var card=matches.values().iterator().next();RegisteredGtinProof.requireCurrent(shop,card.gtin());
        var key=new ProductKey(shop.getId(),Marketplace.OZON,product.key().cardId(),product.key().variantId());
        try(var c=com.vncode.app.config.Database.getConnection()){
            try(var p=c.prepareStatement("SELECT offer_id,sku,archived FROM ozon_products WHERE shop_id=? AND product_id=?")){
                p.setInt(1,shop.getId());p.setString(2,key.productId());try(var r=p.executeQuery()){
                    if(!r.next()||!key.variantId().equals(r.getString(1))||!product.metadata().getOrDefault("sku","").equals(r.getString(2))||r.getBoolean(3)||r.next())throw new IllegalStateException("GTIN_VARIANT_IDENTITY_UNVERIFIED");
                }
            }
            try(var p=c.prepareStatement("SELECT 1 FROM ozon_product_barcodes WHERE shop_id=? AND product_id<>? AND length(barcode) IN (8,12,13,14) AND barcode NOT GLOB '*[^0-9]*' AND substr('00000000000000'||barcode,-14)=? LIMIT 1")){
                p.setInt(1,shop.getId());p.setString(2,key.productId());p.setString(3,RegisteredGtinProof.canonicalCode(card.gtin()));try(var r=p.executeQuery()){if(r.next())throw new IllegalStateException("GTIN_ON_ANOTHER_PRODUCT");}
            }
        }catch(java.sql.SQLException failure){throw new IllegalStateException("SYNC_STORAGE",failure);}
        var previous=OzonReviewedGtinLinks.read(shop,key);
        if(previous!=null&&(!OzonReviewedVariantProof.exactProof(previous.revision())||!RegisteredGtinProof.sameCode(previous.gtin(),card.gtin())||!previous.sku().equals(product.metadata().getOrDefault("sku",""))))throw new IllegalStateException("GTIN_REVIEW_CHANGED");
        long id;try{id=Long.parseLong(card.details().single("@catalog_id"));if(id<=0)throw new NumberFormatException();}catch(RuntimeException invalid){throw new IllegalStateException("CATALOG_IDENTITY_CHANGED",invalid);}
        try(var c=com.vncode.app.config.Database.getConnection();var p=c.prepareStatement("SELECT gtin,good_id,sku,active_gtin FROM ozon_card_registrations WHERE shop_id=? AND product_id=? AND offer_id=?")){
            p.setInt(1,shop.getId());p.setString(2,key.productId());p.setString(3,key.variantId());try(var r=p.executeQuery()){if(r.next()){
                String registered=Objects.toString(r.getString(4),"").isBlank()?Objects.toString(r.getString(1),""):r.getString(4);
                if(!registered.isBlank()&&!RegisteredGtinProof.sameCode(registered,card.gtin())||r.getLong(2)>0&&r.getLong(2)!=id||!Objects.toString(r.getString(3),"").isBlank()&&!r.getString(3).equals(product.metadata().get("sku")))throw new IllegalStateException("GTIN_REVIEW_CHANGED");
            }}
        }catch(java.sql.SQLException failure){throw new IllegalStateException("SYNC_STORAGE",failure);}
        OzonReviewedVariantProof.observeExact(shop,product,card);
        var link=new OzonReviewedGtinLinks.Link(card.gtin(),id,product.metadata().getOrDefault("sku",""),ListingIdentity.fingerprint(shop),source,OzonReviewedVariantProof.exactRevision(product,card));
        OzonReviewedGtinLinks.saveExact(shop,key,link);
        return link;
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
                p.setInt(1,shop.getId());try(var r=p.executeQuery()){while(r.next())if((skus.isEmpty()||skus.contains(r.getString(5)))&&OzonReviewedVariantProof.nativeProof(r.getString(8)))saved.put(new ProductKey(shop.getId(),Marketplace.OZON,r.getString(1),r.getString(2)),new OzonReviewedGtinLinks.Link(r.getString(3),r.getLong(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8)));}
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
                boolean exact=OzonReviewedVariantProof.exactProof(link.revision());var current=exact?readExact(shop,entry.getKey(),link.gtin()):read(shop,entry.getKey(),link.gtin());
                if(!link.owner().equals(current.owner())||!link.source().equals(current.source())||!link.sku().equals(current.sku())||link.goodId()!=current.goodId()
                        ||!link.revision().equals(exact?OzonReviewedVariantProof.exactObservedRevision(shop,current.key(),current.revision(),current.catalogRevision()):OzonReviewedVariantProof.approvedRevision(shop,current.key(),current.revision(),current.catalogRevision())))issues.put(link.sku(),"GTIN_REVIEW_CHANGED");else verified++;
            }catch(SecurityException changed){throw changed;}
            catch(Exception failure){if(failure instanceof InterruptedException){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("KIZ_REQUEST_CANCELLED");}issues.put(link.sku(),com.vncode.app.features.sync.SyncFailure.of(failure,0).code());}
        }
        return new com.vncode.app.features.kiz.KizCrossMarketplaceProof.RefreshResult(attempted,verified,issues);
    }
}
