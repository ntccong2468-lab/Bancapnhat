package com.vncode.app.integration.znack.registration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.features.products.ProductSnapshot;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.GtinNormalizer;
import com.vncode.app.models.Shop;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** Seller's durable permission to correct this exact submitted card, not a signing or GTIN-write consent. */
final class RegistrationRepairConsent {
    private static final Gson JSON=new Gson();
    private static void ensure(Connection c)throws SQLException {
        try(var s=c.createStatement()){s.execute("""
            CREATE TABLE IF NOT EXISTS catalog_repair_consents(
              shop_id INTEGER NOT NULL,marketplace TEXT NOT NULL,product_id TEXT NOT NULL,variant_id TEXT NOT NULL,
              owner TEXT NOT NULL,scope_hash TEXT NOT NULL,gtin TEXT NOT NULL,initial_payload_hash TEXT NOT NULL,created_at TEXT NOT NULL,
              run_id TEXT NOT NULL DEFAULT '',intent_id TEXT NOT NULL DEFAULT '',
              PRIMARY KEY(shop_id,marketplace,product_id,variant_id,owner,scope_hash,gtin))
            """);}
        var columns=new HashSet<String>();try(var s=c.createStatement();var r=s.executeQuery("PRAGMA table_info(catalog_repair_consents)")){while(r.next())columns.add(r.getString("name"));}
        for(String col:List.of("run_id","intent_id"))if(!columns.contains(col))try(var s=c.createStatement()){s.execute("ALTER TABLE catalog_repair_consents ADD COLUMN "+col+" TEXT NOT NULL DEFAULT ''");}
    }
    private static void guard(Connection c,Shop shop,ProductKey key,ZnackRegistrationScope scope,String gtin,JsonObject payload)throws SQLException {
        ListingIdentity.require(shop,key);scope.assertCurrent(shop);
        if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new SecurityException("SHOP_CREDENTIALS_CHANGED");
        if(!RegisteredGtinValidator.isValid(gtin)||payload==null||!payload.has("gtin")||!payload.get("gtin").isJsonPrimitive()
                ||!RegisteredGtinProof.sameCode(gtin,payload.get("gtin").getAsString()))throw new IllegalArgumentException("CATALOG_IDENTITY_CHANGED");
        String sql=key.marketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON
                ?"SELECT 1 FROM ozon_card_registrations WHERE shop_id=? AND product_id=? AND offer_id=? AND gtin=?"
                :"SELECT 1 FROM znack_card_registrations WHERE shop_id=? AND nm_id=? AND chrt_id=? AND gtin=?";
        try(var p=c.prepareStatement(sql)){p.setInt(1,key.shopId());p.setString(2,key.cardId());p.setString(3,key.variantId());p.setString(4,gtin);try(var r=p.executeQuery()){if(!r.next())throw new IllegalStateException("REGISTRATION_CHANGED");}}
    }
    // Legacy callers cannot infer the seller's repair policy from an ordinary registration.
    static void grant(Shop shop,ProductKey key,ZnackRegistrationScope scope,String gtin,JsonObject payload){throw new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");}
    static void grant(Connection c,Shop shop,ProductKey key,ZnackRegistrationScope scope,String gtin,JsonObject payload)throws SQLException {throw new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");}
    /** Require a current, seller-approved operation; an ordinary submit is not consent. */
    static void grantIfApproved(Connection c,Shop shop,ProductKey key,ZnackRegistrationScope scope,RegistrationIntent intent,
                                ProductSnapshot snapshot,List<ZnackCardRegistrationModels.Attribute> schema,JsonObject payload)throws SQLException {
        if(intent==null||scope==null)throw new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");
        String policy;int limit;
        try(var p=c.prepareStatement("SELECT repair_policy,repair_limit FROM znack_source_consents WHERE run_id=? AND position=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=?")){
            p.setString(1,intent.runId());p.setInt(2,intent.position());p.setInt(3,key.shopId());p.setString(4,key.marketplace().name());p.setString(5,key.cardId());p.setString(6,key.variantId());
            try(var r=p.executeQuery()){if(!r.next())throw new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");policy=r.getString(1);limit=r.getInt(2);}
        }
        if(policy==null||policy.isBlank())return;
        if(!"EVIDENCE_V1".equals(policy)||limit!=CatalogAutomaticRepairLedger.LIMIT)throw new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");
        var action=RegistrationSourceConsentGuard.action(c,intent,key);
        var proof=RegistrationSourceConsentGuard.require(c,shop,intent.runId(),snapshot,schema,payload,action,true);
        grant(c,shop,key,scope,payload.get("gtin").getAsString(),payload,proof);
    }
    static void grant(Connection c,Shop shop,ProductKey key,ZnackRegistrationScope scope,String gtin,JsonObject payload,
                      RegistrationSourceConsentGuard.VerifiedRepairIntent proof)throws SQLException {
        if(scope==null||proof==null||!key.equals(proof.key())||!proof.matchesPayload(payload))throw new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");
        RegistrationSourceConsentGuard.requireCurrent(c,proof);guard(c,shop,key,scope,gtin,payload);ensure(c);
        try(var p=c.prepareStatement("""
            INSERT INTO catalog_repair_consents(shop_id,marketplace,product_id,variant_id,owner,scope_hash,gtin,initial_payload_hash,created_at,run_id,intent_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(shop_id,marketplace,product_id,variant_id,owner,scope_hash,gtin)
            DO UPDATE SET initial_payload_hash=excluded.initial_payload_hash,created_at=excluded.created_at,run_id=excluded.run_id,intent_id=excluded.intent_id
            """)){
            bind(p,shop,key,scope,gtin);p.setString(8,ListingIdentity.hash(payload.toString()));p.setString(9,Instant.now().toString());p.setString(10,proof.run());p.setString(11,proof.intent());p.executeUpdate();
        }
    }
    static boolean allowed(Shop shop,ProductKey key,ZnackRegistrationScope scope,String gtin,JsonObject payload) {return false;}
    static boolean allowed(Connection c,Shop shop,ProductKey key,ZnackRegistrationScope scope,String gtin,JsonObject payload)throws SQLException {return false;}
    static boolean allowed(Connection c,Shop shop,ProductKey key,ZnackRegistrationScope scope,String gtin,JsonObject payload,
                           RegistrationSourceConsentGuard.VerifiedRepairIntent proof)throws SQLException {
        if(scope==null||proof==null||!key.equals(proof.key()))return false;
        RegistrationSourceConsentGuard.requireCurrent(c,proof);
        guard(c,shop,key,scope,gtin,payload);
            try(var exists=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='catalog_repair_consents'");var r=exists.executeQuery()){if(!r.next())return false;}
            String initial;
            try(var p=c.prepareStatement("SELECT initial_payload_hash,run_id,intent_id FROM catalog_repair_consents WHERE shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND owner=? AND scope_hash=? AND gtin=?")){
                bind(p,shop,key,scope,gtin);try(var r=p.executeQuery()){if(!r.next()||!proof.run().equals(r.getString(2))||!proof.intent().equals(r.getString(3)))return false;initial=r.getString(1);}
            }
            if(!initial.equals(ListingIdentity.hash(proof.originalPayload().toString())))return false;
            String hash=ListingIdentity.hash(payload.toString());if(hash.equals(initial))return true;
            // Only the bounded correction ledger can authorize a derived payload. The caller
            // still needs a fresh exact-feed rejection and owner proof before another send.
            try(var exists=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='catalog_automatic_repairs'");var r=exists.executeQuery()){if(!r.next())return false;}
            long id;try{id=payload.get("good_id").getAsLong();}catch(RuntimeException invalid){return false;}
            if(id<=0)return false;
            boolean sourceColumn=false;try(var statement=c.createStatement();var rows=statement.executeQuery("PRAGMA table_info(catalog_automatic_repairs)")){while(rows.next())sourceColumn|="source_payload_hash".equals(rows.getString("name"));}
            if(!sourceColumn)return false;
            String pool=com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal.allocationPool(scope.inn(),scope.environment());
            // Verify an unbroken chain, not merely the existence of a matching destination.
            // Old ledger records lacking source hashes fail closed.
            for(int round=0;round<CatalogAutomaticRepairLedger.LIMIT;round++) {
                String previous=null;int matches=0;
                try(var p=c.prepareStatement("SELECT source_payload_hash FROM catalog_automatic_repairs WHERE scope_hash=? AND gtin=? AND good_id=? AND payload_hash=?")){
                    p.setString(1,pool);p.setString(2,GtinNormalizer.normalize(gtin));p.setLong(3,id);p.setString(4,hash);
                    try(var r=p.executeQuery()){while(r.next()){previous=r.getString(1);matches++;}}
                }
                if(matches!=1||previous==null||previous.isBlank()||previous.equals(hash))return false;
                if(previous.equals(initial))return true;
                hash=previous;
            }
            return false;
    }
    /** Rebuild proof from the original seller-reviewed payload and fresh variant/schema. */
    static RegistrationSourceConsentGuard.VerifiedRepairIntent proof(Shop shop,ProductKey key,ZnackRegistrationScope scope,RegistrationIntent intent,
            ZnackCardRegistrationWorkflow.CatalogSession session,JsonObject currentPayload)throws Exception {
        return proof(shop,key,scope,intent,session,currentPayload,(seller,product)->product.marketplace()==com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES
                ?new com.vncode.app.integration.wb.WbProductSnapshotReader().readForSynchronization(seller,product)
                :new com.vncode.app.integration.ozon.OzonProductSnapshotReader().readForSynchronization(seller,product));
    }
    static RegistrationSourceConsentGuard.VerifiedRepairIntent proof(Shop shop,ProductKey key,ZnackRegistrationScope scope,RegistrationIntent intent,
            ZnackCardRegistrationWorkflow.CatalogSession session,JsonObject currentPayload,RegistrationSnapshotReview.Reader reader)throws Exception {
        Objects.requireNonNull(reader);if(intent==null||scope==null||session==null||!scope.equals(session.scope())||currentPayload==null)return null;
        String gtin;try{gtin=currentPayload.get("gtin").getAsString();}catch(RuntimeException invalid){return null;}
        if(!RegisteredGtinValidator.isValid(gtin))return null;
        String expectedHash,expectedIntent;JsonObject original=null;
        try(var c=Database.getConnection()){
            guard(c,shop,key,scope,gtin,currentPayload);
            try(var p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='catalog_repair_consents'");var r=p.executeQuery()){if(!r.next())return null;}
            var columns=new HashSet<String>();try(var p=c.createStatement();var r=p.executeQuery("PRAGMA table_info(catalog_repair_consents)")){while(r.next())columns.add(r.getString("name"));}
            if(!columns.contains("run_id")||!columns.contains("intent_id"))return null;
            try(var p=c.prepareStatement("SELECT initial_payload_hash,intent_id FROM catalog_repair_consents WHERE shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND owner=? AND scope_hash=? AND gtin=? AND run_id=?")){
                bind(p,shop,key,scope,gtin);p.setString(8,intent.runId());try(var r=p.executeQuery()){if(!r.next())return null;expectedHash=r.getString(1);expectedIntent=r.getString(2);}
            }
            if(expectedIntent==null||expectedIntent.isBlank())return null;
            if(ListingIdentity.hash(currentPayload.toString()).equals(expectedHash))original=currentPayload.deepCopy();
            if(original==null){String sql=key.marketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON
                    ?"SELECT payload_json FROM ozon_card_registration_history WHERE shop_id=? AND product_id=? AND offer_id=? AND gtin=? ORDER BY id DESC LIMIT 1024"
                    :"SELECT payload_json FROM znack_card_registration_history WHERE shop_id=? AND nm_id=? AND chrt_id=? AND gtin=? ORDER BY rowid DESC LIMIT 1024";
                try(var p=c.prepareStatement(sql)){p.setInt(1,key.shopId());p.setString(2,key.cardId());p.setString(3,key.variantId());p.setString(4,gtin);try(var r=p.executeQuery()){while(r.next()){
                    String json=r.getString(1);if(expectedHash.equals(ListingIdentity.hash(json))){original=com.google.gson.JsonParser.parseString(json).getAsJsonObject();break;}
                }}}
            }
        }catch(SQLException unavailable){throw new IllegalStateException("SYNC_STORAGE",unavailable);}
        if(original==null)return null;
        scope.assertCurrent(shop);ProductSnapshot snapshot=reader.read(shop,key);
        if(snapshot==null||!key.equals(snapshot.key()))throw new SecurityException("SOURCE_PRODUCT_NOT_OBSERVED");
        if(key.marketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON)snapshot=new com.vncode.app.integration.ozon.OzonShopBrand().apply(shop,snapshot);
        var draft=ZnackCardRegistrationWorkflow.draftFromPayload(original);List<ZnackCardRegistrationModels.Attribute> schema;
        try(var read=session.catalog().registrationCountryReadScope()){schema=session.catalog().registrationAttributes(draft.categoryId(),session.token());}
        try(var c=Database.getConnection()){
            var action=RegistrationSourceConsentGuard.action(c,intent,key);
            var proof=RegistrationSourceConsentGuard.require(c,shop,intent.runId(),snapshot,schema,original,action,true);
            if(!expectedIntent.equals(proof.intent())||!allowed(c,shop,key,scope,gtin,currentPayload,proof))return null;
            return proof;
        }
    }
    private static void bind(java.sql.PreparedStatement p,Shop shop,ProductKey key,ZnackRegistrationScope scope,String gtin)throws SQLException {
        p.setInt(1,shop.getId());p.setString(2,key.marketplace().name());p.setString(3,key.cardId());p.setString(4,key.variantId());
        p.setString(5,ListingIdentity.fingerprint(shop));p.setString(6,ListingIdentity.hash(JSON.toJson(scope)));p.setString(7,gtin);
    }
    private RegistrationRepairConsent(){}
}
