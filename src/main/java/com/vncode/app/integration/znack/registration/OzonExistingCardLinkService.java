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
        var rows=NationalCatalogGtinSource.forSelection(shop,Set.of(gtin),false).read(shop.getId());
        if(rows.size()!=1)throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");RegisteredGtin card=rows.getFirst();
        if(!scope.source().equals(card.source())||!RegisteredGtinProof.sameCode(gtin,card.gtin())||!RegisteredGtinProof.currentObservation(card,java.time.Instant.now()))throw new IllegalStateException("unverified_catalog_gtin");
        long id;try{id=Long.parseLong(card.details().single("@catalog_id"));if(id<=0)throw new NumberFormatException();}catch(RuntimeException invalid){throw new IllegalStateException("CATALOG_IDENTITY_CHANGED",invalid);}
        var observed=session.catalog().observedCardStatus(session.token(),gtin,id);
        if(observed==null||observed.goodId()!=id||!observed.tradeUnit()||observed.progress()==null||!observed.progress().signed()||!"published".equalsIgnoreCase(observed.rawStatus())||!RegisteredGtinProof.sameCode(observed.gtin(),gtin))throw new IllegalStateException("CATALOG_PUBLICATION_PENDING");
        if(!card.article().equals(observed.details().single("article"))||!card.color().equals(observed.details().single("color"))
                ||!card.size().equals(observed.details().single("size"))||!card.details().values("tnved").equals(observed.details().values("tnved")))throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");
        Runnable guard=()->{session.scope().assertCurrent(shop);ListingIdentity.require(shop);};
        if(!session.catalog().verifiedPublishedCardOwner(session.token(),gtin,id,scope.inn(),guard))throw new IllegalStateException("CATALOG_OWNER_UNCONFIRMED");
        var target=new com.vncode.app.features.products.ProductKey(shop.getId(),Marketplace.OZON,key.productId(),key.variantId());
        var product=new OzonShopBrand().apply(shop,new OzonProductSnapshotReader().readForRegistrationProof(shop,target));
        String sku=product.metadata().getOrDefault("sku","");var variants=product.variants().stream().filter(v->key.variantId().equals(v.id())).toList();
        if(variants.size()!=1||!sku.matches("[1-9][0-9]{0,18}")||new java.math.BigInteger(sku).compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE))>0)throw new IllegalStateException("GTIN_VARIANT_IDENTITY_UNVERIFIED");
        var variant=variants.getFirst();String size=variant.size().isBlank()?variant.russianSize():variant.size();
        if(!OzonSkuGtinMatcher.sameColor(variant.color(),card.color())||!GtinVariantIdentity.sizeMatches(card,size,variant.russianSize()))throw new IllegalStateException("GTIN_VARIANT_IDENTITY_UNVERIFIED");
        var codes=product.attributes().stream().filter(a->ProductFieldNames.matches("tnved",a.name())).flatMap(a->a.values().stream()).map(ProductFieldNames::tnvedCode).filter(v->!v.isBlank()).distinct().toList();
        var catalogCodes=card.details().values("tnved").stream().map(ProductFieldNames::tnvedCode).filter(v->!v.isBlank()).distinct().toList();
        if(codes.size()!=1||catalogCodes.size()!=1||!codes.getFirst().matches("[0-9]{10}")||!codes.getFirst().equals(catalogCodes.getFirst()))throw new IllegalStateException("GTIN_TNVED_GROUP_CHANGED");
        guard.run();String revision=RegistrationReviewedValues.productRevision(product);
        String catalogRevision=ListingIdentity.hash(new com.google.gson.Gson().toJson(card.details())+"\n"+card.article()+"\n"+card.color()+"\n"+card.size()+"\n"+id);
        // Keep a minimal KIZ identity observation and its scoped Catalog readback;
        // do not replace the seller's product snapshot or certificates/documents.
        com.vncode.app.features.kiz.KizCrossMarketplaceProof.cacheObservedOzonSnapshot(shop,product,card,id);guard.run();
        return new Review(key,card.gtin(),id,sku,product.title(),product.vendorArticle(),variant.color(),size,card.article(),card.color(),card.size(),codes.getFirst(),owner,scope.source(),revision,catalogRevision);
    }
    /** Called only after seller explicitly reviews this exact pair; it does not post to Ozon. */
    public void approve(Shop shop,Review reviewed)throws Exception {
        Objects.requireNonNull(reviewed);Review current=read(shop,reviewed.key(),reviewed.gtin());
        if(!reviewed.equals(current))throw new IllegalStateException("GTIN_REVIEW_CHANGED");
        OzonReviewedGtinLinks.save(shop,reviewed.key(),new OzonReviewedGtinLinks.Link(reviewed.gtin(),reviewed.goodId(),reviewed.sku(),reviewed.owner(),reviewed.source(),reviewed.revision()));
    }
}
