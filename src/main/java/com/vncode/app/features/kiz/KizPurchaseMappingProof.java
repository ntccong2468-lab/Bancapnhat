package com.vncode.app.features.kiz;

import com.vncode.app.features.fbo.*;
import com.vncode.app.features.catalogsync.CatalogPermitEvidence;
import com.vncode.app.features.gtinsync.GtinSyncRepository;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.models.Shop;
import com.vncode.app.integration.znack.GtinNormalizer;
import java.util.*;

/** A broad legacy category/article rule can select stock, but cannot authorize a new paid purchase. */
public final class KizPurchaseMappingProof {
    public record Product(String nativeId,String sku,String gtin,String order){
        public Product(String nativeId,String sku,String gtin){this(nativeId,sku,gtin,"");}
    }
    public static void require(Shop shop,List<Product> products,Set<String> toBuy){
        var requested=new HashSet<String>();for(var gtin:toBuy)requested.add(GtinNormalizer.normalize(gtin));
        var selected=products.stream().filter(p->p.gtin()!=null&&requested.contains(GtinNormalizer.normalize(p.gtin()))).toList();
        var resolved=resolve(shop,selected,true);var covered=new HashSet<String>();
        for(var product:selected){String gtin=resolved.get(product);if(gtin==null||!gtin.equals(GtinNormalizer.normalize(product.gtin())))throw missing(product);covered.add(gtin);}
        if(!covered.containsAll(requested))throw missing();
        // A reviewed cross-market link may be changed concurrently with enqueue or
        // the later asynchronous paid request. Until its evidence can be pinned
        // atomically for that whole lifecycle, it can select existing stock only.
        if(shop.getMarketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON){
            var direct=resolve(shop,selected,false);
            for(var product:selected)if(!com.vncode.app.features.gtinsync.RegisteredGtinProof.sameCode(direct.get(product),resolved.get(product)))
                throw new KizAssignmentException("KIZ_CROSS_PURCHASE_REVIEW_REQUIRED",product.order(),Objects.toString(product.nativeId(),"")+"-"+Objects.toString(product.sku(),""));
        }
        requireKnownPermit(shop,requested);
    }
    /** New paid requests must honor known permit validity; existing stock identity is independent. */
    public static void requireKnownPermit(Shop shop,Collection<String> gtins){
        String revision=com.vncode.app.integration.znack.CatalogReadRevision.current(shop.getId());
        com.vncode.app.integration.znack.CatalogReadRevision.requireComplete(shop.getId());
        var requested=new HashSet<String>();var aliases=new LinkedHashSet<String>();
        for(String gtin:gtins){
            String normalized=GtinNormalizer.normalize(gtin);requested.add(normalized);
            for(int width:new int[]{8,12,13,14}){
                String alias=normalized.substring(14-width);
                if(RegisteredGtinProof.sameCode(normalized,alias))aliases.add(alias);
            }
        }
        var observed=new GtinSyncRepository().catalogItems(shop.getId(),shop.getMarketplace(),aliases).values().stream()
                .filter(card->card.published()&&requested.contains(GtinNormalizer.normalize(card.gtin())))
                .filter(card->!card.details().documents().isEmpty()||!card.details().values("@documents_status").isEmpty()
                        ||!card.details().values("@registry_document_count").isEmpty()).toList();
        com.vncode.app.integration.znack.CatalogReadRevision.requireUnchanged(shop.getId(),revision);
        if(observed.isEmpty())return; // Legacy and accepted, unpublished registrations keep their existing prerequisites.
        String source=RegisteredGtinProof.current(shop).source();
        for(var card:observed)if(source.equals(card.source())){
            String problem=CatalogPermitEvidence.problem(card.details());
            if(!problem.isBlank())throw new IllegalStateException(problem);
        }
        com.vncode.app.integration.znack.CatalogReadRevision.requireUnchanged(shop.getId(),revision);
    }
    /** Resolve exact native variants for printing, including GTIN barcodes entered outside VNCode.
     * A legacy category rule is deliberately not evidence for this view. */
    public static Map<Product,String> resolve(Shop shop,List<Product> selected){
        // The live link can change between a planner snapshot and stock reservation.
        // Do not expose cross-only identity to either reservation or printing until
        // link evidence is pinned and revalidated atomically in those transactions.
        return resolve(shop,selected,false);
    }
    private static Map<Product,String> resolve(Shop shop,List<Product> selected,boolean allowCross){
        var pending=new KizRegistrationIdentityRepository().readForPurchase(shop);
        var resolved=new LinkedHashMap<Product,String>();
        var references=new LinkedHashMap<Product,FboProductSku>();
        for(var product:selected){
            if(product.nativeId()==null||product.nativeId().isBlank()||product.sku()==null||product.sku().isBlank())throw missing(product);
            KizRegistrationIdentityRepository.Identity identity;
            try{identity=pending.select(product.nativeId(),product.sku());}
            catch(KizAssignmentException incomplete){throw missing(product);}
            if(identity!=null){
                resolved.put(product,identity.gtin());continue;
            }
            // These are native lookup keys only. The repository must obtain physical
            // article/color/size and scoped Catalog proof from the owned cached records.
            references.put(product,new FboProductSku(1,"","","","","","","",product.sku(),"",true,product.sku(),"","",product.nativeId()));
        }
        var verified=references.isEmpty()?Map.<FboProductSku,String>of():new FboVerifiedGtinRepository().resolve(shop.getId(),shop.getMarketplace(),references.values().stream().distinct().toList());
        var links=allowCross&&shop.getMarketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON&&!references.isEmpty()
                ?KizCrossMarketplaceProof.resolve(shop,references.keySet().stream().map(Product::sku).distinct().toList())
                :Map.<KizCrossMarketplaceProof.Variant,String>of();
        references.forEach((product,reference)->{
            String direct=verified.get(reference),cross=links.get(new KizCrossMarketplaceProof.Variant(product.nativeId(),product.sku()));
            if(direct!=null&&cross!=null&&!com.vncode.app.features.gtinsync.RegisteredGtinProof.sameCode(direct,cross))throw missing(product);
            String gtin=direct!=null?direct:cross;if(gtin!=null)resolved.put(product,GtinNormalizer.normalize(gtin));
        });
        var identityByVariant=new HashMap<KizCrossMarketplaceProof.Variant,String>();
        resolved.forEach((product,gtin)->identityByVariant.put(new KizCrossMarketplaceProof.Variant(product.nativeId(),product.sku()),gtin));
        var conflicts=KizCrossMarketplaceProof.conflictsWithReviewedLinks(shop,identityByVariant);
        for(var product:resolved.keySet())if(conflicts.contains(new KizCrossMarketplaceProof.Variant(product.nativeId(),product.sku())))throw missing(product);
        return Map.copyOf(resolved);
    }
    private static KizAssignmentException missing(){return new KizAssignmentException("KIZ_GTIN_MAPPING_MISSING","","");}
    private static KizAssignmentException missing(Product product){return new KizAssignmentException("KIZ_GTIN_MAPPING_MISSING",product.order(),Objects.toString(product.nativeId(),"")+"-"+Objects.toString(product.sku(),""));}
}
