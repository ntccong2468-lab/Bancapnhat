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
    record Batch(String id,String owner,String scope,boolean autoSign,String state,String detail,Instant retryAt,int retries,Instant waitingSince){}
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
        ensureColumn(c,"retry_count","INTEGER NOT NULL DEFAULT 0");
        ensureColumn(c,"waiting_since","TEXT");
        ensureColumn(c,"correction_resumed","INTEGER NOT NULL DEFAULT 0");
        boolean checkedFeed=false;
        try(var s=c.createStatement();var rows=s.executeQuery("PRAGMA table_info(znack_registration_batch_items)")){while(rows.next())checkedFeed|="correction_checked_feed".equals(rows.getString("name"));}
        if(!checkedFeed)try(var s=c.createStatement()){
            s.execute("ALTER TABLE znack_registration_batch_items ADD COLUMN correction_checked_feed TEXT NOT NULL DEFAULT ''");
            // Old releases only recorded one batch-wide boolean. A newer feed is
            // recognizable when the previous rejected receipt was archived for this
            // same card after this batch started. Otherwise conservatively preserve
            // its checked status and explain why the seller must review it.
            s.execute("""
                UPDATE znack_registration_batch_items AS i
                SET correction_checked_feed=feed_id,
                    detail='CATALOG_LEGACY_REPAIR_REVIEW_REQUIRED: '||detail
                WHERE state='NEEDS_ACTION' AND EXISTS(SELECT 1 FROM znack_registration_batches b
                    WHERE b.id=i.run_id AND b.correction_resumed<>0)
                AND NOT EXISTS(SELECT 1 FROM znack_registration_batches b
                    JOIN znack_card_registrations r ON r.shop_id=b.shop_id
                         AND json_valid(i.sku_json) AND r.chrt_id=json_extract(i.sku_json,'$.chrtId')
                    JOIN znack_card_registration_history h ON h.shop_id=r.shop_id AND h.chrt_id=r.chrt_id
                         AND h.gtin=r.gtin AND h.status='ERROR' AND h.feed_id<>i.feed_id AND h.archived_at>=b.created_at
                    WHERE b.id=i.run_id AND r.feed_id=i.feed_id)
                """);
        }
    }
    private static void ensureColumn(Connection c,String name,String definition)throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("PRAGMA table_info(znack_registration_batches)")){while(r.next())if(name.equals(r.getString("name")))return;}
        try(var s=c.createStatement()){s.execute("ALTER TABLE znack_registration_batches ADD COLUMN "+name+" "+definition);}
    }
    String create(Shop shop,String run,List<Sku> rows,boolean autoSign,String scope){
        return create(shop,run,rows,autoSign,scope,null);
    }
    String create(Shop shop,String run,List<Sku> rows,boolean autoSign,String scope,RegistrationSourceConsent.Prepared consent){
        try{return SqliteWriteTransaction.run(c->{
            requireOwner(c,shop);
            try(var p=c.prepareStatement("SELECT id FROM znack_registration_batches WHERE shop_id=? AND owner=? AND state IN ('QUEUED','RUNNING','WAITING_NETWORK','WAITING_REMOTE','PAUSED') ORDER BY created_at DESC LIMIT 1")){
                p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));try(var r=p.executeQuery()){if(r.next())throw new IllegalStateException("ZNACK_REGISTRATION_BATCH_ALREADY_RUNNING");}
            }
            String now=Instant.now().toString();
            try(var p=c.prepareStatement("INSERT INTO znack_registration_batches(id,shop_id,owner,scope,auto_sign,state,created_at,updated_at) VALUES(?,?,?,?,?,'QUEUED',?,?)")){
                p.setString(1,run);p.setInt(2,shop.getId());p.setString(3,ListingIdentity.fingerprint(shop));p.setString(4,scope);p.setBoolean(5,autoSign);p.setString(6,now);p.setString(7,now);p.executeUpdate();
            }
            try(var p=c.prepareStatement("INSERT INTO znack_registration_batch_items(run_id,position,sku_json,state,updated_at) VALUES(?,?,?,'QUEUED',?)")){
                int position=0;for(var row:rows){p.setString(1,run);p.setInt(2,position++);p.setString(3,JSON.toJson(row));p.setString(4,now);p.addBatch();}p.executeBatch();
            }
            if(consent!=null)RegistrationSourceConsent.persist(c,shop,run,rows.stream().map(row->new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(row.nmId()),Long.toString(row.chrtId()))).toList(),consent);
            return run;
        });}catch(SQLException e){throw storage(e);}
    }
    /** Append separately reviewed native cards without cancelling or replaying an earlier request. */
    void append(Shop shop,String run,List<Sku> rows,boolean autoSign,String scope,RegistrationSourceConsent.Prepared consent){
        if(consent==null)throw new RegistrationValidationException("ZNACK_SOURCE_REVIEW_REQUIRED");
        try{SqliteWriteTransaction.run(c->{
            requireOwner(c,shop);
            try(var p=c.prepareStatement("SELECT state,scope,auto_sign FROM znack_registration_batches WHERE id=? AND shop_id=? AND owner=?")){
                p.setString(1,run);p.setInt(2,shop.getId());p.setString(3,ListingIdentity.fingerprint(shop));
                try(var r=p.executeQuery()){
                    if(!r.next()||!Set.of("QUEUED","RUNNING","WAITING_NETWORK","WAITING_REMOTE").contains(r.getString(1)))throw new IllegalStateException("ZNACK_REGISTRATION_BATCH_ALREADY_RUNNING");
                    if(!scope.equals(r.getString(2)))throw new ZnackRegistrationScope.ScopeChangedException();
                    if(autoSign!=r.getBoolean(3))throw new RegistrationValidationException("ZNACK_BATCH_SIGNING_MODE_CHANGED");
                }
            }
            int offset;
            var existing=new HashSet<Long>();
            try(var p=c.prepareStatement("SELECT position,sku_json FROM znack_registration_batch_items WHERE run_id=?")){
                p.setString(1,run);int next=0;try(var r=p.executeQuery()){while(r.next()){
                    next=Math.max(next,r.getInt(1)+1);existing.add(JSON.fromJson(r.getString(2),Sku.class).chrtId());
                }}offset=next;
            }
            if((long)offset+rows.size()>100000)throw new IllegalArgumentException("registration_selection_limit");
            if(rows.stream().anyMatch(row->existing.contains(row.chrtId())))throw new RegistrationValidationException("ZNACK_REGISTRATION_ALREADY_QUEUED");
            String now=Instant.now().toString();
            try(var p=c.prepareStatement("INSERT INTO znack_registration_batch_items(run_id,position,sku_json,state,updated_at) VALUES(?,?,?,'QUEUED',?)");
                var report=c.prepareStatement("INSERT INTO sync_run_items(id,run_id,product_id,variant_id,sku,article,color,size,gtin,state,step,error_code,child_job,evidence_json,updated_at) VALUES(?,?,?,?,'',?,?,?,'','QUEUED','PREFLIGHT','','','{}',?)")){
                int position=offset;for(var row:rows){
                    p.setString(1,run);p.setInt(2,position++);p.setString(3,JSON.toJson(row));p.setString(4,now);p.addBatch();
                    String[] values={UUID.randomUUID().toString(),run,Long.toString(row.nmId()),Long.toString(row.chrtId()),text(row.vendorCode()),text(row.color()),text(row.size()),now};
                    for(int i=0;i<values.length;i++)report.setString(i+1,values[i]);report.addBatch();
                }p.executeBatch();report.executeBatch();
            }
            RegistrationSourceConsent.persist(c,shop,run,rows.stream().map(row->new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(row.nmId()),Long.toString(row.chrtId()))).toList(),consent,offset);
            try(var p=c.prepareStatement("UPDATE sync_runs SET total=(SELECT count(*) FROM sync_run_items WHERE run_id=?),state='RUNNING',finished_at=NULL,updated_at=? WHERE id=? AND shop_id=? AND owner=?")){
                p.setString(1,run);p.setString(2,now);p.setString(3,run);p.setInt(4,shop.getId());p.setString(5,ListingIdentity.fingerprint(shop));if(p.executeUpdate()!=1)throw new SecurityException("SYNC_OWNER_CHANGED");
            }
            try(var p=c.prepareStatement("UPDATE znack_registration_batches SET state=CASE WHEN state='RUNNING' THEN state ELSE 'QUEUED' END,retry_at=NULL,updated_at=? WHERE id=?")){
                p.setString(1,now);p.setString(2,run);p.executeUpdate();
            }
            return null;
        });}catch(SQLException error){throw storage(error);}
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
    record Summary(int total,int submitted,int failed,int cancelled,Sku current,Sku last,int acceptedFeeds,int awaitingReadback){}
    Summary summary(Shop shop,String id){
        get(shop,id);
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);int total,submitted,failed,cancelled,acceptedFeeds,awaitingReadback;
            try(var p=c.prepareStatement("SELECT count(*),COALESCE(sum(state='SUBMITTED'),0),COALESCE(sum(state='NEEDS_ACTION'),0),COALESCE(sum(state='CANCELLED'),0),COALESCE(sum(NULLIF(TRIM(feed_id),'') IS NOT NULL),0),COALESCE(sum(state='WAITING_REMOTE' AND NULLIF(TRIM(feed_id),'') IS NOT NULL),0) FROM znack_registration_batch_items WHERE run_id=?")){
                p.setString(1,id);try(var r=p.executeQuery()){r.next();total=r.getInt(1);submitted=r.getInt(2);failed=r.getInt(3);cancelled=r.getInt(4);acceptedFeeds=r.getInt(5);awaitingReadback=r.getInt(6);}
            }
            Sku current=summarySku(c,id,false),last=summarySku(c,id,true);c.commit();return new Summary(total,submitted,failed,cancelled,current,last,acceptedFeeds,awaitingReadback);
        }catch(SQLException e){throw storage(e);}
    }
    private static Sku summarySku(Connection c,String id,boolean last)throws SQLException {
        String predicate=last?"state='SUBMITTED'":"state NOT IN ('SUBMITTED','NEEDS_ACTION','CANCELLED')";
        try(var p=c.prepareStatement("SELECT sku_json FROM znack_registration_batch_items WHERE run_id=? AND "+predicate+" ORDER BY position "+(last?"DESC":"ASC")+" LIMIT 1")){
            p.setString(1,id);try(var r=p.executeQuery()){return r.next()?JSON.fromJson(r.getString(1),Sku.class):null;}
        }
    }
    void item(Shop shop,Item item,String state,String detail,Draft draft,String feed){
        write(shop,item.run(),c->{try(var p=c.prepareStatement("UPDATE znack_registration_batch_items SET state=?,detail=?,draft_json=COALESCE(?,draft_json),feed_id=CASE WHEN ?<>'' THEN ? ELSE feed_id END,updated_at=? WHERE run_id=? AND position=?")){
            p.setString(1,state);p.setString(2,safe(detail));p.setString(3,draft==null?null:JSON.toJson(draft));p.setString(4,text(feed));p.setString(5,text(feed));p.setString(6,Instant.now().toString());p.setString(7,item.run());p.setInt(8,item.position());p.executeUpdate();}return null;});
    }
    void state(Shop shop,String id,String state,String detail,Instant retry){
        write(shop,id,c->{try(var p=c.prepareStatement("UPDATE znack_registration_batches SET state=?,detail=?,retry_at=?,updated_at=? WHERE id=? AND state NOT IN ('PAUSED','CANCELLED')")){
            p.setString(1,state);p.setString(2,safe(detail));p.setString(3,retry==null?null:retry.toString());p.setString(4,Instant.now().toString());p.setString(5,id);p.executeUpdate();}return null;});
    }
    /** Controls take effect between cards; an in-flight remote request retains its receipt. */
    boolean control(Shop shop,String id,String state,String detail){
        var changed=new boolean[1];
        write(shop,id,c->{
            String eligible="QUEUED".equals(state)?"state='PAUSED'":"state IN ('QUEUED','RUNNING','WAITING_NETWORK','WAITING_REMOTE','PAUSED')";
            try(var p=c.prepareStatement("UPDATE znack_registration_batches SET state=?,detail=?,retry_at=NULL,retry_count=CASE WHEN ?='QUEUED' THEN 0 ELSE retry_count END,waiting_since=CASE WHEN ?='QUEUED' THEN NULL ELSE waiting_since END,updated_at=? WHERE id=? AND "+eligible)){
                p.setString(1,state);p.setString(2,safe(detail));p.setString(3,state);p.setString(4,state);p.setString(5,Instant.now().toString());p.setString(6,id);changed[0]=p.executeUpdate()==1;
            }
            if(changed[0]&&"CANCELLED".equals(state))try(var p=c.prepareStatement("UPDATE znack_registration_batch_items SET state='CANCELLED',detail='ZNACK_BATCH_CANCELLED',updated_at=? WHERE run_id=? AND state='QUEUED'")){
                p.setString(1,Instant.now().toString());p.setString(2,id);p.executeUpdate();
            }
            if(changed[0]&&"CANCELLED".equals(state))RegistrationSourceConsentGuard.cancel(c,id);
            return null;
        });return changed[0];
    }
    /** Reopen only terminal rejections whose saved request has a deterministic correction.
     * Accepted receipts remain intact until a fresh server rejection authorizes a new POST. */
    boolean reopenRejected(Shop shop,String id,List<Integer> positions){
        if(positions.isEmpty())return false;var changed=new boolean[1];
        write(shop,id,c->{
            try(var p=c.prepareStatement("UPDATE znack_registration_batch_items SET state='WAITING_REMOTE',detail='CATALOG_CORRECTING_OPTIONAL_FIELDS',correction_checked_feed=feed_id,updated_at=? WHERE run_id=? AND position=? AND state='NEEDS_ACTION' AND feed_id<>'' AND correction_checked_feed<>feed_id AND EXISTS(SELECT 1 FROM znack_registration_batches b WHERE b.id=run_id AND b.state='NEEDS_ACTION')")){
                for(int position:positions){p.setString(1,Instant.now().toString());p.setString(2,id);p.setInt(3,position);changed[0]|=p.executeUpdate()==1;}
            }
            if(changed[0])try(var p=c.prepareStatement("UPDATE znack_registration_batches SET state='QUEUED',detail='CATALOG_CORRECTING_OPTIONAL_FIELDS',retry_at=NULL,retry_count=0,waiting_since=NULL,updated_at=? WHERE id=? AND state='NEEDS_ACTION'")){
                p.setString(1,Instant.now().toString());p.setString(2,id);if(p.executeUpdate()!=1)throw new IllegalStateException("REGISTRATION_BATCH_CHANGED");
            }
            return null;
        });return changed[0];
    }
    int retry(Shop shop,String id){
        write(shop,id,c->{try(var p=c.prepareStatement("UPDATE znack_registration_batches SET retry_count=MIN(retry_count+1,1000000) WHERE id=? AND state IN ('QUEUED','RUNNING','WAITING_NETWORK','WAITING_REMOTE')")){p.setString(1,id);p.executeUpdate();}return null;});return get(shop,id).retries();
    }
    /** Upgrade recovery only for the old automatic retry cap, never a seller pause/cancel. */
    boolean recoverRetryPause(Shop shop,String id){
        var changed=new boolean[1];
        write(shop,id,c->{try(var p=c.prepareStatement("UPDATE znack_registration_batches SET state='WAITING_REMOTE',detail='CATALOG_READBACK_RETRY_PENDING',retry_at=?,updated_at=? WHERE id=? AND state='PAUSED' AND detail='ZNACK_BATCH_RETRY_LIMIT_REACHED'")){
            p.setString(1,Instant.now().plusSeconds(30).toString());p.setString(2,Instant.now().toString());p.setString(3,id);changed[0]=p.executeUpdate()==1;
        }return null;});return changed[0];
    }
    Instant waitingSince(Shop shop,String id){
        write(shop,id,c->{try(var p=c.prepareStatement("UPDATE znack_registration_batches SET waiting_since=COALESCE(waiting_since,?) WHERE id=? AND state IN ('QUEUED','RUNNING','WAITING_NETWORK','WAITING_REMOTE')")){p.setString(1,Instant.now().toString());p.setString(2,id);p.executeUpdate();}return null;});return get(shop,id).waitingSince();
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
    private static Batch batch(ResultSet r)throws SQLException {String retry=r.getString("retry_at");return new Batch(r.getString("id"),r.getString("owner"),r.getString("scope"),r.getBoolean("auto_sign"),r.getString("state"),r.getString("detail"),retry==null?null:Instant.parse(retry),r.getInt("retry_count"),r.getString("waiting_since")==null?null:Instant.parse(r.getString("waiting_since")));}
    private static void requireOwner(Connection c,Shop shop)throws SQLException {if(!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace())))throw new SecurityException("SYNC_OWNER_CHANGED");}
    private static String safe(String detail){return com.vncode.app.integration.znack.ZnackSanitizer.message(detail);}
    private static String text(String v){return Objects.requireNonNullElse(v,"");}
    private static IllegalStateException storage(SQLException e){return new IllegalStateException("SYNC_STORAGE",e);}
}
