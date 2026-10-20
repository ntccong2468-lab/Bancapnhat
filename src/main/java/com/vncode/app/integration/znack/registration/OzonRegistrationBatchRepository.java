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
    public record Batch(String id,int shopId,String owner,String scope,boolean autoSign,String state,String detail) {}
    public record Item(String run,int position,ProductKey key,String state,String detail) {}
    public record Counts(int total,int submitted,int failed,int remaining) {}
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
    }
    public String create(Shop shop,List<ProductKey> keys,boolean autoSign,ZnackRegistrationScope scope) {
        return create(shop,keys,autoSign,scope,UUID.randomUUID().toString());
    }
    public String create(Shop shop,List<ProductKey> keys,boolean autoSign,ZnackRegistrationScope scope,String run) {
        ListingIdentity.require(shop);scope.assertCurrent(shop);
        var frozen=List.copyOf(new LinkedHashSet<>(keys));
        if(frozen.isEmpty()||frozen.size()>100000||frozen.stream().anyMatch(k->k.shopId()!=shop.getId()||k.marketplace()!=Marketplace.OZON))throw new IllegalArgumentException("invalid_selection");
        return write(c->{initialize(c);var old=latest(c,shop.getId());if(old!=null&&!Set.of("COMPLETED","FAILED").contains(old.state()))return old.id();
            String id=run,now=Instant.now().toString();
            try(var p=c.prepareStatement("INSERT INTO ozon_registration_batches VALUES(?,?,?,?,?,'QUEUED','',?,?)")){p.setString(1,id);p.setInt(2,shop.getId());p.setString(3,ListingIdentity.fingerprint(shop));p.setString(4,new Gson().toJson(scope));p.setBoolean(5,autoSign);p.setString(6,now);p.setString(7,now);p.executeUpdate();}
            try(var p=c.prepareStatement("INSERT INTO ozon_registration_batch_items(run_id,position,product_id,offer_id) VALUES(?,?,?,?)")){int n=0;for(var key:frozen){p.setString(1,id);p.setInt(2,n++);p.setString(3,key.cardId());p.setString(4,key.variantId());p.addBatch();if(n%500==0)p.executeBatch();}p.executeBatch();}
            return id;});
    }
    public Batch latest(Shop shop){return read(c->latest(c,shop.getId()));}
    public Item next(Batch batch){return read(c->{try(var p=c.prepareStatement("SELECT * FROM ozon_registration_batch_items WHERE run_id=? AND state IN ('PENDING','RUNNING') ORDER BY position LIMIT 1")){p.setString(1,batch.id());try(var r=p.executeQuery()){return r.next()?new Item(batch.id(),r.getInt("position"),new ProductKey(batch.shopId(),Marketplace.OZON,r.getString("product_id"),r.getString("offer_id")),r.getString("state"),r.getString("detail")):null;}}});}
    public Item lastSubmitted(Batch batch){return read(c->{try(var p=c.prepareStatement("SELECT * FROM ozon_registration_batch_items WHERE run_id=? AND state='SUBMITTED' ORDER BY position DESC LIMIT 1")){p.setString(1,batch.id());try(var r=p.executeQuery()){return r.next()?new Item(batch.id(),r.getInt("position"),new ProductKey(batch.shopId(),Marketplace.OZON,r.getString("product_id"),r.getString("offer_id")),r.getString("state"),r.getString("detail")):null;}}});}
    public Counts counts(String run){return read(c->{try(var p=c.prepareStatement("SELECT count(*),sum(state='SUBMITTED'),sum(state='FAILED'),sum(state IN ('PENDING','RUNNING')) FROM ozon_registration_batch_items WHERE run_id=?")){p.setString(1,run);try(var r=p.executeQuery()){r.next();return new Counts(r.getInt(1),r.getInt(2),r.getInt(3),r.getInt(4));}}});}
    public void item(Item item,String state,String detail){write(c->{try(var p=c.prepareStatement("UPDATE ozon_registration_batch_items SET state=?,detail=? WHERE run_id=? AND position=?")){p.setString(1,state);p.setString(2,safe(detail));p.setString(3,item.run());p.setInt(4,item.position());p.executeUpdate();}return null;});}
    public void state(String run,String state,String detail){write(c->{try(var p=c.prepareStatement("UPDATE ozon_registration_batches SET state=?,detail=?,updated_at=? WHERE run_id=?")){p.setString(1,state);p.setString(2,safe(detail));p.setString(3,Instant.now().toString());p.setString(4,run);p.executeUpdate();}return null;});}
    public void failPending(String run,String detail){write(c->{try(var p=c.prepareStatement("UPDATE ozon_registration_batch_items SET state='FAILED',detail=? WHERE run_id=? AND state IN ('PENDING','RUNNING')")){p.setString(1,safe(detail));p.setString(2,run);p.executeUpdate();}return null;});state(run,"FAILED",detail);}
    private static Batch latest(Connection c,int shop)throws SQLException {try(var p=c.prepareStatement("SELECT * FROM ozon_registration_batches WHERE shop_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1")){p.setInt(1,shop);try(var r=p.executeQuery()){return r.next()?new Batch(r.getString("run_id"),shop,r.getString("owner"),r.getString("scope_json"),r.getBoolean("auto_sign"),r.getString("state"),r.getString("detail")):null;}}}
    private static String safe(String s){return s==null?"":s.substring(0,Math.min(2000,s.length()));}
    private static <T>T write(SqliteWriteTransaction.Work<T> work){try{return SqliteWriteTransaction.run(work);}catch(SQLException e){throw new IllegalStateException("ozon_registration_batch_storage_error",e);}}
    private static <T>T read(SqliteWriteTransaction.Work<T> work){try(var c=Database.getConnection()){return work.run(c);}catch(SQLException e){throw new IllegalStateException("ozon_registration_batch_storage_error",e);}}
}
