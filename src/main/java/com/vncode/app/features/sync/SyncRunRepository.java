package com.vncode.app.features.sync;

import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Safe, per-item progress. Payloads and upstream exception messages are never report fields. */
public final class SyncRunRepository {
    public record Run(String id,int shopId,String marketplace,String kind,String state,String step,int total,String created,String updated,String error) {}
    public record Item(String id,String runId,String productId,String variantId,String sku,String article,String color,String size,String gtin,String state,String step,String error,String childJob,String evidence) {}
    public record Event(String step,String state,String error,String time) {}
    public String begin(Shop shop,String kind,String step){
        ListingIdentity.require(shop);String id=UUID.randomUUID().toString(),now=Instant.now().toString();
        try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO sync_runs(id,shop_id,marketplace,owner,kind,state,step,created_at,updated_at) VALUES(?,?,?,?,?,'RUNNING',?,?,?)")){
            p.setString(1,id);p.setInt(2,shop.getId());p.setString(3,shop.getMarketplace().name());p.setString(4,ListingIdentity.fingerprint(shop));p.setString(5,kind);p.setString(6,step);p.setString(7,now);p.setString(8,now);p.executeUpdate();
        }catch(SQLException e){throw storage(e);}return id;
    }
    public void assertOwner(Shop shop,String run){try(var c=Database.getConnection()){require(c,shop,run);}catch(SQLException e){throw storage(e);}}
    public void stage(Shop shop,String id,String state,String step,String error){
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);require(c,shop,id);
            try(var p=c.prepareStatement("UPDATE sync_runs SET state=?,step=?,error_code=?,updated_at=?,finished_at=CASE WHEN ? IN ('SUCCEEDED','FAILED','PARTIAL','CANCELLED') THEN ? ELSE NULL END WHERE id=?")){
                String now=Instant.now().toString();p.setString(1,state);p.setString(2,step);p.setString(3,code(error));p.setString(4,now);p.setString(5,state);p.setString(6,now);p.setString(7,id);p.executeUpdate();event(c,id,null,step,state,error);
            }c.commit();
        }catch(SQLException e){throw storage(e);}
    }
    public Item add(Shop shop,String run,String product,String variant,String sku,String article,String color,String size,String gtin,String state,String error,String evidence){
        String id=UUID.randomUUID().toString();
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);require(c,shop,run);
            try(var p=c.prepareStatement("INSERT INTO sync_run_items(id,run_id,product_id,variant_id,sku,article,color,size,gtin,state,step,error_code,evidence_json,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,'MATCHING',?,?,?) ON CONFLICT(run_id,product_id,variant_id) DO NOTHING")){
                String[] values={id,run,product,variant,sku,article,color,size,gtin,state,code(error),evidence,Instant.now().toString()};for(int i=0;i<values.length;i++)p.setString(i+1,values[i]);p.executeUpdate();
            }
            try(var p=c.prepareStatement("UPDATE sync_runs SET total=(SELECT count(*) FROM sync_run_items WHERE run_id=?),updated_at=? WHERE id=?")){p.setString(1,run);p.setString(2,Instant.now().toString());p.setString(3,run);p.executeUpdate();}
            c.commit();
        }catch(SQLException e){throw storage(e);}
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT id,child_job FROM sync_run_items WHERE run_id=? AND product_id=? AND variant_id=?")){p.setString(1,run);p.setString(2,product);p.setString(3,variant);try(var r=p.executeQuery()){if(!r.next())throw new SQLException("Missing item");return new Item(r.getString(1),run,product,variant,sku,article,color,size,gtin,state,"MATCHING",code(error),r.getString(2),evidence);}}catch(SQLException e){throw storage(e);}
    }
    /** Persist a Catalog snapshot report in one bounded transaction, not a connection per GTIN. */
    public void addBatch(Shop shop,String run,List<Item> items){
        if(items.size()>100000)throw new IllegalArgumentException("SYNC_REPORT_LIMIT");
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);require(c,shop,run);
            try(var p=c.prepareStatement("INSERT INTO sync_run_items(id,run_id,product_id,variant_id,sku,article,color,size,gtin,state,step,error_code,child_job,evidence_json,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(run_id,product_id,variant_id) DO NOTHING")){
                for(var item:items){if(!run.equals(item.runId()))throw new SecurityException("SYNC_OWNER_CHANGED");
                    String[] values={item.id(),run,item.productId(),item.variantId(),item.sku(),item.article(),item.color(),item.size(),item.gtin(),item.state(),item.step(),code(item.error()),item.childJob(),item.evidence(),Instant.now().toString()};
                    for(int i=0;i<values.length;i++)p.setString(i+1,values[i]);p.addBatch();
                }p.executeBatch();
            }
            try(var p=c.prepareStatement("UPDATE sync_runs SET total=(SELECT count(*) FROM sync_run_items WHERE run_id=?),updated_at=? WHERE id=?")){p.setString(1,run);p.setString(2,Instant.now().toString());p.setString(3,run);p.executeUpdate();}
            c.commit();
        }catch(SQLException e){throw storage(e);}
    }
    public void update(Shop shop,Item item,String state,String step,String error,String child){
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);require(c,shop,item.runId());
            try(var p=c.prepareStatement("UPDATE sync_run_items SET state=?,step=?,error_code=?,child_job=?,updated_at=? WHERE id=? AND run_id=?")){
                p.setString(1,state);p.setString(2,step);p.setString(3,code(error));p.setString(4,child==null?item.childJob():child);p.setString(5,Instant.now().toString());p.setString(6,item.id());p.setString(7,item.runId());p.executeUpdate();
            }
            event(c,item.runId(),item.id(),step,state,error);
            try(var p=c.prepareStatement("UPDATE sync_runs SET step=?,updated_at=? WHERE id=?")){p.setString(1,step);p.setString(2,Instant.now().toString());p.setString(3,item.runId());p.executeUpdate();}c.commit();
        }catch(SQLException e){throw storage(e);}
    }
    /** Bounded progress write for a large FBS batch, with one owner check and transaction. */
    public void updateBatch(Shop shop,String run,List<Item> items){
        if(items.size()>10000||items.stream().anyMatch(i->!run.equals(i.runId())))throw new IllegalArgumentException("SYNC_REPORT_LIMIT");
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);require(c,shop,run);
            try(var p=c.prepareStatement("UPDATE sync_run_items SET state=?,step=?,error_code=?,evidence_json=?,updated_at=? WHERE run_id=? AND id=?")){
                for(var item:items){p.setString(1,item.state());p.setString(2,item.step());p.setString(3,code(item.error()));p.setString(4,item.evidence());p.setString(5,Instant.now().toString());p.setString(6,run);p.setString(7,item.id());p.addBatch();}p.executeBatch();
            }c.commit();
        }catch(SQLException e){throw storage(e);}
    }
    public void linkReviewed(Shop shop,String run,com.vncode.app.features.catalogsync.CatalogSyncModels.Job child){
        if(!child.confirmed()||!child.fields().contains(com.vncode.app.features.catalogsync.CatalogSyncModels.Field.GTIN)||child.shopId()!=shop.getId()||!child.owner().equals(ListingIdentity.fingerprint(shop)))throw new SecurityException("SYNC_REVIEW_OWNER_CHANGED");
        try(var c=Database.getConnection()){
            c.setAutoCommit(false);require(c,shop,run);
            for(var row:child.rows()){
                try(var p=c.prepareStatement("UPDATE sync_run_items SET gtin=?,child_job=?,state=?,step='READBACK',error_code=?,updated_at=? WHERE run_id=? AND product_id=? AND variant_id=? AND state NOT IN ('SUCCEEDED','UNCHANGED')")){
                    // Let the sync worker activate the native registration candidate after
                    // the confirmed child has verified the target. UI callbacks cannot skip it.
                    p.setString(1,row.catalog().gtin());p.setString(2,child.id());p.setString(3,row.state()==com.vncode.app.features.catalogsync.CatalogSyncModels.State.BLOCKED?"NEEDS_ACTION":"WAITING_REMOTE");p.setString(4,code(row.issue()));p.setString(5,Instant.now().toString());p.setString(6,run);p.setString(7,row.key().productId());p.setString(8,row.key().variantId());p.executeUpdate();
                }
            }
            try(var p=c.prepareStatement("UPDATE sync_runs SET state='RUNNING',step='READBACK',finished_at=NULL,updated_at=? WHERE id=?")){p.setString(1,Instant.now().toString());p.setString(2,run);p.executeUpdate();}
            SyncStreamStore.request(c,shop.getId(),"ZNACK");c.commit();
        }catch(SQLException e){throw storage(e);}
    }
    public Optional<String> active(Shop shop,String kind){
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT id FROM sync_runs WHERE shop_id=? AND owner=? AND kind=? AND state IN ('QUEUED','RUNNING','WAITING_REMOTE','RECONCILING','PAUSED') ORDER BY created_at LIMIT 1")){
            p.setInt(1,shop.getId());p.setString(2,ListingIdentity.fingerprint(shop));p.setString(3,kind);try(var r=p.executeQuery()){return r.next()?Optional.of(r.getString(1)):Optional.empty();}
        }catch(SQLException e){throw storage(e);}
    }
    public String step(String id){try(var c=Database.getConnection();var p=c.prepareStatement("SELECT step FROM sync_runs WHERE id=?")){p.setString(1,id);try(var r=p.executeQuery()){if(!r.next())throw new IllegalArgumentException("SYNC_JOB_NOT_FOUND");return r.getString(1);}}catch(SQLException e){throw storage(e);}}
    public Optional<Run> find(String id){try(var c=Database.getConnection();var p=c.prepareStatement("SELECT * FROM sync_runs WHERE id=?")){p.setString(1,id);try(var r=p.executeQuery()){return r.next()?Optional.of(new Run(r.getString("id"),r.getInt("shop_id"),r.getString("marketplace"),r.getString("kind"),r.getString("state"),r.getString("step"),r.getInt("total"),r.getString("created_at"),r.getString("updated_at"),r.getString("error_code"))):Optional.empty();}}catch(SQLException e){throw storage(e);}}
    public Map<String,Long> counts(String id){try(var c=Database.getConnection();var p=c.prepareStatement("SELECT state,count(*) FROM sync_run_items WHERE run_id=? GROUP BY state")){p.setString(1,id);var out=new LinkedHashMap<String,Long>();try(var r=p.executeQuery()){while(r.next())out.put(r.getString(1),r.getLong(2));}return Collections.unmodifiableMap(out);}catch(SQLException e){throw storage(e);}}
    public List<Run> list(int shopId,int offset,int limit){
        if(offset<0||limit<1||limit>1000)throw new IllegalArgumentException("SYNC_PAGE_LIMIT");
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT * FROM sync_runs WHERE (?=0 OR shop_id=?) ORDER BY created_at DESC LIMIT ? OFFSET ?")){
            p.setInt(1,shopId);p.setInt(2,shopId);p.setInt(3,limit);p.setInt(4,offset);var out=new ArrayList<Run>();try(var r=p.executeQuery()){while(r.next())out.add(new Run(r.getString("id"),r.getInt("shop_id"),r.getString("marketplace"),r.getString("kind"),r.getString("state"),r.getString("step"),r.getInt("total"),r.getString("created_at"),r.getString("updated_at"),r.getString("error_code")));}return List.copyOf(out);
        }catch(SQLException e){throw storage(e);}
    }
    public List<Item> items(String run,int offset,int limit){
        if(offset<0||limit<1||limit>1000)throw new IllegalArgumentException("SYNC_PAGE_LIMIT");
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT * FROM sync_run_items WHERE run_id=? ORDER BY rowid LIMIT ? OFFSET ?")){
            p.setString(1,run);p.setInt(2,limit);p.setInt(3,offset);var out=new ArrayList<Item>();try(var r=p.executeQuery()){while(r.next())out.add(new Item(r.getString("id"),run,r.getString("product_id"),r.getString("variant_id"),r.getString("sku"),r.getString("article"),r.getString("color"),r.getString("size"),r.getString("gtin"),r.getString("state"),r.getString("step"),r.getString("error_code"),r.getString("child_job"),r.getString("evidence_json")));}return List.copyOf(out);
        }catch(SQLException e){throw storage(e);}
    }
    public List<Event> events(String run,String item,int limit){
        if(limit<1||limit>1000)throw new IllegalArgumentException("SYNC_PAGE_LIMIT");
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT step,state,error_code,created_at FROM sync_run_events WHERE run_id=? AND (?='' OR item_id=?) ORDER BY id DESC LIMIT ?")){
            p.setString(1,run);p.setString(2,item==null?"":item);p.setString(3,item);p.setInt(4,limit);var out=new ArrayList<Event>();try(var r=p.executeQuery()){while(r.next())out.add(new Event(r.getString(1),r.getString(2),r.getString(3),r.getString(4)));}return List.copyOf(out);
        }catch(SQLException e){throw storage(e);}
    }
    private static void require(Connection c,Shop shop,String run)throws SQLException {
        ListingIdentity.require(shop);try(var p=c.prepareStatement("SELECT owner FROM sync_runs WHERE id=? AND shop_id=?")){p.setString(1,run);p.setInt(2,shop.getId());try(var r=p.executeQuery()){if(!r.next()||!ListingIdentity.fingerprint(shop).equals(r.getString(1)))throw new SecurityException("SYNC_OWNER_CHANGED");}}
    }
    private static void event(Connection c,String run,String item,String step,String state,String error)throws SQLException{
        try(var p=c.prepareStatement("INSERT INTO sync_run_events(run_id,item_id,step,state,error_code,created_at) VALUES(?,?,?,?,?,?)")){
            p.setString(1,run);p.setString(2,item);p.setString(3,step);p.setString(4,state);p.setString(5,code(error));p.setString(6,Instant.now().toString());p.executeUpdate();
        }
    }
    public static String code(String text){if(text==null||text.isBlank())return "";String value=text.toUpperCase(Locale.ROOT);return value.matches("(?:API|CATALOG|GS1|ZNACK|OZON|WB|GTIN|FBS|FBO|SYNC|NETWORK|LICENSE|PRODUCT|VARIANT|BARCODE|BARCODES|ATTRIBUTES|TARGET|CARD|ITEMS|SIGNATURE|CERTIFICATE|REGISTRY|PARTICIPANT|GOODS|UNVERIFIED|INVALID|MISSING|AMBIGUOUS|INCOMPLETE|REPEATED|READBACK|LEGACY|DETAIL|SHOP|SOURCE)_[A-Z0-9_]{1,64}")||Set.of("ALREADY_PRESENT","UNSUPPORTED_OPERATION","CAPABILITY_CHANGED").contains(value)?value:"DETAIL_NOT_AVAILABLE";}
    private static IllegalStateException storage(SQLException e){return new IllegalStateException("SYNC_STORAGE",e);}
}
