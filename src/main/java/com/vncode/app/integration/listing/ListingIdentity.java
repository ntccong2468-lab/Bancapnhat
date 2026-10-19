package com.vncode.app.integration.listing;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.models.Shop;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Scope includes current credentials, so deleting or rotating a shop invalidates a draft. */
public final class ListingIdentity {
    private static final ThreadLocal<OwnerCheckBatch> BATCH=new ThreadLocal<>();
    private ListingIdentity(){}
    public static void require(Shop shop) {
        if(shop==null||shop.getId()<=0||!shop.isCredentialConfigured())throw new SecurityException("Listing owner is unavailable");
        var batch=BATCH.get();if(batch!=null&&batch.shopId==shop.getId()){batch.check(shop);return;}
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT marketplace,client_id,api_key FROM shops WHERE id=?")){
            p.setInt(1,shop.getId());try(var r=p.executeQuery()){
                if(!r.next()||!shop.getMarketplace().name().equals(r.getString(1))||!Objects.equals(shop.getClientId(),r.getString(2))||!Objects.equals(shop.getApiKey(),r.getString(3)))throw new SecurityException("Listing owner changed");
            }
        }catch(java.sql.SQLException e){throw new IllegalStateException("Cannot validate listing owner");}
    }
    /** Reuses a prepared SELECT, never its result. Every guard still reads current credentials.
     * Autocommit result sets close before HTTP waits, so FBS writers are not held behind a read. */
    public static OwnerCheckBatch batchChecks(Shop shop){require(shop);return new OwnerCheckBatch(shop);}
    public static final class OwnerCheckBatch implements AutoCloseable {
        private final int shopId;private final java.nio.file.Path directory;private final OwnerCheckBatch previous;
        private final java.sql.Connection connection;private final java.sql.PreparedStatement statement,proof;
        private final Thread owner=Thread.currentThread();private boolean closed;
        private OwnerCheckBatch(Shop shop){shopId=shop.getId();directory=com.vncode.app.shared.AppPaths.appDataDir();previous=BATCH.get();connection=Database.getConnection();
            try{statement=connection.prepareStatement("SELECT marketplace,client_id,api_key FROM shops WHERE id=?");proof=connection.prepareStatement("SELECT m.gtin FROM gtin_sync_mappings m JOIN gtin_sync_catalog g ON g.shop_id=m.shop_id AND g.marketplace=m.marketplace AND g.gtin=m.gtin WHERE m.shop_id=? AND m.marketplace=? AND m.product_id=? AND m.variant_id=? AND json_extract(g.data_json,'$.published')=1 AND json_extract(g.data_json,'$.tradeUnit')=1");}
            catch(java.sql.SQLException failure){try{connection.close();}catch(java.sql.SQLException ignored){}throw new IllegalStateException("Cannot prepare listing owner check",failure);}BATCH.set(this);
        }
        private void check(Shop shop){com.vncode.app.features.sync.SyncDataScope.requireCurrent();if(closed||owner!=Thread.currentThread()||!directory.equals(com.vncode.app.shared.AppPaths.appDataDir()))throw new SecurityException("Listing data scope changed");
            try{statement.setInt(1,shopId);try(var r=statement.executeQuery()){if(!r.next()||!shop.getMarketplace().name().equals(r.getString(1))||!Objects.equals(shop.getClientId(),r.getString(2))||!Objects.equals(shop.getApiKey(),r.getString(3)))throw new SecurityException("Listing owner changed");}}
            catch(java.sql.SQLException failure){throw new IllegalStateException("Cannot validate listing owner",failure);}
        }
        private String verified(ProductKey key,List<String> barcodes)throws java.sql.SQLException {
            com.vncode.app.features.sync.SyncDataScope.requireCurrent();if(closed||owner!=Thread.currentThread()||!directory.equals(com.vncode.app.shared.AppPaths.appDataDir()))throw new SecurityException("Listing data scope changed");
            proof.setInt(1,key.shopId());proof.setString(2,key.marketplace().name());proof.setString(3,key.cardId());proof.setString(4,key.variantId());
            try(var rows=proof.executeQuery()){while(rows.next()){String gtin=rows.getString(1);if(RegisteredGtinValidator.isValid(gtin)&&barcodes.stream().anyMatch(b->pad(b).equals(pad(gtin))))return gtin;}}return "";
        }
        @Override public void close(){if(closed)return;if(owner!=Thread.currentThread()||BATCH.get()!=this)throw new IllegalStateException("Listing checks must close in scope order");closed=true;if(previous==null)BATCH.remove();else BATCH.set(previous);try{try{statement.close();}finally{try{proof.close();}finally{connection.close();}}}catch(java.sql.SQLException failure){throw new IllegalStateException("Cannot close listing owner check",failure);}}
    }
    public static void require(Shop shop,ProductKey key){require(shop);if(key.shopId()!=shop.getId()||key.marketplace()!=shop.getMarketplace())throw new SecurityException("Foreign listing owner");}
    public static String fingerprint(Shop shop){return hash(shop.getId()+"\n"+shop.getMarketplace()+"\n"+Objects.toString(shop.getClientId(),"")+"\n"+shop.getApiKey());}
    public static String fingerprint(java.sql.Connection c,int shopId,com.vncode.app.integration.marketplace.Marketplace market)throws java.sql.SQLException {
        try(var p=c.prepareStatement("SELECT marketplace,name,client_id,api_key FROM shops WHERE id=?")){p.setInt(1,shopId);try(var r=p.executeQuery()){if(!r.next()||!market.name().equals(r.getString(1)))throw new SecurityException("Listing owner changed");return fingerprint(new Shop(shopId,r.getString(2),market,r.getString(3),r.getString(4)));}}
    }
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public static String canonical(com.google.gson.JsonElement value){
        if(value.isJsonObject()){var o=new com.google.gson.JsonObject();value.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->o.add(e.getKey(),com.google.gson.JsonParser.parseString(canonical(e.getValue()))));return o.toString();}
        if(value.isJsonArray()){var a=new com.google.gson.JsonArray();for(var e:value.getAsJsonArray())a.add(com.google.gson.JsonParser.parseString(canonical(e)));return a.toString();}
        return value.toString();
    }
    public static String verifiedGtin(ProductKey key,List<String> barcodes){
        var batch=BATCH.get();if(batch!=null&&batch.shopId==key.shopId())try{return batch.verified(key,barcodes);}catch(java.sql.SQLException unavailable){return "";}
        try(var c=Database.getConnection();var p=c.prepareStatement("""
          SELECT m.gtin FROM gtin_sync_mappings m JOIN gtin_sync_catalog g ON g.shop_id=m.shop_id AND g.marketplace=m.marketplace AND g.gtin=m.gtin
          WHERE m.shop_id=? AND m.marketplace=? AND m.product_id=? AND m.variant_id=? AND json_extract(g.data_json,'$.published')=1 AND json_extract(g.data_json,'$.tradeUnit')=1
          """)){
            p.setInt(1,key.shopId());p.setString(2,key.marketplace().name());p.setString(3,key.cardId());p.setString(4,key.variantId());
            try(var r=p.executeQuery()){while(r.next()){String gtin=r.getString(1);if(RegisteredGtinValidator.isValid(gtin)&&barcodes.stream().anyMatch(b->pad(b).equals(pad(gtin))))return gtin;}}
        }catch(java.sql.SQLException ignored){/* Missing/corrupt proof is never a verified GTIN. */}
        return "";
    }
    private static String pad(String s){return s!=null&&s.matches("[0-9]{8,14}")?"0".repeat(14-s.length())+s:Objects.toString(s,"");}
}
