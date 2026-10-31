package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.gtinsync.RegisteredGtinValidator;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Serialize Catalog writes for the same participant/environment/GTIN across WB and Ozon.
 * Native checkpoints remain the durable journal. A timeout or ambiguous old checkpoint never
 * grants a second write; only fresh exact-feed observations can authorize a cross-seller repair. */
final class CatalogSubmissionGuard {
    @FunctionalInterface interface FeedReader {
        /** Returns the monotonic timestamp of fresh owner evidence, or zero when unverified. */
        long terminal(List<String> feeds,String gtin,Long expectedGoodId)throws Exception;
    }
    private record Other(Marketplace marketplace,int shop,String product,String variant,String pool,
                         String status,String feed,Long good,String signature,String updated) {}
    private final Shop shop;private final ZnackRegistrationScope scope;private final ProductKey key;
    private final String gtin,pool,payload;private final Long goodId;private final List<Other> observed;
    private final long proofAt;private final boolean needsProof;

    private CatalogSubmissionGuard(Shop shop,ZnackRegistrationScope scope,ProductKey key,String gtin,
            String pool,String payload,Long goodId,List<Other> observed,long proofAt,boolean needsProof){
        this.shop=shop;this.scope=scope;this.key=key;this.gtin=gtin;this.pool=pool;
        this.payload=payload;this.goodId=goodId;this.observed=List.copyOf(observed);this.proofAt=proofAt;this.needsProof=needsProof;
    }
    static CatalogSubmissionGuard prepare(Shop shop,ZnackRegistrationScope scope,ProductKey key,JsonObject body,FeedReader reader)throws Exception {
        Objects.requireNonNull(reader);ListingIdentity.require(shop,key);scope.assertCurrent(shop);
        String code=text(body,"gtin");if(!RegisteredGtinValidator.isValid(code))throw new RegistrationValidationException("CATALOG_REPAIR_INVALID");
        Long id=goodId(body);String pool=RegistrationCommandJournal.allocationPool(scope.inn(),scope.environment());
        List<Other> rows;try(var c=Database.getConnection()){rows=others(c,key,code,pool);}
        var feeds=new LinkedHashSet<String>();
        for(var other:rows){
            validateWithoutNetwork(other,id);
            if(id!=null&&!other.feed().isBlank())feeds.add(other.feed());
        }
        long proofAt=feeds.isEmpty()?0:reader.terminal(List.copyOf(feeds),code,id);
        if(!feeds.isEmpty()&&proofAt==0)throw blocked();scope.assertCurrent(shop);
        return new CatalogSubmissionGuard(shop,scope,key,code,pool,body.toString(),id,rows,proofAt,!feeds.isEmpty());
    }
    static CatalogSubmissionGuard prepareSigning(Shop shop,ZnackRegistrationScope scope,ProductKey key,
            String gtin,long goodId,FeedReader reader)throws Exception {
        var identity=new JsonObject();identity.addProperty("gtin",gtin);identity.addProperty("good_id",goodId);
        return prepare(shop,scope,key,identity,reader);
    }
    /** Called after the Catalog service's own fresh owner/GTIN/good_id validation and directly
     * before the send. A single SQLite writer decides which marketplace may send first. */
    void reserve() {
        scope.assertCurrent(shop);
        if(needsProof&&(System.nanoTime()-proofAt<0||System.nanoTime()-proofAt>java.util.concurrent.TimeUnit.MINUTES.toNanos(1)))throw blocked();
        try{SqliteWriteTransaction.run(c->{
            ListingIdentity.require(shop,key);
            if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new ZnackRegistrationScope.ScopeChangedException();
            scope.assertCurrent(shop);
            var now=others(c,key,gtin,pool);if(!now.equals(observed))throw blocked();
            for(var other:now)validateWithoutNetwork(other,goodId);
            String sql=key.marketplace()==Marketplace.OZON
                    ?"UPDATE ozon_card_registrations SET status='FEED_SUBMITTING',updated_at=? WHERE shop_id=? AND product_id=? AND offer_id=? AND status='READY_TO_SUBMIT' AND gtin=? AND payload_json=? AND scope_json=? AND owner=?"
                    :"UPDATE znack_card_registrations SET status='FEED_SUBMITTING',updated_at=? WHERE shop_id=? AND nm_id=? AND chrt_id=? AND status='READY_TO_SUBMIT' AND gtin=? AND payload_json=? AND EXISTS (SELECT 1 FROM znack_card_registration_scopes s WHERE s.shop_id=znack_card_registrations.shop_id AND s.chrt_id=znack_card_registrations.chrt_id AND s.participant_inn=? AND s.environment=? AND s.fingerprint=? AND s.owner_hash=?)";
            try(var p=c.prepareStatement(sql)){
                p.setString(1,Instant.now().toString());p.setInt(2,key.shopId());p.setString(3,key.cardId());p.setString(4,key.variantId());p.setString(5,gtin);p.setString(6,payload);
                if(key.marketplace()==Marketplace.OZON){p.setString(7,new com.google.gson.Gson().toJson(scope));p.setString(8,ListingIdentity.fingerprint(shop));}
                else{p.setString(7,scope.inn());p.setString(8,scope.environment());p.setString(9,scope.fingerprint());p.setString(10,ListingIdentity.fingerprint(shop));}
                if(p.executeUpdate()!=1)throw new IllegalStateException("REGISTRATION_CHANGED");
            }return null;
        });}catch(SQLException failure){throw new IllegalStateException("REGISTRATION_STORAGE",failure);}
    }
    /** Claim the frozen signing document under the same cross-marketplace SQLite lock as POST.
     * A competing feed/signature, changed scope or stale feed evidence prevents signing. */
    boolean reserveSigning(String documentHash) {
        if(documentHash==null||!documentHash.matches("[0-9a-f]{64}")||goodId==null)throw new IllegalArgumentException("SIGNING_DOCUMENT_CHANGED");
        scope.assertCurrent(shop);
        if(needsProof&&(System.nanoTime()-proofAt<0||System.nanoTime()-proofAt>java.util.concurrent.TimeUnit.MINUTES.toNanos(1)))throw blocked();
        try{return SqliteWriteTransaction.run(c->{
            ListingIdentity.require(shop,key);
            if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new ZnackRegistrationScope.ScopeChangedException();
            scope.assertCurrent(shop);
            var now=others(c,key,gtin,pool);if(!now.equals(observed))throw blocked();
            for(var other:now)validateWithoutNetwork(other,goodId);
            String sql=key.marketplace()==Marketplace.OZON
                    ?"UPDATE ozon_card_registrations SET status='SIGNING',signature_state='SENDING',updated_at=? WHERE shop_id=? AND product_id=? AND offer_id=? AND status='READY_TO_SIGN' AND signature_state='PREPARED' AND signature_sha256=? AND gtin=? AND good_id=? AND scope_json=? AND owner=?"
                    :"UPDATE znack_card_registration_signatures SET state='SENDING',updated_at=? WHERE shop_id=? AND chrt_id=? AND document_sha256=? AND state='PREPARED' AND gtin=? AND good_id=? AND EXISTS (SELECT 1 FROM znack_card_registrations r JOIN znack_card_registration_scopes s ON s.shop_id=r.shop_id AND s.chrt_id=r.chrt_id WHERE r.shop_id=znack_card_registration_signatures.shop_id AND r.chrt_id=znack_card_registration_signatures.chrt_id AND r.nm_id=? AND r.status='READY_TO_SIGN' AND r.gtin=? AND r.good_id=? AND s.participant_inn=? AND s.environment=? AND s.fingerprint=? AND s.owner_hash=?)";
            try(var p=c.prepareStatement(sql)){
                p.setString(1,Instant.now().toString());p.setInt(2,key.shopId());
                if(key.marketplace()==Marketplace.OZON){
                    p.setString(3,key.cardId());p.setString(4,key.variantId());p.setString(5,documentHash);p.setString(6,gtin);p.setLong(7,goodId);
                    p.setString(8,new com.google.gson.Gson().toJson(scope));p.setString(9,ListingIdentity.fingerprint(shop));
                }else{
                    p.setString(3,key.variantId());p.setString(4,documentHash);p.setString(5,gtin);p.setLong(6,goodId);
                    p.setString(7,key.cardId());p.setString(8,gtin);p.setLong(9,goodId);
                    p.setString(10,scope.inn());p.setString(11,scope.environment());p.setString(12,scope.fingerprint());p.setString(13,ListingIdentity.fingerprint(shop));
                }
                return p.executeUpdate()==1;
            }
        });}catch(SQLException failure){throw new IllegalStateException("REGISTRATION_STORAGE",failure);}
    }
    private static void validateWithoutNetwork(Other row,Long updateId){
        if(Set.of("FEED_SUBMITTING","SIGNING").contains(row.status())
                ||Set.of("SENDING","RECONCILE_REQUIRED").contains(row.signature()))throw blocked();
        if(Set.of("FEED_REVIEW_REQUIRED","FEED_SUBMITTED").contains(row.status())
                &&(updateId==null||row.feed().isBlank()))throw blocked();
        if(updateId==null){
            // A different SKU already has a card/feed: a create must link/observe it, not create again.
            if(row.good()!=null||!row.feed().isBlank()||!Set.of("READY_TO_SUBMIT","GTIN_GENERATED","PRE_SUBMIT_ERROR").contains(row.status()))throw blocked();
        }else{
            if(row.good()!=null&&!updateId.equals(row.good()))throw blocked();
            if(row.feed().isBlank()&&row.good()==null&&!Set.of("READY_TO_SUBMIT","GTIN_GENERATED","PRE_SUBMIT_ERROR").contains(row.status()))throw blocked();
        }
    }
    private static List<Other> others(Connection c,ProductKey own,String gtin,String pool)throws SQLException {
        var result=new ArrayList<Other>();String canonical=RegisteredGtinProof.canonicalCode(gtin);
        try(var p=c.prepareStatement("""
            SELECT r.shop_id,r.nm_id,r.chrt_id,s.participant_inn,s.environment,r.status,
                   COALESCE(r.feed_id,''),r.good_id,COALESCE(sig.state,''),r.updated_at
              FROM znack_card_registrations r LEFT JOIN znack_card_registration_scopes s
                ON s.shop_id=r.shop_id AND s.chrt_id=r.chrt_id
              LEFT JOIN znack_card_registration_signatures sig ON sig.shop_id=r.shop_id AND sig.chrt_id=r.chrt_id
             WHERE r.gtin IS NOT NULL AND substr('00000000000000'||trim(r.gtin),-14)=?
            """)){
            p.setString(1,canonical);try(var r=p.executeQuery()){while(r.next()){
                String product=Long.toString(r.getLong(2)),variant=Long.toString(r.getLong(3));int shop=r.getInt(1);
                if(own.marketplace()==Marketplace.WILDBERRIES&&own.shopId()==shop&&own.cardId().equals(product)&&own.variantId().equals(variant))continue;
                String otherPool=poolOrUnknown(r.getString(4),r.getString(5));if(!otherPool.isEmpty()&&!pool.equals(otherPool))continue;
                long good=r.getLong(8);Long id=r.wasNull()?null:good;
                result.add(new Other(Marketplace.WILDBERRIES,shop,product,variant,otherPool,r.getString(6),safe(r.getString(7)),id,safe(r.getString(9)),safe(r.getString(10))));
            }}
        }
        try(var p=c.prepareStatement("""
            SELECT shop_id,product_id,offer_id,scope_json,status,feed_id,good_id,signature_state,updated_at
              FROM ozon_card_registrations WHERE gtin<>'' AND substr('00000000000000'||trim(gtin),-14)=?
            """)){
            p.setString(1,canonical);try(var r=p.executeQuery()){while(r.next()){
                int shop=r.getInt(1);String product=r.getString(2),variant=r.getString(3);
                if(own.marketplace()==Marketplace.OZON&&own.shopId()==shop&&own.cardId().equals(product)&&own.variantId().equals(variant))continue;
                String otherPool="";try{var j=JsonParser.parseString(r.getString(4)).getAsJsonObject();otherPool=poolOrUnknown(text(j,"inn"),text(j,"environment"));}catch(RuntimeException ignored){}
                if(!otherPool.isEmpty()&&!pool.equals(otherPool))continue;
                long good=r.getLong(7);Long id=r.wasNull()?null:good;
                result.add(new Other(Marketplace.OZON,shop,product,variant,otherPool,r.getString(5),safe(r.getString(6)),id,safe(r.getString(8)),safe(r.getString(9))));
            }}
        }
        // Older history tables did not bind participant or environment. Never assume that an
        // archived feed belongs to a different account; a fresh exact-feed read can resolve it.
        try(var p=c.prepareStatement("""
            SELECT shop_id,nm_id,chrt_id,COALESCE(feed_id,''),good_id,archived_at
              FROM znack_card_registration_history WHERE gtin<>'' AND substr('00000000000000'||trim(gtin),-14)=?
            """)){
            p.setString(1,canonical);try(var r=p.executeQuery()){while(r.next()){
                int shop=r.getInt(1);String product=Long.toString(r.getLong(2)),variant=Long.toString(r.getLong(3));
                if(own.marketplace()==Marketplace.WILDBERRIES&&own.shopId()==shop&&own.cardId().equals(product)&&own.variantId().equals(variant))continue;
                long good=r.getLong(5);Long id=r.wasNull()?null:good;
                result.add(new Other(Marketplace.WILDBERRIES,shop,product,variant,"","HISTORY",safe(r.getString(4)),id,"",safe(r.getString(6))));
            }}
        }
        try(var p=c.prepareStatement("""
            SELECT shop_id,product_id,offer_id,feed_id,good_id,created_at
              FROM ozon_card_registration_history WHERE gtin<>'' AND substr('00000000000000'||trim(gtin),-14)=?
            """)){
            p.setString(1,canonical);try(var r=p.executeQuery()){while(r.next()){
                int shop=r.getInt(1);String product=r.getString(2),variant=r.getString(3);
                if(own.marketplace()==Marketplace.OZON&&own.shopId()==shop&&own.cardId().equals(product)&&own.variantId().equals(variant))continue;
                long good=r.getLong(5);Long id=r.wasNull()?null:good;
                result.add(new Other(Marketplace.OZON,shop,product,variant,"","HISTORY",safe(r.getString(4)),id,"",safe(r.getString(6))));
            }}
        }
        result.sort(Comparator.comparing((Other o)->o.marketplace().name()).thenComparingInt(Other::shop).thenComparing(Other::product).thenComparing(Other::variant));
        return List.copyOf(result);
    }
    private static String poolOrUnknown(String inn,String env){try{return RegistrationCommandJournal.allocationPool(inn,env);}catch(RuntimeException invalid){return "";}}
    private static Long goodId(JsonObject body){
        if(!body.has("good_id")||body.get("good_id").isJsonNull())return null;
        try{long value=body.get("good_id").getAsLong();if(value>0)return value;}catch(RuntimeException ignored){}
        throw new RegistrationValidationException("CATALOG_REPAIR_INVALID");
    }
    private static String text(JsonObject o,String key){try{return o.has(key)&&o.get(key).isJsonPrimitive()?o.get(key).getAsString():"";}catch(RuntimeException invalid){return "";}}
    private static String safe(String text){return text==null?"":text;}
    private static IllegalStateException blocked(){return new IllegalStateException("CATALOG_FEED_READBACK_REQUIRED");}
}
