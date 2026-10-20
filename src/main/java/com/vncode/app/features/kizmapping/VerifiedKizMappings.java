package com.vncode.app.features.kizmapping;

import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.GtinNormalizer;
import java.sql.*;
import java.util.*;

/** Variant-level choices become usable only after a completed marketplace readback. */
public final class VerifiedKizMappings {
    private VerifiedKizMappings(){}
    public static Map<String,String> read(Connection c,int shopId,Marketplace market)throws SQLException {
        String aliases=market==Marketplace.OZON?"SELECT p.sku alias,v.gtin FROM gtin_sync_verified_mappings v JOIN ozon_products p ON p.shop_id=v.shop_id AND p.product_id=v.product_id AND p.offer_id=v.variant_id AND p.archived=0 ":
            "SELECT b.sku alias,v.gtin FROM gtin_sync_verified_mappings v JOIN wb_product_size_skus b ON b.shop_id=v.shop_id AND CAST(b.chrt_id AS TEXT)=v.variant_id JOIN wb_product_sizes z ON z.shop_id=b.shop_id AND z.chrt_id=b.chrt_id AND CAST(z.nm_id AS TEXT)=v.product_id ";
        var candidates=new HashMap<String,Set<String>>();
        String sql=aliases+"""
            JOIN gtin_sync_catalog g ON g.shop_id=v.shop_id AND g.marketplace=v.marketplace AND g.gtin=v.gtin
            WHERE v.shop_id=? AND v.marketplace=? AND v.owner=?
              AND json_extract(g.data_json,'$.owner')=v.owner AND json_extract(g.data_json,'$.source')=v.source
              AND json_extract(g.data_json,'$.published')=1 AND json_extract(g.data_json,'$.tradeUnit')=1
            """;
        try(var p=c.prepareStatement(sql)) {
            p.setInt(1,shopId);p.setString(2,market.name());p.setString(3,ListingIdentity.fingerprint(c,shopId,market));
            try(var r=p.executeQuery()){while(r.next()){
                String alias=r.getString(1),gtin=r.getString(2);
                if(alias!=null&&!alias.isBlank()&&RegisteredGtinValidator.isValid(gtin)&&!GtinNormalizer.isTechnicalRange(gtin))candidates.computeIfAbsent(alias,k->new HashSet<>()).add(GtinNormalizer.normalize(gtin));
            }}
        }
        var out=new HashMap<String,String>();candidates.forEach((alias,gtins)->{if(gtins.size()==1)out.put(alias,gtins.iterator().next());});return Map.copyOf(out);
    }
}
