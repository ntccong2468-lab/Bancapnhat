package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.*;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.*;

/** Exact previously rejected feed proof for a seller-reviewed retry of an uncreated GTIN. */
final class CatalogRepairFeedEvidence {
    private static final Gson JSON=new Gson();
    static void store(Shop shop,ProductKey key,ZnackRegistrationScope scope,RegistrationIntent intent,String gtin,JsonObject payload,CatalogErrorRepairService.FeedRejection old){
        if(old==null||old.feedId().isBlank())throw invalid();
        try{SqliteWriteTransaction.run(c->{check(c,shop,key,scope,intent,gtin,payload);
            try(var s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS catalog_repair_feed_proofs(run_id TEXT NOT NULL,position INTEGER NOT NULL,shop_id INTEGER NOT NULL,marketplace TEXT NOT NULL,product_id TEXT NOT NULL,variant_id TEXT NOT NULL,owner TEXT NOT NULL,scope_hash TEXT NOT NULL,gtin TEXT NOT NULL,payload_hash TEXT NOT NULL,rejected_feed TEXT NOT NULL,old_payload_hash TEXT NOT NULL,PRIMARY KEY(run_id,position,shop_id,marketplace,product_id,variant_id))");}
            try(var p=c.prepareStatement("INSERT INTO catalog_repair_feed_proofs VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")){
                bind(p,shop,key,scope,intent,gtin,payload);p.setString(11,old.feedId());p.setString(12,ListingIdentity.hash(old.payload()));p.executeUpdate();
            }return null;
        });}catch(SQLException e){throw new IllegalStateException("SYNC_STORAGE",e);}
    }
    static void verifyBeforeSubmit(Shop shop,ProductKey key,ZnackRegistrationScope scope,RegistrationIntent intent,String gtin,JsonObject payload,ZnackCardRegistrationWorkflow.CatalogSession session)throws Exception{
        if(intent==null||scope==null||session==null||!scope.equals(session.scope()))throw invalid();String oldFeed;
        try(var c=Database.getConnection()){oldFeed=assertLocal(c,shop,key,scope,intent,gtin,payload);}
        catch(SQLException e){throw invalid();}
        if(!session.catalog().verifiedRejectedUncreatedFeed(session.token(),oldFeed,gtin))throw invalid();scope.assertCurrent(shop);ListingIdentity.require(shop,key);
    }
    static String assertLocal(Connection c,Shop shop,ProductKey key,ZnackRegistrationScope scope,RegistrationIntent intent,String gtin,JsonObject payload)throws SQLException {
        check(c,shop,key,scope,intent,gtin,payload);
        try(var p=c.prepareStatement("SELECT rejected_feed FROM catalog_repair_feed_proofs WHERE run_id=? AND position=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND owner=? AND scope_hash=? AND gtin=? AND payload_hash=?")){
            bind(p,shop,key,scope,intent,gtin,payload);try(var r=p.executeQuery()){if(!r.next())throw invalid();String feed=r.getString(1);if(r.next()||feed==null||feed.isBlank())throw invalid();return feed;}
        }
    }
    private static void check(Connection c,Shop shop,ProductKey key,ZnackRegistrationScope scope,RegistrationIntent intent,String gtin,JsonObject payload)throws SQLException {
        if(intent==null||scope==null||payload==null||payload.has("good_id")||!payload.has("gtin")||!gtin.equals(payload.get("gtin").getAsString()))throw invalid();
        ListingIdentity.require(shop,key);scope.assertCurrent(shop);
        if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw invalid();
        try(var p=c.prepareStatement("SELECT 1 FROM sync_runs r JOIN sync_run_items i ON i.run_id=r.id WHERE r.id=? AND r.shop_id=? AND r.marketplace=? AND r.owner=? AND r.kind='catalog_repair' AND r.state NOT IN ('CANCELLED','FAILED') AND i.product_id=? AND i.variant_id=? AND i.gtin=? AND i.state NOT IN ('CANCELLED','FAILED')")){
            p.setString(1,intent.runId());p.setInt(2,shop.getId());p.setString(3,key.marketplace().name());p.setString(4,ListingIdentity.fingerprint(shop));p.setString(5,key.cardId());p.setString(6,key.variantId());p.setString(7,gtin);try(var r=p.executeQuery()){if(!r.next())throw invalid();}
        }
        String sql=key.marketplace()==Marketplace.OZON
                ?"SELECT payload_json FROM ozon_card_registrations WHERE shop_id=? AND product_id=? AND offer_id=? AND gtin=? AND good_id IS NULL AND status IN ('READY_TO_SUBMIT','FEED_SUBMITTING') AND NULLIF(feed_id,'') IS NULL"
                :"SELECT payload_json FROM znack_card_registrations WHERE shop_id=? AND nm_id=? AND chrt_id=? AND gtin=? AND good_id IS NULL AND status IN ('READY_TO_SUBMIT','FEED_SUBMITTING') AND NULLIF(feed_id,'') IS NULL";
        try(var p=c.prepareStatement(sql)){p.setInt(1,key.shopId());p.setString(2,key.cardId());p.setString(3,key.variantId());p.setString(4,gtin);try(var r=p.executeQuery()){if(!r.next()||!ListingIdentity.hash(payload.toString()).equals(ListingIdentity.hash(r.getString(1))))throw invalid();}}
    }
    private static void bind(PreparedStatement p,Shop shop,ProductKey key,ZnackRegistrationScope scope,RegistrationIntent intent,String gtin,JsonObject payload)throws SQLException {
        p.setString(1,intent.runId());p.setInt(2,intent.position());p.setInt(3,shop.getId());p.setString(4,key.marketplace().name());p.setString(5,key.cardId());p.setString(6,key.variantId());p.setString(7,ListingIdentity.fingerprint(shop));p.setString(8,ListingIdentity.hash(JSON.toJson(scope)));p.setString(9,gtin);p.setString(10,ListingIdentity.hash(payload.toString()));
    }
    private static RegistrationValidationException invalid(){return new RegistrationValidationException("CATALOG_FEED_READBACK_REQUIRED");}
    private CatalogRepairFeedEvidence(){}
}
