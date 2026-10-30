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
    /** Atomically order Ozon HTTP reads across app processes before dispatch. */
    public static Map<String,Long> issueOzon(int shopId,String owner,Collection<String> productIds){
        var ids=new LinkedHashSet<>(productIds);if(shopId<=0||owner==null||owner.isBlank()||ids.size()>1000||ids.stream().anyMatch(id->id==null||id.isBlank()||id.length()>256||!id.equals(id.strip())||id.codePoints().anyMatch(Character::isISOControl)))throw new IllegalArgumentException("INVALID_OZON_READ_IDS");
        if(ids.isEmpty())return Map.of();
        try{return SqliteWriteTransaction.run(c->{
            if(!owner.equals(ListingIdentity.fingerprint(c,shopId,Marketplace.OZON)))throw new SecurityException("SHOP_CREDENTIALS_CHANGED");
            var result=new LinkedHashMap<String,Long>();
            try(var update=c.prepareStatement("INSERT INTO gtin_sync_barcode_read_heads(shop_id,owner,product_id,generation) VALUES(?,?,?,1) ON CONFLICT(shop_id,owner,product_id) DO UPDATE SET generation=generation+1");
                var read=c.prepareStatement("SELECT generation FROM gtin_sync_barcode_read_heads WHERE shop_id=? AND owner=? AND product_id=?")){
                for(var id:ids){update.setInt(1,shopId);update.setString(2,owner);update.setString(3,id);update.executeUpdate();read.setInt(1,shopId);read.setString(2,owner);read.setString(3,id);try(var row=read.executeQuery()){if(!row.next())throw new SQLException("MISSING_OZON_READ_HEAD");result.put(id,row.getLong(1));}}
            }return Map.copyOf(result);
        });}catch(SQLException failure){throw new IllegalStateException("GTIN_STORAGE",failure);}
    }
    /** False means another read for the same product started after this request. */
    public static boolean currentOzonRead(Connection c,int shopId,String owner,String productId,long generation)throws SQLException{
        try(var p=c.prepareStatement("SELECT generation FROM gtin_sync_barcode_read_heads WHERE shop_id=? AND owner=? AND product_id=?")){
            p.setInt(1,shopId);p.setString(2,owner);p.setString(3,productId);try(var r=p.executeQuery()){return r.next()&&r.getLong(1)==generation;}
        }
    }
    public static void observe(Shop shop,GtinSyncModels.ProductKey key,List<String> barcodes){
        var owner=ListingIdentity.fingerprint(shop);var tokens=key.marketplace()==Marketplace.OZON?issueOzon(shop.getId(),owner,List.of(key.productId())):Map.<String,Long>of();
        observe(shop,key,barcodes,java.time.Instant.now(),tokens);
    }
    /** Returns false when a newer read already won or the owned product no longer exists. */
    public static boolean observe(Shop shop,GtinSyncModels.ProductKey key,List<String> barcodes,java.time.Instant started,Map<String,Long> tokens){
        Objects.requireNonNull(started);
        if(key.shopId()!=shop.getId()||key.marketplace()!=shop.getMarketplace())throw new SecurityException("PRODUCT_MISMATCH");
        ListingIdentity.require(shop);var actual=new LinkedHashSet<>(barcodes);
        if(actual.stream().anyMatch(v->v==null||v.isBlank())||actual.size()>10000)throw new IllegalArgumentException("INVALID_BARCODES");
        try{
            try(var c=Database.getConnection()){if(key.marketplace()!=Marketplace.OZON&&(!product(c,key)||codes(c,key).equals(actual)))return true;}
            return SqliteWriteTransaction.run(c->{
                if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new SecurityException("SHOP_CREDENTIALS_CHANGED");
                if(key.marketplace()==Marketplace.OZON&&!currentOzonRead(c,key.shopId(),ListingIdentity.fingerprint(shop),key.productId(),tokens.getOrDefault(key.productId(),0L)))return false;
                if(!product(c,key)){if(key.marketplace()==Marketplace.OZON)recordRead(c,key,ListingIdentity.fingerprint(shop),started);return true;}
                String table=key.marketplace()==Marketplace.WILDBERRIES?"wb_product_size_skus":"ozon_product_barcodes";
                String id=key.marketplace()==Marketplace.WILDBERRIES?"chrt_id":"product_id";
                String value=key.marketplace()==Marketplace.WILDBERRIES?"sku":"barcode";
                String nativeId=key.marketplace()==Marketplace.WILDBERRIES?key.variantId():key.productId();
                if(!codes(c,key).equals(actual)){
                    try(var p=c.prepareStatement("DELETE FROM "+table+" WHERE shop_id=? AND "+id+"=?")){p.setInt(1,key.shopId());p.setString(2,nativeId);p.executeUpdate();}
                    try(var p=c.prepareStatement("INSERT INTO "+table+"(shop_id,"+id+","+value+") VALUES(?,?,?)")){
                        for(String code:actual){p.setInt(1,key.shopId());p.setString(2,nativeId);p.setString(3,code);p.addBatch();}p.executeBatch();
                    }
                }
                if(key.marketplace()==Marketplace.OZON)recordRead(c,key,ListingIdentity.fingerprint(shop),started);
                return true;
            });
        }catch(SQLException failure){throw new IllegalStateException("GTIN_STORAGE",failure);}
    }
    /** Actual GET pages only, with reused statements and commits bounded to 250 changed variants. */
    public static void observeBatch(Shop shop,Map<GtinSyncModels.ProductKey,List<String>> observations){
        var started=new LinkedHashMap<GtinSyncModels.ProductKey,java.time.Instant>();var now=java.time.Instant.now();observations.keySet().forEach(key->started.put(key,now));
        var ids=observations.keySet().stream().map(GtinSyncModels.ProductKey::productId).distinct().toList();
        var tokens=shop.getMarketplace()==Marketplace.OZON?issueOzon(shop.getId(),ListingIdentity.fingerprint(shop),ids):Map.<String,Long>of();
        observeBatch(shop,observations,started,tokens);
    }
    /** Starts are captured before their individual HTTP requests, not when responses arrive. */
    public static Set<GtinSyncModels.ProductKey> observeBatch(Shop shop,Map<GtinSyncModels.ProductKey,List<String>> observations,Map<GtinSyncModels.ProductKey,java.time.Instant> started,Map<String,Long> tokens){
        if(observations.isEmpty())return Set.of();ListingIdentity.require(shop);
        if(!started.keySet().containsAll(observations.keySet())||started.values().stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("INVALID_READ_TIMESTAMP");
        String productSql=shop.getMarketplace()==Marketplace.WILDBERRIES?"SELECT 1 FROM wb_product_sizes WHERE shop_id=? AND nm_id=? AND chrt_id=?":"SELECT 1 FROM ozon_products WHERE shop_id=? AND product_id=? AND offer_id=? AND archived=0";
        String table=shop.getMarketplace()==Marketplace.WILDBERRIES?"wb_product_size_skus":"ozon_product_barcodes",nativeColumn=shop.getMarketplace()==Marketplace.WILDBERRIES?"chrt_id":"product_id",valueColumn=shop.getMarketplace()==Marketplace.WILDBERRIES?"sku":"barcode";
        var changes=new LinkedHashMap<GtinSyncModels.ProductKey,Set<String>>();
        try(var c=Database.getConnection();var product=c.prepareStatement(productSql);var codes=c.prepareStatement("SELECT "+valueColumn+" FROM "+table+" WHERE shop_id=? AND "+nativeColumn+"=?")){
            for(var entry:observations.entrySet()){var key=entry.getKey();if(key.shopId()!=shop.getId()||key.marketplace()!=shop.getMarketplace())throw new SecurityException("PRODUCT_MISMATCH");var actual=new LinkedHashSet<>(entry.getValue());if(actual.size()>10000||actual.stream().anyMatch(v->v==null||v.isBlank()))throw new IllegalArgumentException("INVALID_BARCODES");
                product.setInt(1,shop.getId());product.setString(2,key.productId());product.setString(3,key.variantId());try(var r=product.executeQuery()){if(!r.next()){if(shop.getMarketplace()==Marketplace.OZON)changes.put(key,actual);continue;}}
                codes.setInt(1,shop.getId());codes.setString(2,shop.getMarketplace()==Marketplace.WILDBERRIES?key.variantId():key.productId());var existing=new HashSet<String>();try(var r=codes.executeQuery()){while(r.next())existing.add(r.getString(1));}if(!existing.equals(actual)||shop.getMarketplace()==Marketplace.OZON)changes.put(key,actual);
            }
        }catch(SQLException failure){throw new IllegalStateException("GTIN_STORAGE",failure);}
        var entries=new ArrayList<>(changes.entrySet());var accepted=new LinkedHashSet<GtinSyncModels.ProductKey>();
        for(int offset=0;offset<entries.size();offset+=250){var part=entries.subList(offset,Math.min(offset+250,entries.size()));
            try{SqliteWriteTransaction.run(c->{if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new SecurityException("SHOP_CREDENTIALS_CHANGED");
                try(var exists=c.prepareStatement(productSql);var remove=c.prepareStatement("DELETE FROM "+table+" WHERE shop_id=? AND "+nativeColumn+"=?");var insert=c.prepareStatement("INSERT INTO "+table+"(shop_id,"+nativeColumn+","+valueColumn+") VALUES(?,?,?)")){
                    for(var entry:part){var key=entry.getKey();exists.setInt(1,shop.getId());exists.setString(2,key.productId());exists.setString(3,key.variantId());boolean present;try(var r=exists.executeQuery()){present=r.next();}if(shop.getMarketplace()==Marketplace.OZON&&!currentOzonRead(c,key.shopId(),ListingIdentity.fingerprint(shop),key.productId(),tokens.getOrDefault(key.productId(),0L)))continue;if(!present){if(shop.getMarketplace()==Marketplace.OZON){recordRead(c,key,ListingIdentity.fingerprint(shop),started.get(key));accepted.add(key);}continue;}String nativeId=shop.getMarketplace()==Marketplace.WILDBERRIES?key.variantId():key.productId();if(!codes(c,key).equals(entry.getValue())){remove.setInt(1,shop.getId());remove.setString(2,nativeId);remove.executeUpdate();for(var code:entry.getValue()){insert.setInt(1,shop.getId());insert.setString(2,nativeId);insert.setString(3,code);insert.addBatch();}}if(shop.getMarketplace()==Marketplace.OZON)recordRead(c,key,ListingIdentity.fingerprint(shop),started.get(key));accepted.add(key);}
                    insert.executeBatch();
                }return null;
            });}catch(SQLException failure){throw new IllegalStateException("GTIN_STORAGE",failure);}
        }
        if(shop.getMarketplace()!=Marketplace.OZON)accepted.addAll(observations.keySet());
        return Set.copyOf(accepted);
    }
    private static void recordRead(Connection c,GtinSyncModels.ProductKey key,String owner,java.time.Instant started)throws SQLException{
        try(var p=c.prepareStatement("INSERT INTO gtin_sync_barcode_observations(shop_id,marketplace,product_id,variant_id,owner,observed_at) VALUES(?,?,?,?,?,?) ON CONFLICT(shop_id,marketplace,product_id,variant_id) DO UPDATE SET owner=excluded.owner,observed_at=excluded.observed_at")){
            p.setInt(1,key.shopId());p.setString(2,key.marketplace().name());p.setString(3,key.productId());p.setString(4,key.variantId());p.setString(5,owner);p.setString(6,started.toString());p.executeUpdate();
        }
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
