package com.vncode.app.features.news;
import com.vncode.app.models.Shop;
import com.vncode.app.integration.marketplace.Marketplace;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
public record NewsScope(int shopId,Marketplace marketplace,String ownerFingerprint) {
 public NewsScope {if(shopId<=0||marketplace==null||ownerFingerprint==null||!ownerFingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid news owner");}
 public static NewsScope forShop(Shop shop){Objects.requireNonNull(shop);return new NewsScope(shop.getId(),shop.getMarketplace(),hash(shop.getId()+"|"+shop.getMarketplace()+"|"+Objects.toString(shop.getClientId(),"")+"|"+Objects.toString(shop.getApiKey(),"")));}
 public void requireOwner(Shop shop){if(!equals(forShop(shop)))throw new SecurityException("News owner mismatch");}
 public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
 @Override public String toString(){return "NewsScope[shop="+shopId+",marketplace="+marketplace+"]";}
}
