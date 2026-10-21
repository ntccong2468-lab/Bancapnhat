package com.vncode.app.features.kiz;

import com.vncode.app.features.fbo.*;
import com.vncode.app.models.Shop;
import com.vncode.app.integration.znack.GtinNormalizer;
import java.util.*;

/** A broad legacy category/article rule can select stock, but cannot authorize a new paid purchase. */
public final class KizPurchaseMappingProof {
    public record Product(String nativeId,String sku,String gtin){}
    public static void require(Shop shop,List<Product> products,Set<String> toBuy){
        var requested=new HashSet<String>();for(var gtin:toBuy)requested.add(GtinNormalizer.normalize(gtin));
        var selected=products.stream().filter(p->p.gtin()!=null&&requested.contains(GtinNormalizer.normalize(p.gtin()))).toList();
        var references=new LinkedHashMap<Product,FboProductSku>();
        for(var product:selected){
            if(product.nativeId()==null||product.nativeId().isBlank()||product.sku()==null||product.sku().isBlank())throw missing();
            // These are native lookup keys only. The repository must obtain physical
            // article/color/size and scoped Catalog proof from the owned cached records.
            references.put(product,new FboProductSku(1,"","","","","","","",product.sku(),"",true,product.sku(),"","",product.nativeId()));
        }
        var verified=new FboVerifiedGtinRepository().resolve(shop.getId(),shop.getMarketplace(),List.copyOf(references.values()));
        var covered=new HashSet<String>();
        references.forEach((product,reference)->{String gtin=verified.get(reference);if(gtin==null||!GtinNormalizer.normalize(gtin).equals(GtinNormalizer.normalize(product.gtin())))throw missing();covered.add(GtinNormalizer.normalize(gtin));});
        if(!covered.containsAll(requested))throw missing();
    }
    private static KizAssignmentException missing(){return new KizAssignmentException("KIZ_GTIN_MAPPING_MISSING","","");}
}
