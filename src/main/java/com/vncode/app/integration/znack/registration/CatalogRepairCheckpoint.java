package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Imports a server-confirmed rejected card into the existing durable feed state machine.
 * Never resets pending POSTs, published cards, signatures or a different GTIN. */
final class CatalogRepairCheckpoint {
    static boolean prepare(Shop shop,ProductKey key,String sku,String gtin,long goodId,String payload,
                           ZnackCardRegistrationModels.Draft draft,ZnackRegistrationScope scope)throws SQLException {
        ListingIdentity.require(shop,key);scope.assertCurrent(shop);
        if(payload.length()>2*1024*1024||goodId<=0||!com.vncode.app.features.gtinsync.RegisteredGtinValidator.isValid(gtin))throw new IllegalArgumentException("CATALOG_REPAIR_INVALID");
        return SqliteWriteTransaction.run(c->{
            if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new SecurityException("SHOP_CREDENTIALS_CHANGED");
            return key.marketplace()==Marketplace.WILDBERRIES?wb(c,shop,key,gtin,goodId,payload,draft,scope):ozon(c,shop,key,sku,gtin,goodId,payload,scope);
        });
    }
    private static boolean wb(Connection c,Shop shop,ProductKey key,String gtin,long goodId,String payload,ZnackCardRegistrationModels.Draft draft,ZnackRegistrationScope scope)throws SQLException {
        long variant=Long.parseLong(key.variantId()),card=Long.parseLong(key.cardId());
        try(var p=c.prepareStatement("SELECT 1 FROM wb_product_sizes WHERE shop_id=? AND nm_id=? AND chrt_id=?")){p.setInt(1,shop.getId());p.setLong(2,card);p.setLong(3,variant);try(var r=p.executeQuery()){if(!r.next())return false;}}
        try(var p=c.prepareStatement("SELECT participant_inn,environment,fingerprint,owner_hash FROM znack_card_registration_scopes WHERE shop_id=? AND chrt_id=?")){
            p.setInt(1,shop.getId());p.setLong(2,variant);try(var r=p.executeQuery()){if(r.next()&&(!scope.equals(new ZnackRegistrationScope(r.getString(1),r.getString(2),r.getString(3)))||!ListingIdentity.fingerprint(shop).equals(r.getString(4))))throw new ZnackRegistrationScope.ScopeChangedException();}
        }
        try(var p=c.prepareStatement("SELECT status,gtin FROM znack_card_registrations WHERE shop_id=? AND chrt_id=?")){
            p.setInt(1,shop.getId());p.setLong(2,variant);try(var r=p.executeQuery()){if(r.next()&&!replaceable(r.getString(1),r.getString(2),gtin))return false;}
        }
        try(var p=c.prepareStatement("SELECT 1 FROM znack_card_registrations WHERE shop_id=? AND chrt_id<>? AND gtin=? UNION ALL SELECT 1 FROM znack_card_registration_signatures WHERE shop_id=? AND chrt_id=? AND state IN ('SENDING','RECONCILE_REQUIRED') UNION ALL SELECT 1 FROM znack_card_registration_replacements WHERE shop_id=? AND chrt_id=? AND state='CANDIDATE'")){
            p.setInt(1,shop.getId());p.setLong(2,variant);p.setString(3,gtin);p.setInt(4,shop.getId());p.setLong(5,variant);p.setInt(6,shop.getId());p.setLong(7,variant);try(var r=p.executeQuery()){if(r.next())return false;}
        }
        String now=Instant.now().toString();
        try(var p=c.prepareStatement("INSERT OR IGNORE INTO znack_card_registration_history SELECT shop_id,chrt_id,nm_id,gtin,good_id,feed_id,payload_json,tnved,status,error_message,? FROM znack_card_registrations WHERE shop_id=? AND chrt_id=? AND gtin<>''")){p.setString(1,now);p.setInt(2,shop.getId());p.setLong(3,variant);p.executeUpdate();}
        try(var p=c.prepareStatement("""
            INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,vendor_code,source_barcode,gtin,good_id,feed_id,tnved,category_id,good_name,payload_json,status,wb_updated,created_at,updated_at)
            SELECT ?,?,?,vendor_code,'',?,?,'',?,?,?,?, 'READY_TO_SUBMIT',0,?,? FROM wb_product_cards WHERE shop_id=? AND nm_id=?
            ON CONFLICT(shop_id,chrt_id) DO UPDATE SET gtin=excluded.gtin,good_id=excluded.good_id,feed_id='',tnved=excluded.tnved,category_id=excluded.category_id,good_name=excluded.good_name,payload_json=excluded.payload_json,status='READY_TO_SUBMIT',error_message=NULL,updated_at=excluded.updated_at
            """)){
            p.setInt(1,shop.getId());p.setLong(2,variant);p.setLong(3,card);p.setString(4,gtin);p.setLong(5,goodId);p.setString(6,draft.tnved());p.setLong(7,draft.categoryId());p.setString(8,draft.goodName());p.setString(9,payload);p.setString(10,now);p.setString(11,now);p.setInt(12,shop.getId());p.setLong(13,card);if(p.executeUpdate()!=1)return false;
        }
        try(var p=c.prepareStatement("INSERT OR IGNORE INTO znack_card_registration_scopes(shop_id,chrt_id,participant_inn,environment,fingerprint,owner_hash,created_at) VALUES(?,?,?,?,?,?,?)")){p.setInt(1,shop.getId());p.setLong(2,variant);p.setString(3,scope.inn());p.setString(4,scope.environment());p.setString(5,scope.fingerprint());p.setString(6,ListingIdentity.fingerprint(shop));p.setString(7,now);p.executeUpdate();}
        try(var p=c.prepareStatement("DELETE FROM znack_card_registration_signatures WHERE shop_id=? AND chrt_id=?")){p.setInt(1,shop.getId());p.setLong(2,variant);p.executeUpdate();}
        return true;
    }
    private static boolean ozon(Connection c,Shop shop,ProductKey key,String sku,String gtin,long goodId,String payload,ZnackRegistrationScope scope)throws SQLException {
        if(!sku.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("OZON_SKU_UNAVAILABLE");
        try(var p=c.prepareStatement("SELECT 1 FROM ozon_products WHERE shop_id=? AND product_id=? AND offer_id=? AND sku=?")){bindKey(p,key);p.setString(4,sku);try(var r=p.executeQuery()){if(!r.next())return false;}}
        try(var p=c.prepareStatement("SELECT 1 FROM ozon_card_registrations WHERE shop_id=? AND gtin=? AND NOT(product_id=? AND offer_id=?)")){p.setInt(1,key.shopId());p.setString(2,gtin);p.setString(3,key.cardId());p.setString(4,key.variantId());try(var r=p.executeQuery()){if(r.next())return false;}}
        String scopeJson=new com.google.gson.Gson().toJson(scope),owner=ListingIdentity.fingerprint(shop),now=Instant.now().toString();
        try(var p=c.prepareStatement("SELECT status,gtin,scope_json,owner,signature_state FROM ozon_card_registrations WHERE shop_id=? AND product_id=? AND offer_id=?")){
            bindKey(p,key);try(var r=p.executeQuery()){if(r.next()){
                if(!scopeJson.equals(r.getString(3))||!owner.equals(r.getString(4)))throw new ZnackRegistrationScope.ScopeChangedException();
                if(!replaceable(r.getString(1),r.getString(2),gtin)||Set.of("SENDING","RECONCILE_REQUIRED").contains(r.getString(5)))return false;
            }}
        }
        try(var p=c.prepareStatement("INSERT INTO ozon_card_registration_history(shop_id,product_id,offer_id,gtin,payload_json,feed_id,good_id,created_at) SELECT shop_id,product_id,offer_id,gtin,payload_json,feed_id,good_id,? FROM ozon_card_registrations WHERE shop_id=? AND product_id=? AND offer_id=? AND gtin<>''")){p.setString(1,now);p.setInt(2,key.shopId());p.setString(3,key.cardId());p.setString(4,key.variantId());p.executeUpdate();}
        try(var p=c.prepareStatement("""
            INSERT INTO ozon_card_registrations(shop_id,product_id,offer_id,sku,status,gtin,good_id,payload_json,scope_json,owner,created_at,updated_at)
            SELECT ?,?,?,?,'READY_TO_SUBMIT',?,?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM ozon_products WHERE shop_id=? AND product_id=? AND offer_id=?)
            ON CONFLICT(shop_id,product_id,offer_id) DO UPDATE SET status='READY_TO_SUBMIT',gtin=excluded.gtin,good_id=excluded.good_id,feed_id='',payload_json=excluded.payload_json,signature_xml=NULL,signature_sha256='',signature_state='',error='',updated_at=excluded.updated_at
            """)){
            bindKey(p,key);p.setString(4,sku);p.setString(5,gtin);p.setLong(6,goodId);p.setString(7,payload);p.setString(8,scopeJson);p.setString(9,owner);p.setString(10,now);p.setString(11,now);p.setInt(12,key.shopId());p.setString(13,key.cardId());p.setString(14,key.variantId());return p.executeUpdate()==1;
        }
    }
    private static boolean replaceable(String status,String old,String gtin){return Set.of("NOT_CREATED","PREFLIGHT_ERROR","PRE_SUBMIT_ERROR","ERROR").contains(status)&&(old==null||old.isBlank()||com.vncode.app.features.gtinsync.RegisteredGtinProof.sameCode(old,gtin));}
    private static void bindKey(PreparedStatement p,ProductKey key)throws SQLException{p.setInt(1,key.shopId());p.setString(2,key.cardId());p.setString(3,key.variantId());}
}
