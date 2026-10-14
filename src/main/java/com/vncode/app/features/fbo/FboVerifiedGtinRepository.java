package com.vncode.app.features.fbo;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.features.products.ProductRepository;
import com.vncode.app.integration.marketplace.Marketplace;
import java.sql.SQLException;
import java.util.*;

/** A GTIN must be published in the Catalog and present on the exact marketplace variant. */
public final class FboVerifiedGtinRepository {
    private static final class Candidates { final Set<String> gtins=new LinkedHashSet<>(); String active; }
    public Map<FboProductSku,String> resolve(int shopId,Marketplace market,List<FboProductSku> products) {
        if(products==null||products.size()>10000)throw new IllegalArgumentException("Invalid FBO selection");
        var result=new LinkedHashMap<FboProductSku,String>();
        var requested=products.stream().map(p->market==Marketplace.OZON?p.catalogSku():p.sku()).distinct().toList();
        var found=new HashMap<String,Map<String,Candidates>>();
        try(var c=Database.getConnection()) {
            c.setAutoCommit(false);ProductRepository.requireOwner(c,shopId,market);String owner=com.vncode.app.integration.listing.ListingIdentity.fingerprint(c,shopId,market);
            for(int start=0;start<requested.size();start+=750) {
                var batch=requested.subList(start,Math.min(start+750,requested.size()));
                String placeholders=String.join(",",Collections.nCopies(batch.size(),"?"));
                String body=market==Marketplace.OZON?"""
                    SELECT p.sku requested_sku,p.product_id card_id,p.offer_id variant_id,b.barcode
                    FROM ozon_products p JOIN ozon_product_barcodes b ON b.shop_id=p.shop_id AND b.product_id=p.product_id
                    WHERE p.shop_id=? AND p.archived=0 AND p.sku IN (
                    """:"""
                    SELECT selected.sku requested_sku,CAST(s.nm_id AS TEXT) card_id,CAST(s.chrt_id AS TEXT) variant_id,b.sku barcode
                    FROM wb_product_sizes s JOIN wb_product_size_skus selected ON selected.shop_id=s.shop_id AND selected.chrt_id=s.chrt_id
                    JOIN wb_product_size_skus b ON b.shop_id=s.shop_id AND b.chrt_id=s.chrt_id
                    WHERE s.shop_id=? AND selected.sku IN (
                    """;
                String sql="SELECT b.requested_sku,b.card_id,b.variant_id,g.gtin,m.gtin active_gtin FROM ("+body+placeholders+")) b "+"""
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
                        String gtin=r.getString("gtin");if(!RegisteredGtinValidator.isValid(gtin)||com.vncode.app.integration.znack.GtinNormalizer.isTechnicalRange(gtin))continue;gtin=com.vncode.app.integration.znack.GtinNormalizer.normalize(gtin);
                        String key=r.getString("requested_sku")+"|"+r.getString("card_id");
                        var entry=found.computeIfAbsent(key,k->new HashMap<>()).computeIfAbsent(r.getString("variant_id"),k->new Candidates());
                        entry.gtins.add(gtin);if(r.getString("active_gtin")!=null)entry.active=gtin;
                    }}
                }
            }
            for(var product:products) {
                String sku=market==Marketplace.OZON?product.catalogSku():product.sku();
                var variants=found.getOrDefault(sku+"|"+product.productId(),Map.of());
                Candidates entry=product.variantId().isBlank()?(variants.size()==1?variants.values().iterator().next():null):variants.get(product.variantId());
                if(entry!=null&&(entry.active!=null||entry.gtins.size()==1))result.put(product,entry.active!=null?entry.active:entry.gtins.iterator().next());
            }
            c.commit();return Map.copyOf(result);
        }catch(SQLException failure){throw new IllegalStateException("Unable to verify FBO GTIN associations",failure);}
    }
}
