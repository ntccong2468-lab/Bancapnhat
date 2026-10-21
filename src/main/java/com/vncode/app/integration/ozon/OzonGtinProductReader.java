package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.integration.marketplace.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.util.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;

public final class OzonGtinProductReader {
    private final Shop shop;private final OzonApiClient api;
    public OzonGtinProductReader(Shop shop,OzonApiClient api){MarketplaceGuard.requireOzon(shop);this.shop=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());this.api=Objects.requireNonNull(api);}
    public List<ProductSnapshot> list() throws IOException {
        requireOwner();var result=new ArrayList<ProductSnapshot>();var seen=new HashSet<String>();
        for(String visibility:List.of("ALL","ARCHIVED")){String cursor="";var cursors=new HashSet<String>();long count=0,expectedTotal=-1;boolean complete=false;
        for(int page=0;page<20_000;page++) {
            var response=api.listProducts(cursor,500,visibility);requireOwner();
            if(!response.has("result")||!response.get("result").isJsonObject())throw new IOException("incomplete_ozon_catalog");
            var raw=response.getAsJsonObject("result");var items=array(raw,"items");long total=declaredTotal(raw);if(expectedTotal<0)expectedTotal=total;else if(total!=expectedTotal)throw new IOException("incomplete_ozon_catalog");var requested=new LinkedHashMap<String,String>();
            for(var value:items){if(!value.isJsonObject())throw new IOException("invalid_ozon_catalog");var item=value.getAsJsonObject();String id=text(item,"product_id"),offer=text(item,"offer_id");if(!numeric(id)||offer.isBlank()||!seen.add(id)||requested.put(id,offer)!=null)throw new IOException("invalid_ozon_catalog");}if(count+items.size()>total)throw new IOException("incomplete_ozon_catalog");
            if(items.isEmpty()){if(total>count)throw new IOException("incomplete_ozon_catalog");complete=true;break;}
            var detailed=products(api.productInfo(List.copyOf(requested.keySet())),requested);requireOwner();
            for(var p:detailed)result.add(fromProduct(new ProductKey(shop.getId(),Marketplace.OZON,p.productId(),p.offerId()),p));
            count+=items.size();if(items.size()<500&&total<=count){complete=true;break;}
            String next=text(raw,"last_id");if(next.isBlank()||next.equals(cursor)||!cursors.add(next))throw new IOException("invalid_ozon_cursor");cursor=next;
        }
        if(!complete)throw new IOException("ozon_page_limit");}
        return List.copyOf(result);
    }
    public ProductSnapshot read(ProductKey key) throws IOException {
        if(key.shopId()!=shop.getId()||key.marketplace()!=Marketplace.OZON||!numeric(key.productId())||key.variantId().isBlank())throw new IllegalArgumentException("shop_mismatch");
        requireOwner();var products=products(api.productInfo(List.of(key.productId())),Map.of(key.productId(),key.variantId()));requireOwner();
        var p=products.getFirst();
        return fromProduct(key,p);
    }
    public static ProductSnapshot fromProduct(ProductKey key,OzonProductDto product) {
        if(key.marketplace()!=Marketplace.OZON || !key.productId().equals(product.productId()) || !key.variantId().equals(product.offerId()))throw new IllegalArgumentException("product_mismatch");
        return ProductFingerprints.snapshot(key,product.article(),product.color(),product.size(),product.barcodes());
    }
    private static List<OzonProductDto> products(JsonObject response,Map<String,String> requested)throws IOException{
        var raw=array(response,"items");var seen=new HashSet<String>();
        for(var value:raw){if(!value.isJsonObject())throw new IOException("invalid_ozon_catalog");var item=value.getAsJsonObject();String id=text(item,"id"),offer=text(item,"offer_id");
            if(!requested.containsKey(id)||!seen.add(id)||!requested.get(id).equals(offer)||!numeric(text(item,"sku")))throw new IOException("foreign_ozon_product");
            var codes=array(item,"barcodes");if(codes.size()>100)throw new IOException("invalid_ozon_barcode");
            for(var code:codes)if(!code.isJsonPrimitive()||!code.getAsJsonPrimitive().isString()||code.getAsString().isBlank()||!code.getAsString().equals(code.getAsString().strip())||code.getAsString().length()>512)throw new IOException("invalid_ozon_barcode");
        }
        if(!seen.equals(requested.keySet()))throw new IOException("incomplete_ozon_catalog");
        try{var result=OzonJson.parseProductInfo(response);if(result.size()!=raw.size())throw new IOException("incomplete_ozon_catalog");return result;}
        catch(IllegalArgumentException invalid){throw new IOException("invalid_ozon_catalog",invalid);}
    }
    private static JsonArray array(JsonObject object,String name)throws IOException{if(object==null||!object.has(name)||!object.get(name).isJsonArray())throw new IOException("incomplete_ozon_catalog");return object.getAsJsonArray(name);}
    private void requireOwner(){com.vncode.app.integration.listing.ListingIdentity.require(shop);api.requireSyncOwner(shop.getId());}
    private static String text(JsonObject object,String name){var value=object.get(name);return value!=null&&value.isJsonPrimitive()&&!value.getAsJsonPrimitive().isBoolean()?value.getAsString():"";}
    private static boolean numeric(String value){return value.matches("[1-9][0-9]{0,18}")&&new java.math.BigInteger(value).compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE))<=0;}
    static long declaredTotal(JsonObject result)throws IOException{if(!result.has("total_items")&&!result.has("total"))throw new IOException("incomplete_ozon_catalog");Long total=null;for(String field:List.of("total_items","total")){if(!result.has(field))continue;try{var raw=result.get(field);if(!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException();long value=raw.getAsBigDecimal().longValueExact();if(value<0||total!=null&&total!=value)throw new IllegalArgumentException();total=value;}catch(RuntimeException invalid){throw new IOException("invalid_ozon_catalog",invalid);}}return total;}
}
