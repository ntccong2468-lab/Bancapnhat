package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.GtinNormalizer;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

/** Durable per-card correction budget; the caller owns the checkpoint transaction. */
public final class CatalogAutomaticRepairLedger {
    static final int LIMIT = 2;
    public record Round(String rejectedFeed, String rejection, String changesJson, String createdAt, String state) {
        public Round(String rejectedFeed,String rejection,String changesJson,String createdAt){this(rejectedFeed,rejection,changesJson,createdAt,"UNKNOWN");}
    }

    /** Read only the current seller's scope, without exposing account credentials. */
    public static java.util.List<Round> history(com.vncode.app.models.Shop shop, String gtin, long goodId) {
        ListingIdentity.require(shop);
        var settings = new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var scope = ZnackRegistrationScope.fromSettings(settings); scope.assertCurrent(shop);
        if (!RegisteredGtinValidator.isValid(gtin) || goodId <= 0) return java.util.List.of();
        String scopeKey = com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal.allocationPool(scope.inn(),scope.environment());
        try (var connection = com.vncode.app.config.Database.getConnection()) {
            try(var statement=connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='catalog_automatic_repairs'");var rows=statement.executeQuery()) {
                if(!rows.next()) return java.util.List.of();
            }
            var out = new java.util.ArrayList<Round>();
            boolean hasState=false;try(var statement=connection.createStatement();var rows=statement.executeQuery("PRAGMA table_info(catalog_automatic_repairs)")){while(rows.next())hasState|="state".equals(rows.getString("name"));}
            try (var query = connection.prepareStatement("SELECT rejected_feed,rejection,changes_json,created_at,"+(hasState?"state":"'UNKNOWN'")+" FROM catalog_automatic_repairs WHERE scope_hash=? AND gtin=? AND good_id=? ORDER BY created_at,rejected_feed")) {
                query.setString(1,scopeKey);query.setString(2,GtinNormalizer.normalize(gtin));query.setLong(3,goodId);
                try (var rows=query.executeQuery()) { while(rows.next()) out.add(new Round(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5))); }
            }
            ListingIdentity.require(shop);scope.assertCurrent(shop);
            return java.util.List.copyOf(out);
        } catch(SQLException failure) { throw new IllegalStateException("CATALOG_REPAIR_HISTORY_UNAVAILABLE",failure); }
    }

    static boolean reserve(Connection connection, ZnackRegistrationScope scope, String gtin,
                           String rejectedFeed, CatalogAutomaticRepair.Proposal proposal) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (scope == null || proposal == null || proposal.goodId() <= 0 || rejectedFeed == null
                || rejectedFeed.isBlank() || rejectedFeed.length() > 200 || !RegisteredGtinValidator.isValid(gtin)
                || proposal.changes().isEmpty() || proposal.changes().size() > 20) return false;
        String scopeKey = com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal.allocationPool(scope.inn(),scope.environment());
        String code = GtinNormalizer.normalize(gtin);
        ensure(connection);
        String payloadHash=ListingIdentity.hash(proposal.payload().toString());
        String sourceHash=ListingIdentity.hash(proposal.originalPayload().toString());
        try (var query = connection.prepareStatement("SELECT COUNT(*) FROM catalog_automatic_repairs WHERE scope_hash=? AND gtin=? AND good_id=? AND state<>'PRE_SEND_FAILED'")) {
            query.setString(1, scopeKey); query.setString(2, code); query.setLong(3, proposal.goodId());
            try (var rows = query.executeQuery()) { if (!rows.next() || rows.getInt(1) >= LIMIT) return false; }
        }
        try(var previous=connection.prepareStatement("UPDATE catalog_automatic_repairs SET state='PLANNED' WHERE scope_hash=? AND gtin=? AND good_id=? AND rejected_feed=? AND payload_hash=? AND source_payload_hash=? AND state='PRE_SEND_FAILED'")){
            previous.setString(1,scopeKey);previous.setString(2,code);previous.setLong(3,proposal.goodId());previous.setString(4,rejectedFeed);previous.setString(5,payloadHash);previous.setString(6,sourceHash);
            if(previous.executeUpdate()==1)return true;
        }
        var changes = new JsonArray();
        for (var change : proposal.changes()) {
            var detail = new JsonObject();
            detail.addProperty("field", change.field()); detail.addProperty("before", change.before());
            detail.addProperty("after", change.after()); detail.addProperty("source", CatalogFeedback.display(change.source()));
            changes.add(detail);
        }
        try (var insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO catalog_automatic_repairs
                (scope_hash,gtin,good_id,rejected_feed,rejection,changes_json,payload_hash,created_at,source_payload_hash,state)
                VALUES(?,?,?,?,?,?,?,?,?,'PLANNED')
                """)) {
            insert.setString(1, scopeKey); insert.setString(2, code); insert.setLong(3, proposal.goodId());
            insert.setString(4, rejectedFeed); insert.setString(5, CatalogFeedback.display(proposal.rejection()));
            insert.setString(6, changes.toString()); insert.setString(7, payloadHash);
            insert.setString(8, Instant.now().toString());insert.setString(9,sourceHash); return insert.executeUpdate() == 1;
        }
    }

    private record Key(String scope,String gtin,long goodId,String payloadHash) { }
    private static Key key(ZnackRegistrationScope scope,JsonObject payload){
        if(scope==null||payload==null||!payload.has("gtin")||!payload.has("good_id"))return null;
        try{
            String gtin=payload.get("gtin").getAsString();long id=payload.get("good_id").getAsLong();
            if(id<=0||!RegisteredGtinValidator.isValid(gtin))return null;
            return new Key(com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal.allocationPool(scope.inn(),scope.environment()),GtinNormalizer.normalize(gtin),id,ListingIdentity.hash(payload.toString()));
        }catch(RuntimeException invalid){return null;}
    }
    private static boolean exists(Connection c)throws SQLException{
        try(var p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='catalog_automatic_repairs'");var r=p.executeQuery()){return r.next();}
    }
    /** Reserve the existing correction at the send boundary; never replay an uncertain send. */
    static boolean sending(com.vncode.app.models.Shop shop,ZnackRegistrationScope scope,JsonObject payload){
        var k=key(scope,payload);if(k==null)return false;ListingIdentity.require(shop);scope.assertCurrent(shop);
        try{return com.vncode.app.config.SqliteWriteTransaction.run(c->{
            if(!exists(c))return false;ensure(c);String state=null;int matches=0;
            try(var p=c.prepareStatement("SELECT state FROM catalog_automatic_repairs WHERE scope_hash=? AND gtin=? AND good_id=? AND payload_hash=?")){
                p.setString(1,k.scope());p.setString(2,k.gtin());p.setLong(3,k.goodId());p.setString(4,k.payloadHash());
                try(var r=p.executeQuery()){while(r.next()){state=r.getString(1);matches++;}}
            }
            if(matches==0)return false;if(matches!=1||!java.util.Set.of("PLANNED","PRE_SEND_FAILED","WAITING").contains(state))throw new IllegalStateException("CATALOG_REPAIR_READBACK_REQUIRED");
            if("PRE_SEND_FAILED".equals(state))try(var p=c.prepareStatement("SELECT COUNT(*) FROM catalog_automatic_repairs WHERE scope_hash=? AND gtin=? AND good_id=? AND state<>'PRE_SEND_FAILED'")){
                p.setString(1,k.scope());p.setString(2,k.gtin());p.setLong(3,k.goodId());try(var r=p.executeQuery()){if(!r.next()||r.getInt(1)>=LIMIT)throw new IllegalStateException("CATALOG_REPAIR_LIMIT_REACHED");}
            }
            try(var p=c.prepareStatement("UPDATE catalog_automatic_repairs SET state='SENDING' WHERE scope_hash=? AND gtin=? AND good_id=? AND payload_hash=? AND state IN ('PLANNED','PRE_SEND_FAILED','WAITING')")){
                p.setString(1,k.scope());p.setString(2,k.gtin());p.setLong(3,k.goodId());p.setString(4,k.payloadHash());if(p.executeUpdate()!=1)throw new IllegalStateException("CATALOG_REPAIR_READBACK_REQUIRED");
            }
            return true;
        });}catch(SQLException unavailable){throw new IllegalStateException("SYNC_STORAGE",unavailable);}
    }
    /** A known local validation failure has not consumed a send round. */
    static void preSendFailure(com.vncode.app.models.Shop shop,ZnackRegistrationScope scope,JsonObject payload,boolean ownsSending){
        transition(shop,scope,payload,"PRE_SEND_FAILED",false,ownsSending);
    }
    /** Caller owns the transaction rolling back the exact native pre-POST checkpoint. */
    static void releaseKnownUnsent(Connection c,ZnackRegistrationScope scope,JsonObject payload)throws SQLException {
        var k=key(scope,payload);if(k==null||!exists(c))return;
        ensure(c);
        try(var p=c.prepareStatement("UPDATE catalog_automatic_repairs SET state='PRE_SEND_FAILED' WHERE scope_hash=? AND gtin=? AND good_id=? AND payload_hash=? AND state='SENDING'")){
            p.setString(1,k.scope());p.setString(2,k.gtin());p.setLong(3,k.goodId());p.setString(4,k.payloadHash());p.executeUpdate();
        }
    }
    static void finished(com.vncode.app.models.Shop shop,ZnackRegistrationScope scope,JsonObject payload,String state){
        if(!java.util.Set.of("ACKNOWLEDGED","UNKNOWN","WAITING").contains(state))throw new IllegalArgumentException("invalid_repair_state");
        transition(shop,scope,payload,state,true,false);
    }
    private static void transition(com.vncode.app.models.Shop shop,ZnackRegistrationScope scope,JsonObject payload,String target,boolean sent,boolean ownsSending){
        var k=key(scope,payload);if(k==null)return;ListingIdentity.require(shop);scope.assertCurrent(shop);
        try{com.vncode.app.config.SqliteWriteTransaction.run(c->{
            if(!exists(c))return null;ensure(c);
            String allowed=sent||ownsSending?"state='SENDING'":"state IN ('PLANNED','WAITING')";
            try(var p=c.prepareStatement("UPDATE catalog_automatic_repairs SET state=? WHERE scope_hash=? AND gtin=? AND good_id=? AND payload_hash=? AND "+allowed)){
                p.setString(1,target);p.setString(2,k.scope());p.setString(3,k.gtin());p.setLong(4,k.goodId());p.setString(5,k.payloadHash());p.executeUpdate();
            }return null;
        });}catch(SQLException unavailable){throw new IllegalStateException("SYNC_STORAGE",unavailable);}
    }

    private static void ensure(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS catalog_automatic_repairs (
                  scope_hash TEXT NOT NULL, gtin TEXT NOT NULL, good_id INTEGER NOT NULL,
                  rejected_feed TEXT NOT NULL, rejection TEXT NOT NULL, changes_json TEXT NOT NULL,
                  payload_hash TEXT NOT NULL, created_at TEXT NOT NULL, source_payload_hash TEXT NOT NULL DEFAULT '', state TEXT NOT NULL DEFAULT 'PLANNED',
                  PRIMARY KEY(scope_hash, gtin, good_id, rejected_feed))
                """);
        }
        boolean found=false;try(var statement=connection.createStatement();var rows=statement.executeQuery("PRAGMA table_info(catalog_automatic_repairs)")){while(rows.next())found|="state".equals(rows.getString("name"));}
        // An old record has no dispatch receipt: do not assume it was unsent.
        if(!found)try(var statement=connection.createStatement()){statement.execute("ALTER TABLE catalog_automatic_repairs ADD COLUMN state TEXT NOT NULL DEFAULT 'UNKNOWN'");}
        boolean source=false;try(var statement=connection.createStatement();var rows=statement.executeQuery("PRAGMA table_info(catalog_automatic_repairs)")){while(rows.next())source|="source_payload_hash".equals(rows.getString("name"));}
        // Old entries have no provable source and cannot authorize automatic repair.
        if(!source)try(var statement=connection.createStatement()){statement.execute("ALTER TABLE catalog_automatic_repairs ADD COLUMN source_payload_hash TEXT NOT NULL DEFAULT ''");}
    }

    private CatalogAutomaticRepairLedger() { }
}
