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
    private ListingIdentity(){}
    public static void require(Shop shop) {
        if(shop==null||shop.getId()<=0||!shop.isCredentialConfigured())throw new SecurityException("Listing owner is unavailable");
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT marketplace,client_id,api_key FROM shops WHERE id=?")){
            p.setInt(1,shop.getId());try(var r=p.executeQuery()){
                if(!r.next()||!shop.getMarketplace().name().equals(r.getString(1))||!Objects.equals(shop.getClientId(),r.getString(2))||!Objects.equals(shop.getApiKey(),r.getString(3)))throw new SecurityException("Listing owner changed");
            }
        }catch(java.sql.SQLException e){throw new IllegalStateException("Cannot validate listing owner");}
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
