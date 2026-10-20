package com.vncode.app.integration.znack.registration;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Frozen seller selection. This queue never deletes marketplace snapshots or Catalog identities. */
final class ZnackRegistrationBatchStore {
    private static final Gson JSON=new Gson();
    record Batch(String id,String owner,String scope,boolean autoSign,String state,String detail,Instant retryAt){}
    record Item(String run,int position,Sku sku,Draft draft,String state,String detail){}
    static void initialize(Connection c)throws SQLException {
        try(var s=c.createStatement()){
            s.execute("""
                CREATE TABLE IF NOT EXISTS znack_registration_batches(
                id TEXT PRIMARY KEY,shop_id INTEGER NOT NULL,owner TEXT NOT NULL,scope TEXT NOT NULL,
                auto_sign INTEGER NOT NULL,state TEXT NOT NULL,detail TEXT NOT NULL DEFAULT '',retry_at TEXT,
                created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
                FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE CASCADE)
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS znack_registration_batch_items(
                run_id TEXT NOT NULL,position INTEGER NOT NULL,sku_json TEXT NOT NULL,draft_json TEXT,
                state TEXT NOT NULL,detail TEXT NOT NULL DEFAULT '',feed_id TEXT NOT NULL DEFAULT '',
                updated_at TEXT NOT NULL,PRIMARY KEY(run_id,position),
                FOREIGN KEY(run_id) REFERENCES znack_registration_batches(id) ON DELETE CASCADE)
                """);
            s.execute("CREATE INDEX IF NOT EXISTS znack_registration_batches_shop ON znack_registration_batches(shop_id,owner,created_at)");
        }
    }
    String create(Shop shop,String run,List<Sku> rows,boolean autoSign,String scope){
        try{return SqliteWriteTransaction.run(c->{
            requireOwner(c,shop);
            try(var p=c.prepareStatement("SELECT id FROM znack_registration_batches WHERE shop_id=? AND owner=? AND state IN ('QUEUED','RUNNING','WAITING_NETWORK','WAITING_REMOTE') ORDER BY created_at DESC LIMIT 1")){
                p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));try(var r=p.executeQuery()){if(r.next())throw new IllegalStateException("ZNACK_REGISTRATION_BATCH_ALREADY_RUNNING");}
            }
            String now=Instant.now().toString();
            try(var p=c.prepareStatement("INSERT INTO znack_registration_batches(id,shop_id,owner,scope,auto_sign,state,created_at,updated_at) VALUES(?,?,?,?,?,'QUEUED',?,?)")){
                p.setString(1,run);p.setInt(2,shop.getId());p.setString(3,ListingIdentity.fingerprint(shop));p.setString(4,scope);p.setBoolean(5,autoSign);p.setString(6,now);p.setString(7,now);p.executeUpdate();
            }
            try(var p=c.prepareStatement("INSERT INTO znack_registration_batch_items(run_id,position,sku_json,state,updated_at) VALUES(?,?,?,'QUEUED',?)")){
                int position=0;for(var row:rows){p.setString(1,run);p.setInt(2,position++);p.setString(3,JSON.toJson(row));p.setString(4,now);p.addBatch();}p.executeBatch();
            }
            return run;
        });}catch(SQLException e){throw storage(e);}
    }
    Batch latest(Shop shop){
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT * FROM znack_registration_batches WHERE shop_id=? AND owner=? ORDER BY created_at DESC LIMIT 1")){
            requireOwner(c,shop);p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));try(var r=p.executeQuery()){return r.next()?batch(r):null;}
        }catch(SQLException e){throw storage(e);}
    }
    Batch get(Shop shop,String id){
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT * FROM znack_registration_batches WHERE id=? AND shop_id=? AND owner=?")){
            requireOwner(c,shop);p.setString(1,id);p.setInt(2,shop.getId());p.setString(3,ListingIdentity.fingerprint(shop));try(var r=p.executeQuery()){if(!r.next())throw new SecurityException("SYNC_OWNER_CHANGED");return batch(r);}
        }catch(SQLException e){throw storage(e);}
    }
    List<Item> items(Shop shop,String id){
        get(shop,id);var out=new ArrayList<Item>();
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT * FROM znack_registration_batch_items WHERE run_id=? ORDER BY position")){
            p.setString(1,id);try(var r=p.executeQuery()){while(r.next())out.add(new Item(id,r.getInt("position"),JSON.fromJson(r.getString("sku_json"),Sku.class),JSON.fromJson(r.getString("draft_json"),Draft.class),r.getString("state"),r.getString("detail")));}return List.copyOf(out);
        }catch(SQLException e){throw storage(e);}
    }
    record Summary(int total,int submitted,int failed,Sku current,Sku last){}
    Summary summary(Shop shop,String id){
        get(shop,id);
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);int total,submitted,failed;
            try(var p=c.prepareStatement("SELECT count(*),COALESCE(sum(state='SUBMITTED'),0),COALESCE(sum(state='NEEDS_ACTION'),0) FROM znack_registration_batch_items WHERE run_id=?")){
                p.setString(1,id);try(var r=p.executeQuery()){r.next();total=r.getInt(1);submitted=r.getInt(2);failed=r.getInt(3);}
            }
            Sku current=summarySku(c,id,false),last=summarySku(c,id,true);c.commit();return new Summary(total,submitted,failed,current,last);
        }catch(SQLException e){throw storage(e);}
    }
    private static Sku summarySku(Connection c,String id,boolean last)throws SQLException {
        String predicate=last?"state='SUBMITTED'":"state NOT IN ('SUBMITTED','NEEDS_ACTION')";
        try(var p=c.prepareStatement("SELECT sku_json FROM znack_registration_batch_items WHERE run_id=? AND "+predicate+" ORDER BY position "+(last?"DESC":"ASC")+" LIMIT 1")){
            p.setString(1,id);try(var r=p.executeQuery()){return r.next()?JSON.fromJson(r.getString(1),Sku.class):null;}
        }
    }
    void item(Shop shop,Item item,String state,String detail,Draft draft,String feed){
        write(shop,item.run(),c->{try(var p=c.prepareStatement("UPDATE znack_registration_batch_items SET state=?,detail=?,draft_json=COALESCE(?,draft_json),feed_id=CASE WHEN ?<>'' THEN ? ELSE feed_id END,updated_at=? WHERE run_id=? AND position=?")){
            p.setString(1,state);p.setString(2,safe(detail));p.setString(3,draft==null?null:JSON.toJson(draft));p.setString(4,text(feed));p.setString(5,text(feed));p.setString(6,Instant.now().toString());p.setString(7,item.run());p.setInt(8,item.position());p.executeUpdate();}return null;});
    }
    void state(Shop shop,String id,String state,String detail,Instant retry){
        write(shop,id,c->{try(var p=c.prepareStatement("UPDATE znack_registration_batches SET state=?,detail=?,retry_at=?,updated_at=? WHERE id=?")){
            p.setString(1,state);p.setString(2,safe(detail));p.setString(3,retry==null?null:retry.toString());p.setString(4,Instant.now().toString());p.setString(5,id);p.executeUpdate();}return null;});
    }
    private void write(Shop shop,String id,SqliteWriteTransaction.Work<Void> work){
        try{SqliteWriteTransaction.run(c->{requireOwner(c,shop);try(var p=c.prepareStatement("SELECT 1 FROM znack_registration_batches WHERE id=? AND shop_id=? AND owner=?")){p.setString(1,id);p.setInt(2,shop.getId());p.setString(3,ListingIdentity.fingerprint(shop));try(var r=p.executeQuery()){if(!r.next())throw new SecurityException("SYNC_OWNER_CHANGED");}}return work.run(c);});}catch(SQLException e){throw storage(e);}
    }
    /** CHECKING is strictly before allocation; uncertain allocating/dispatching states are never reset. */
    boolean recoverPreAllocation(Shop shop,Sku sku){
        try{return SqliteWriteTransaction.run(c->{requireOwner(c,shop);try(var p=c.prepareStatement("UPDATE znack_card_registrations SET status='PREFLIGHT_ERROR',error_message='ZNACK_PREALLOCATION_RESUMED',updated_at=? WHERE shop_id=? AND chrt_id=? AND nm_id=? AND status='CHECKING' AND NULLIF(gtin,'') IS NULL AND NULLIF(feed_id,'') IS NULL AND good_id IS NULL AND updated_at=?")){
            p.setString(1,Instant.now().toString());p.setInt(2,shop.getId());p.setLong(3,sku.chrtId());p.setLong(4,sku.nmId());p.setString(5,sku.registrationRevision());return p.executeUpdate()==1;}});
        }catch(SQLException e){throw storage(e);}
    }
    private static Batch batch(ResultSet r)throws SQLException {String retry=r.getString("retry_at");return new Batch(r.getString("id"),r.getString("owner"),r.getString("scope"),r.getBoolean("auto_sign"),r.getString("state"),r.getString("detail"),retry==null?null:Instant.parse(retry));}
    private static void requireOwner(Connection c,Shop shop)throws SQLException {if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new SecurityException("SYNC_OWNER_CHANGED");}
    private static String safe(String detail){return com.vncode.app.integration.znack.ZnackSanitizer.message(detail);}
    private static String text(String v){return Objects.requireNonNullElse(v,"");}
    private static IllegalStateException storage(SQLException e){return new IllegalStateException("SYNC_STORAGE",e);}
}
