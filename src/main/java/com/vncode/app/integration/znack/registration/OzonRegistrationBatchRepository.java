package com.vncode.app.integration.znack.registration;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** A frozen selection, not an in-memory chain owned by a registration window. */
public final class OzonRegistrationBatchRepository {
    public record Batch(String id,int shopId,String owner,String scope,boolean autoSign,String state,String detail,int retryCount,Instant nextRetryAt) {}
    public record Item(String run,int position,ProductKey key,String state,String detail) {}
    public record Counts(int total,int submitted,int failed,int remaining,int cancelled) {}
    public static void initialize(Connection c)throws SQLException {
        try(var s=c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS ozon_registration_batches(
                run_id TEXT PRIMARY KEY,shop_id INTEGER NOT NULL,owner TEXT NOT NULL,scope_json TEXT NOT NULL,
                auto_sign INTEGER NOT NULL,state TEXT NOT NULL,detail TEXT NOT NULL DEFAULT '',created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS ozon_registration_batch_items(
                run_id TEXT NOT NULL,position INTEGER NOT NULL,product_id TEXT NOT NULL,offer_id TEXT NOT NULL,
                state TEXT NOT NULL DEFAULT 'PENDING',detail TEXT NOT NULL DEFAULT '',PRIMARY KEY(run_id,position),
                UNIQUE(run_id,product_id,offer_id),FOREIGN KEY(run_id) REFERENCES ozon_registration_batches(run_id) ON DELETE CASCADE)
                """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_ozon_registration_batches_shop ON ozon_registration_batches(shop_id,created_at)");
        }
        ensureColumn(c,"retry_count","INTEGER NOT NULL DEFAULT 0");ensureColumn(c,"next_retry_at","TEXT");
    }
    private static void ensureColumn(Connection c,String name,String definition)throws SQLException{boolean found=false;try(var p=c.createStatement();var rows=p.executeQuery("PRAGMA table_info(ozon_registration_batches)")){while(rows.next())found|=name.equals(rows.getString("name"));}if(!found)try(var p=c.createStatement()){p.execute("ALTER TABLE ozon_registration_batches ADD COLUMN "+name+" "+definition);}}
    public String create(Shop shop,List<ProductKey> keys,boolean autoSign,ZnackRegistrationScope scope) {
        return create(shop,keys,autoSign,scope,UUID.randomUUID().toString());
    }
    public String create(Shop shop,List<ProductKey> keys,boolean autoSign,ZnackRegistrationScope scope,String run) {
        ListingIdentity.require(shop);scope.assertCurrent(shop);
        var frozen=List.copyOf(new LinkedHashSet<>(keys));
        if(frozen.isEmpty()||frozen.size()>100000||frozen.stream().anyMatch(k->k.shopId()!=shop.getId()||k.marketplace()!=Marketplace.OZON))throw new IllegalArgumentException("invalid_selection");
        return write(c->{initialize(c);var old=latest(c,shop.getId());if(old!=null&&!Set.of("COMPLETED","FAILED","CANCELLED").contains(old.state()))return old.id();
            String id=run,now=Instant.now().toString();
            try(var p=c.prepareStatement("INSERT INTO ozon_registration_batches(run_id,shop_id,owner,scope_json,auto_sign,state,detail,created_at,updated_at) VALUES(?,?,?,?,?,'QUEUED','',?,?)")){p.setString(1,id);p.setInt(2,shop.getId());p.setString(3,ListingIdentity.fingerprint(shop));p.setString(4,new Gson().toJson(scope));p.setBoolean(5,autoSign);p.setString(6,now);p.setString(7,now);p.executeUpdate();}
            try(var p=c.prepareStatement("INSERT INTO ozon_registration_batch_items(run_id,position,product_id,offer_id) VALUES(?,?,?,?)")){int n=0;for(var key:frozen){p.setString(1,id);p.setInt(2,n++);p.setString(3,key.cardId());p.setString(4,key.variantId());p.addBatch();if(n%500==0)p.executeBatch();}p.executeBatch();}
            return id;});
    }
    /** The exact frozen native product belongs to an unfinished submission batch. */
    public boolean ownsPending(Shop shop,ProductKey key){
        if(shop==null||key==null||shop.getMarketplace()!=Marketplace.OZON||key.marketplace()!=Marketplace.OZON||key.shopId()!=shop.getId())return false;
        return read(c->{try(var p=c.prepareStatement("SELECT 1 FROM ozon_registration_batch_items i JOIN ozon_registration_batches b ON b.run_id=i.run_id WHERE b.shop_id=? AND b.owner=? AND b.state IN ('QUEUED','RUNNING','WAITING','WAITING_REMOTE','PAUSED','CANCELLED') AND i.state IN ('PENDING','RUNNING','WAITING_REMOTE','CANCELLED') AND i.product_id=? AND i.offer_id=? AND NOT EXISTS(SELECT 1 FROM ozon_registration_batch_items newer JOIN ozon_registration_batches n ON n.run_id=newer.run_id WHERE n.shop_id=b.shop_id AND n.rowid>b.rowid AND newer.product_id=i.product_id AND newer.offer_id=i.offer_id) LIMIT 1")){
            p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,key.cardId());p.setString(4,key.variantId());try(var r=p.executeQuery()){return r.next();}
        }});
    }
    public Batch latest(Shop shop){return read(c->latest(c,shop.getId()));}
    public boolean blocksSigning(Shop shop,ProductKey key){
        ListingIdentity.require(shop,key);
        return read(c->{try(var p=c.prepareStatement("SELECT 1 FROM ozon_registration_batch_items i JOIN ozon_registration_batches b ON b.run_id=i.run_id WHERE b.shop_id=? AND b.owner=? AND b.state IN ('PAUSED','CANCELLED') AND i.product_id=? AND i.offer_id=? AND NOT EXISTS(SELECT 1 FROM ozon_registration_batch_items newer JOIN ozon_registration_batches n ON n.run_id=newer.run_id WHERE n.shop_id=b.shop_id AND n.rowid>b.rowid AND newer.product_id=i.product_id AND newer.offer_id=i.offer_id) LIMIT 1")){p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,key.cardId());p.setString(4,key.variantId());try(var r=p.executeQuery()){return r.next();}}});
    }
    public Item next(Batch batch){return read(c->{try(var p=c.prepareStatement("SELECT * FROM ozon_registration_batch_items WHERE run_id=? AND state IN ('PENDING','RUNNING') ORDER BY position LIMIT 1")){p.setString(1,batch.id());try(var r=p.executeQuery()){return r.next()?new Item(batch.id(),r.getInt("position"),new ProductKey(batch.shopId(),Marketplace.OZON,r.getString("product_id"),r.getString("offer_id")),r.getString("state"),r.getString("detail")):null;}}});}
    public List<Item> waitingReadback(Batch batch){return read(c->{var items=new ArrayList<Item>();try(var p=c.prepareStatement("SELECT * FROM ozon_registration_batch_items WHERE run_id=? AND state='WAITING_REMOTE' ORDER BY position")){p.setString(1,batch.id());try(var r=p.executeQuery()){while(r.next())items.add(new Item(batch.id(),r.getInt("position"),new ProductKey(batch.shopId(),Marketplace.OZON,r.getString("product_id"),r.getString("offer_id")),r.getString("state"),r.getString("detail")));}}return List.copyOf(items);});}
    public Item lastSubmitted(Batch batch){return read(c->{try(var p=c.prepareStatement("SELECT * FROM ozon_registration_batch_items WHERE run_id=? AND state='SUBMITTED' ORDER BY position DESC LIMIT 1")){p.setString(1,batch.id());try(var r=p.executeQuery()){return r.next()?new Item(batch.id(),r.getInt("position"),new ProductKey(batch.shopId(),Marketplace.OZON,r.getString("product_id"),r.getString("offer_id")),r.getString("state"),r.getString("detail")):null;}}});}
    public Counts counts(String run){return read(c->{try(var p=c.prepareStatement("SELECT count(*),sum(state='SUBMITTED'),sum(state='FAILED'),sum(state IN ('PENDING','RUNNING','WAITING_REMOTE')),sum(state='CANCELLED') FROM ozon_registration_batch_items WHERE run_id=?")){p.setString(1,run);try(var r=p.executeQuery()){r.next();return new Counts(r.getInt(1),r.getInt(2),r.getInt(3),r.getInt(4),r.getInt(5));}}});}
    public void item(Item item,String state,String detail){write(c->{try(var p=c.prepareStatement("UPDATE ozon_registration_batch_items SET state=?,detail=? WHERE run_id=? AND position=?")){p.setString(1,state);p.setString(2,safe(detail));p.setString(3,item.run());p.setInt(4,item.position());p.executeUpdate();}if(Set.of("SUBMITTED","FAILED").contains(state))try(var p=c.prepareStatement("UPDATE ozon_registration_batches SET retry_count=0,next_retry_at=NULL WHERE run_id=? AND state NOT IN ('PAUSED','CANCELLED')")){p.setString(1,item.run());p.executeUpdate();}return null;});}
    public void state(String run,String state,String detail){write(c->{try(var p=c.prepareStatement("UPDATE ozon_registration_batches SET state=?,detail=?,next_retry_at=NULL,updated_at=? WHERE run_id=? AND state NOT IN ('PAUSED','CANCELLED')")){p.setString(1,state);p.setString(2,safe(detail));p.setString(3,Instant.now().toString());p.setString(4,run);p.executeUpdate();}return null;});}
    /** Claim is atomic with the durable control state; a cancelled PENDING row cannot start later. */
    public boolean begin(Item item){return write(c->{try(var p=c.prepareStatement("UPDATE ozon_registration_batch_items SET state='RUNNING',detail='' WHERE run_id=? AND position=? AND state IN ('PENDING','RUNNING') AND EXISTS(SELECT 1 FROM ozon_registration_batches b WHERE b.run_id=ozon_registration_batch_items.run_id AND b.state NOT IN ('PAUSED','CANCELLED','COMPLETED','FAILED'))")){p.setString(1,item.run());p.setInt(2,item.position());return p.executeUpdate()==1;}});}
    public boolean control(Shop shop,String next){
        if(!Set.of("PAUSED","CANCELLED","QUEUED").contains(next))throw new IllegalArgumentException("invalid_batch_control");
        ListingIdentity.require(shop);
        return write(c->{var batch=latest(c,shop.getId());if(batch==null||!batch.owner().equals(ListingIdentity.fingerprint(shop)))return false;
            var scope=new Gson().fromJson(batch.scope(),ZnackRegistrationScope.class);scope.assertCurrent(shop);
            if(next.equals("QUEUED")?!batch.state().equals("PAUSED"):Set.of("COMPLETED","FAILED","CANCELLED").contains(batch.state()))return false;
            try(var p=c.prepareStatement("UPDATE ozon_registration_batches SET state=?,detail=?,retry_count=CASE WHEN ?='QUEUED' THEN 0 ELSE retry_count END,next_retry_at=NULL,updated_at=? WHERE run_id=?")){p.setString(1,next);p.setString(2,next.equals("QUEUED")?"":next.equals("PAUSED")?"REGISTRATION_BATCH_PAUSED":"REGISTRATION_BATCH_CANCELLED");p.setString(3,next);p.setString(4,Instant.now().toString());p.setString(5,batch.id());p.executeUpdate();}
            if(next.equals("CANCELLED"))try(var p=c.prepareStatement("UPDATE ozon_registration_batch_items SET state='CANCELLED',detail='REGISTRATION_BATCH_CANCELLED' WHERE run_id=? AND state='PENDING' AND NOT EXISTS(SELECT 1 FROM ozon_card_registrations r WHERE r.shop_id=? AND r.product_id=ozon_registration_batch_items.product_id AND r.offer_id=ozon_registration_batch_items.offer_id AND (r.feed_id<>'' OR r.good_id IS NOT NULL OR r.status IN ('ALLOCATING_GTIN','FEED_SUBMITTING','GTIN_REVIEW_REQUIRED','FEED_REVIEW_REQUIRED')))")){p.setString(1,batch.id());p.setInt(2,shop.getId());p.executeUpdate();}
            return true;
        });
    }
    /** Retry reads/known-unsent work only; after the bound a seller explicitly resumes the same intent. */
    public boolean defer(String run,String detail,long seconds,boolean countRetry,int limit){return write(c->{
        try(var q=c.prepareStatement("SELECT state,retry_count FROM ozon_registration_batches WHERE run_id=?")){q.setString(1,run);try(var r=q.executeQuery()){
            if(!r.next()||Set.of("PAUSED","CANCELLED","COMPLETED","FAILED").contains(r.getString(1)))return false;
            int retries=r.getInt(2)+(countRetry?1:0);boolean paused=countRetry&&retries>=limit;String state=paused?"PAUSED":Set.of("CATALOG_REQUEST_ACCEPTED_CARD_PENDING","CATALOG_READBACK_RETRY_PENDING").contains(detail)?"WAITING_REMOTE":"WAITING";
            try(var p=c.prepareStatement("UPDATE ozon_registration_batches SET state=?,detail=?,retry_count=?,next_retry_at=?,updated_at=? WHERE run_id=?")){p.setString(1,state);p.setString(2,safe(paused?"REGISTRATION_BATCH_RETRY_LIMIT: "+detail:detail));p.setInt(3,retries);p.setString(4,paused?null:Instant.now().plusSeconds(Math.max(1,seconds)).toString());p.setString(5,Instant.now().toString());p.setString(6,run);p.executeUpdate();}return !paused;
        }}
    });}
    public void failPending(String run,String detail){write(c->{try(var p=c.prepareStatement("UPDATE ozon_registration_batch_items SET state='FAILED',detail=? WHERE run_id=? AND state IN ('PENDING','RUNNING','WAITING_REMOTE')")){p.setString(1,safe(detail));p.setString(2,run);p.executeUpdate();}return null;});state(run,"FAILED",detail);}
    private static Batch latest(Connection c,int shop)throws SQLException {try(var p=c.prepareStatement("SELECT * FROM ozon_registration_batches WHERE shop_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1")){p.setInt(1,shop);try(var r=p.executeQuery()){return r.next()?new Batch(r.getString("run_id"),shop,r.getString("owner"),r.getString("scope_json"),r.getBoolean("auto_sign"),r.getString("state"),r.getString("detail"),r.getInt("retry_count"),r.getString("next_retry_at")==null?null:Instant.parse(r.getString("next_retry_at"))):null;}}}
    private static String safe(String s){return s==null?"":s.substring(0,Math.min(2000,s.length()));}
    private static <T>T write(SqliteWriteTransaction.Work<T> work){try{return SqliteWriteTransaction.run(work);}catch(SQLException e){throw new IllegalStateException("ozon_registration_batch_storage_error",e);}}
    private static <T>T read(SqliteWriteTransaction.Work<T> work){try(var c=Database.getConnection()){return work.run(c);}catch(SQLException e){throw new IllegalStateException("ozon_registration_batch_storage_error",e);}}
}
