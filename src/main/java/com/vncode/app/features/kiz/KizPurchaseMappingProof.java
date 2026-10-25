package com.vncode.app.features.kiz;

import com.vncode.app.features.fbo.*;
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
        var resolved=resolve(shop,selected);var covered=new HashSet<String>();
        for(var product:selected){String gtin=resolved.get(product);if(gtin==null||!gtin.equals(GtinNormalizer.normalize(product.gtin())))throw missing(product);covered.add(gtin);}
        if(!covered.containsAll(requested))throw missing();
    }
    /** Resolve exact native variants for printing, including GTIN barcodes entered outside VNCode.
     * A legacy category rule is deliberately not evidence for this view. */
    public static Map<Product,String> resolve(Shop shop,List<Product> selected){
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
        references.forEach((product,reference)->{String gtin=verified.get(reference);if(gtin!=null)resolved.put(product,GtinNormalizer.normalize(gtin));});
        return Map.copyOf(resolved);
    }
    private static KizAssignmentException missing(){return new KizAssignmentException("KIZ_GTIN_MAPPING_MISSING","","");}
    private static KizAssignmentException missing(Product product){return new KizAssignmentException("KIZ_GTIN_MAPPING_MISSING",product.order(),Objects.toString(product.nativeId(),"")+"-"+Objects.toString(product.sku(),""));}
}
