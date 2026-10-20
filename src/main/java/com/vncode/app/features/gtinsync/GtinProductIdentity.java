package com.vncode.app.features.gtinsync;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import java.util.*;

/** GTIN-only consent freezes product/variant attributes, not the barcode readback itself. */
public final class GtinProductIdentity {
    private GtinProductIdentity(){}
    public static String fingerprint(ProductSnapshot product){
        var fields=new LinkedHashMap<String,Object>();fields.put("card",product.key().cardId());fields.put("title",product.title());fields.put("brand",product.brand());fields.put("article",product.vendorArticle());fields.put("category",product.categoryId());fields.put("attributes",product.attributes());fields.put("dimensions",product.dimensions());fields.put("sku",product.metadata().getOrDefault("sku",""));
        fields.put("variants",product.variants().stream().map(v->List.of(Objects.toString(v.id(),""),Objects.toString(v.sellerArticle(),""),Objects.toString(v.color(),""),Objects.toString(v.size(),""),Objects.toString(v.russianSize(),""))).toList());
        return ListingIdentity.hash(ListingIdentity.canonical(ProductJson.GSON.toJsonTree(fields)));
    }
}
