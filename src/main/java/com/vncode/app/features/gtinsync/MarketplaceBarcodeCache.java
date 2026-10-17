package com.vncode.app.features.gtinsync;

import com.vncode.app.config.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.util.*;

/** Actual owned GET observations only; an update acknowledgement must never populate this cache. */
public final class MarketplaceBarcodeCache {
    private MarketplaceBarcodeCache(){}
    public static void observe(Shop shop,GtinSyncModels.ProductKey key,List<String> barcodes){
        if(key.shopId()!=shop.getId()||key.marketplace()!=shop.getMarketplace())throw new SecurityException("PRODUCT_MISMATCH");
        ListingIdentity.require(shop);var actual=new LinkedHashSet<>(barcodes);
        if(actual.stream().anyMatch(v->v==null||v.isBlank())||actual.size()>10000)throw new IllegalArgumentException("INVALID_BARCODES");
        try{
            try(var c=Database.getConnection()){if(!product(c,key)||codes(c,key).equals(actual))return;}
            SqliteWriteTransaction.run(c->{
                if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new SecurityException("SHOP_CREDENTIALS_CHANGED");
                if(!product(c,key))return null;
                String table=key.marketplace()==Marketplace.WILDBERRIES?"wb_product_size_skus":"ozon_product_barcodes";
                String id=key.marketplace()==Marketplace.WILDBERRIES?"chrt_id":"product_id";
                String value=key.marketplace()==Marketplace.WILDBERRIES?"sku":"barcode";
                String nativeId=key.marketplace()==Marketplace.WILDBERRIES?key.variantId():key.productId();
                try(var p=c.prepareStatement("DELETE FROM "+table+" WHERE shop_id=? AND "+id+"=?")){p.setInt(1,key.shopId());p.setString(2,nativeId);p.executeUpdate();}
                try(var p=c.prepareStatement("INSERT INTO "+table+"(shop_id,"+id+","+value+") VALUES(?,?,?)")){
                    for(String code:actual){p.setInt(1,key.shopId());p.setString(2,nativeId);p.setString(3,code);p.addBatch();}p.executeBatch();
                }return null;
            });
        }catch(SQLException failure){throw new IllegalStateException("GTIN_STORAGE",failure);}
    }
    private static boolean product(Connection c,GtinSyncModels.ProductKey key)throws SQLException{
        String query=key.marketplace()==Marketplace.WILDBERRIES?"SELECT 1 FROM wb_product_sizes WHERE shop_id=? AND nm_id=? AND chrt_id=?":"SELECT 1 FROM ozon_products WHERE shop_id=? AND product_id=? AND offer_id=? AND archived=0";
        try(var p=c.prepareStatement(query)){p.setInt(1,key.shopId());p.setString(2,key.productId());p.setString(3,key.variantId());try(var r=p.executeQuery()){return r.next();}}
    }
    private static Set<String> codes(Connection c,GtinSyncModels.ProductKey key)throws SQLException{
        String query=key.marketplace()==Marketplace.WILDBERRIES?"SELECT sku FROM wb_product_size_skus WHERE shop_id=? AND chrt_id=?":"SELECT barcode FROM ozon_product_barcodes WHERE shop_id=? AND product_id=?";
        var result=new HashSet<String>();try(var p=c.prepareStatement(query)){p.setInt(1,key.shopId());p.setString(2,key.marketplace()==Marketplace.WILDBERRIES?key.variantId():key.productId());try(var r=p.executeQuery()){while(r.next())result.add(r.getString(1));}}return result;
    }
}
