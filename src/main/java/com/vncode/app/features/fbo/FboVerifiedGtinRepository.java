package com.vncode.app.features.fbo;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.gtinsync.OzonSkuGtinMatcher;
import com.vncode.app.features.products.ProductRepository;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.GtinNormalizer;
import com.google.gson.JsonParser;
import com.vncode.app.features.products.ProductJson;
import java.time.Instant;
import java.sql.SQLException;
import java.util.*;

/** A GTIN must be published in the Catalog and present on the exact marketplace variant. */
public final class FboVerifiedGtinRepository {
    private static final class Candidates { final Set<String> gtins=new LinkedHashSet<>(),active=new LinkedHashSet<>(); }
    public Map<FboProductSku,String> resolve(int shopId,Marketplace market,List<FboProductSku> products) {
        if(products==null||products.size()>10000)throw new IllegalArgumentException("Invalid FBO selection");
        var result=new LinkedHashMap<FboProductSku,String>();
        var shop=new com.vncode.app.features.shop.ShopRepository().findById(shopId);
        if(shop==null||shop.getMarketplace()!=market)throw new IllegalArgumentException("FBO_SHOP_CHANGED");
        ListingIdentity.require(shop);
        RegisteredGtinProof.Scope scope;
        try{scope=RegisteredGtinProof.current(shop);RegisteredGtinProof.requireProduction(scope);}
        catch(RegisteredGtinProof.UnverifiedOwnerException|IllegalArgumentException unknown){return Map.of();}
        var now=Instant.now();
        var requested=products.stream().map(p->market==Marketplace.OZON?p.catalogSku():p.sku()).distinct().toList();
        var found=new HashMap<String,Map<String,Candidates>>();
        try(var c=Database.getConnection()) {
            c.setAutoCommit(false);ProductRepository.requireOwner(c,shopId,market);String owner=com.vncode.app.integration.listing.ListingIdentity.fingerprint(c,shopId,market);
            for(int start=0;start<requested.size();start+=750) {
                var batch=requested.subList(start,Math.min(start+750,requested.size()));
                String placeholders=String.join(",",Collections.nCopies(batch.size(),"?"));
                String body=market==Marketplace.OZON?"""
                    SELECT p.sku requested_sku,p.product_id card_id,p.offer_id variant_id,b.barcode,
                      COALESCE(NULLIF(p.article,''),p.offer_id) article,p.color color_value,p.size size_value,
                      p.russian_size russian_size,p.name title,p.category category,'' brand
                    FROM ozon_products p JOIN ozon_product_barcodes b ON b.shop_id=p.shop_id AND b.product_id=p.product_id
                    WHERE p.shop_id=? AND p.archived=0 AND p.sku IN (
                    """:"""
                    SELECT selected.sku requested_sku,CAST(s.nm_id AS TEXT) card_id,CAST(s.chrt_id AS TEXT) variant_id,b.sku barcode,
                      c.vendor_code article,(SELECT json_group_array(json_object('name',ch.name,'value',ch.value_json)) FROM wb_product_characteristics ch
                        WHERE ch.shop_id=c.shop_id AND ch.nm_id=c.nm_id
                        ) color_value,
                      s.tech_size size_value,s.wb_size russian_size,c.title title,c.subject_name category,c.brand brand
                    FROM wb_product_sizes s JOIN wb_product_size_skus selected ON selected.shop_id=s.shop_id AND selected.chrt_id=s.chrt_id
                    JOIN wb_product_cards c ON c.shop_id=s.shop_id AND c.nm_id=s.nm_id
                    JOIN wb_product_size_skus b ON b.shop_id=s.shop_id AND b.chrt_id=s.chrt_id
                    WHERE s.shop_id=? AND selected.sku IN (
                    """;
                String sql="SELECT b.*,g.gtin,g.data_json,m.gtin active_gtin FROM ("+body+placeholders+")) b "+"""
                    JOIN gtin_sync_catalog g ON g.shop_id=? AND g.marketplace=?
                      AND g.gtin IN (b.barcode,substr('00000000000000'||b.barcode,-14),substr('00000000000000'||b.barcode,-13),substr('00000000000000'||b.barcode,-12),substr('00000000000000'||b.barcode,-8)) AND length(b.barcode) IN (8,12,13,14) AND b.barcode NOT GLOB '*[^0-9]*' AND ltrim(g.gtin,'0')=ltrim(b.barcode,'0')
                      AND json_extract(g.data_json,'$.owner')=?
                      AND json_extract(g.data_json,'$.published')=1 AND json_extract(g.data_json,'$.tradeUnit')=1
                    LEFT JOIN gtin_sync_mappings m ON m.shop_id=g.shop_id AND m.marketplace=g.marketplace
                      AND m.product_id=b.card_id AND m.variant_id=b.variant_id AND m.gtin=g.gtin
                    """;
                try(var p=c.prepareStatement(sql)) {
                    int index=1;p.setInt(index++,shopId);for(var sku:batch)p.setString(index++,sku);
                    p.setInt(index++,shopId);p.setString(index++,market.name());p.setString(index,owner);
                    try(var r=p.executeQuery()){while(r.next()) {
                        String gtin=r.getString("gtin");if(!RegisteredGtinValidator.isValid(gtin)||GtinNormalizer.isTechnicalRange(gtin))continue;
                        if(!verified(r.getString("data_json"),gtin,owner,scope.source(),now,market,
                                r.getString("variant_id"),r.getString("article"),r.getString("color_value"),r.getString("size_value"),r.getString("russian_size"),r.getString("title"),r.getString("category"),r.getString("brand")))continue;
                        gtin=GtinNormalizer.normalize(gtin);
                        String key=r.getString("requested_sku")+"|"+r.getString("card_id");
                        var entry=found.computeIfAbsent(key,k->new HashMap<>()).computeIfAbsent(r.getString("variant_id"),k->new Candidates());
                        entry.gtins.add(gtin);if(r.getString("active_gtin")!=null)entry.active.add(gtin);
                    }}
                }
            }
            for(var product:products) {
                String sku=market==Marketplace.OZON?product.catalogSku():product.sku();
                var variants=found.getOrDefault(sku+"|"+product.productId(),Map.of());
                Candidates entry=product.variantId().isBlank()?(variants.size()==1?variants.values().iterator().next():null):variants.get(product.variantId());
                if(entry!=null&&(entry.active.size()==1||entry.active.isEmpty()&&entry.gtins.size()==1))result.put(product,entry.active.size()==1?entry.active.iterator().next():entry.gtins.iterator().next());
            }
            ListingIdentity.require(shop);
            if(!scope.source().equals(RegisteredGtinProof.current(shop).source()))return Map.of();
            c.commit();return Map.copyOf(result);
        }catch(SQLException failure){throw new IllegalStateException("Unable to verify FBO GTIN associations",failure);}
    }
    private static boolean verified(String json,String gtin,String owner,String source,Instant now,Marketplace market,
            String variant,String article,String color,String size,String russianSize,String title,String category,String brand){
        try{
            var data=JsonParser.parseString(json).getAsJsonObject();
            if(!data.has("owner")||!owner.equals(data.get("owner").getAsString()))return false;
            var row=ProductJson.GSON.fromJson(data,RegisteredGtin.class);
            if(!source.equals(row.source())||!RegisteredGtinProof.sameCode(gtin,row.gtin())||!RegisteredGtinProof.currentObservation(row,now))return false;
            String actualColor=market==Marketplace.WILDBERRIES?singleColor(color):safe(color);
            if(ListingIdentity.compatibleVariant(row,safe(article),actualColor,safe(size),safe(russianSize)))return true;
            return market==Marketplace.OZON&&new OzonSkuGtinMatcher(List.of(row)).candidates(safe(variant),safe(article),actualColor,safe(size),safe(russianSize),safe(title),safe(category),safe(brand)).size()==1;
        }catch(RuntimeException malformed){return false;}
    }
    private static String singleColor(String value){
        if(value==null||value.isBlank())return "";
        var colors=new LinkedHashSet<String>();var attributes=JsonParser.parseString(value);
        if(!attributes.isJsonArray())return "";
        for(var raw:attributes.getAsJsonArray()){
            if(!raw.isJsonObject())return "";var attribute=raw.getAsJsonObject();
            if(!attribute.has("name")||attribute.get("name").isJsonNull()||!com.vncode.app.features.products.ProductFieldNames.matches("color",attribute.get("name").getAsString()))continue;
            if(!attribute.has("value")||attribute.get("value").isJsonNull()||!colors(JsonParser.parseString(attribute.get("value").getAsString()),colors))return "";
        }
        return colors.size()==1?colors.iterator().next():"";
    }
    private static boolean colors(com.google.gson.JsonElement value,Set<String> colors){
        if(value.isJsonArray()){for(var color:value.getAsJsonArray())if(!colors(color,colors))return false;return true;}
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())return false;
        if(!value.getAsString().isBlank())colors.add(value.getAsString().strip());return true;
    }
    private static String safe(String value){return Objects.toString(value,"");}
}
