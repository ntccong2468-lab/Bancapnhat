package com.vncode.app.features.gtinsync;

import com.vncode.app.features.products.*;
import java.util.*;

/** Unreviewed Ozon matches retain TN VED checks; an explicitly reviewed native pair may differ. */
public final class GtinTnvedIdentity {
    private GtinTnvedIdentity(){}
    public static boolean compatible(String marketplace,List<String> registered){return compatible(List.of(marketplace==null?"":marketplace),registered);}
    private static boolean compatible(List<String> marketplace,List<String> registered){
        var target=groups(marketplace);var source=groups(registered);
        // Older observations do not contain TN VED; retain their existing identity checks.
        return target.isEmpty()||source.isEmpty()||!Collections.disjoint(target,source);
    }
    private static Set<String> groups(List<String> values){
        var out=new HashSet<String>();for(String value:values){String code=ProductFieldNames.tnvedCode(value);if(code.length()>=4)out.add(code.substring(0,4));}return out;
    }
    public static void requireCompatible(ProductSnapshot product,GtinSyncModels.RegisteredGtin source){
        if(product.key().marketplace()==com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES)return;
        if(product.revision().startsWith("live:")&&!product.metadata().getOrDefault("owner","").isBlank()&&!product.metadata().getOrDefault("sku","").isBlank()){
            var shop=new com.vncode.app.features.shop.ShopRepository().findById(product.key().shopId());
            if(shop!=null&&OzonReviewedVariantProof.matches(shop,product,source))return;
        }
        var target=product.attributes().stream().filter(a->ProductFieldNames.matches("tnved",a.name())&&(a.complexId()==null||a.complexId().isBlank()||a.complexId().equals("0"))).flatMap(a->a.values().stream()).toList();
        if(!compatible(target,source.details().values("tnved")))throw new IllegalArgumentException("GTIN_TNVED_GROUP_CHANGED");
    }
}
