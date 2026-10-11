package com.vncode.app.features.products;

import com.vncode.app.config.Database;
import com.vncode.app.features.listingcopy.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Persistent per-card claims also exclude the existing GTIN mutation queue. */
public final class ProductEditRepository {
    public static void ensureTables(Connection c)throws SQLException {try(var s=c.createStatement()){
        s.execute("CREATE TABLE IF NOT EXISTS product_edit_jobs(id TEXT PRIMARY KEY,shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,owner TEXT NOT NULL,preview TEXT NOT NULL,created_at TEXT NOT NULL)");
        s.execute("CREATE INDEX IF NOT EXISTS product_edit_owner ON product_edit_jobs(shop_id,owner,created_at DESC)");
        s.execute("CREATE TABLE IF NOT EXISTS product_edit_items(job_id TEXT NOT NULL REFERENCES product_edit_jobs(id) ON DELETE CASCADE,shop_id INTEGER NOT NULL,marketplace TEXT NOT NULL,card_id TEXT NOT NULL,state TEXT NOT NULL,payload TEXT NOT NULL,phase TEXT NOT NULL DEFAULT 'CARD',PRIMARY KEY(job_id,card_id))");
        s.execute("CREATE UNIQUE INDEX IF NOT EXISTS product_edit_active_card ON product_edit_items(shop_id,marketplace,card_id) WHERE state IN ('SENDING','REMOTE_PROCESSING','RECONCILE_REQUIRED')");
        s.execute("CREATE TABLE IF NOT EXISTS product_edit_events(id INTEGER PRIMARY KEY,job_id TEXT NOT NULL REFERENCES product_edit_jobs(id) ON DELETE CASCADE,card_id TEXT NOT NULL,state TEXT NOT NULL,phase TEXT NOT NULL,created_at TEXT NOT NULL)");
    }}
    public void insert(ProductBulkEditService.ProductEditPreview p){try(var c=Database.getConnection()){
        c.setAutoCommit(false);try(var q=c.prepareStatement("INSERT INTO product_edit_jobs VALUES(?,?,?,?,?)")){q.setString(1,p.id());q.setInt(2,p.shopId());q.setString(3,p.owner());q.setString(4,ProductJson.GSON.toJson(p));q.setString(5,Instant.now().toString());q.executeUpdate();}
        try(var q=c.prepareStatement("INSERT INTO product_edit_items(job_id,shop_id,marketplace,card_id,state,payload) VALUES(?,?,?,?,?,?)")){for(var i:p.items()){q.setString(1,p.id());q.setInt(2,p.shopId());q.setString(3,i.key().marketplace().name());q.setString(4,i.key().cardId());q.setString(5,i.state().name());q.setString(6,ProductJson.GSON.toJson(i));q.addBatch();}q.executeBatch();}c.commit();
    }catch(SQLException e){throw new IllegalStateException("Cannot save product edit");}}
    public ProductBulkEditService.ProductEditPreview find(int shop,String owner,String id){try(var c=Database.getConnection();var q=c.prepareStatement("SELECT preview FROM product_edit_jobs WHERE shop_id=? AND owner=? AND id=?")){q.setInt(1,shop);q.setString(2,owner);q.setString(3,id);try(var r=q.executeQuery()){if(!r.next())throw new SecurityException("Foreign product edit");return ProductJson.GSON.fromJson(r.getString(1),ProductBulkEditService.ProductEditPreview.class);}}catch(SQLException e){throw new IllegalStateException("Cannot read product edit");}}
    public List<String> list(int shop,String owner){try(var c=Database.getConnection();var q=c.prepareStatement("SELECT id FROM product_edit_jobs WHERE shop_id=? AND owner=? ORDER BY created_at DESC LIMIT 100")){q.setInt(1,shop);q.setString(2,owner);try(var r=q.executeQuery()){var ids=new ArrayList<String>();while(r.next())ids.add(r.getString(1));return List.copyOf(ids);}}catch(SQLException e){throw new IllegalStateException("Cannot read product edits");}}
    public List<ProductBulkEditService.EditItem> items(String id){try(var c=Database.getConnection();var q=c.prepareStatement("SELECT state,payload FROM product_edit_items WHERE job_id=? ORDER BY card_id")){q.setString(1,id);try(var r=q.executeQuery()){var out=new ArrayList<ProductBulkEditService.EditItem>();while(r.next()){var i=ProductJson.GSON.fromJson(r.getString(2),ProductBulkEditService.EditItem.class);out.add(i.with(CopyState.valueOf(r.getString(1)),i.submission(),i.issues()));}return List.copyOf(out);}}catch(SQLException e){throw new IllegalStateException("Cannot read product edit items");}}
    public boolean claim(String id,ProductBulkEditService.EditItem item){try(var c=Database.getConnection()){
        c.setAutoCommit(false);try(var q=c.prepareStatement("""
          UPDATE product_edit_items SET state='SENDING' WHERE job_id=? AND card_id=? AND state='AWAITING_CONFIRMATION'
          AND NOT EXISTS(SELECT 1 FROM gtin_sync_items g WHERE g.shop_id=product_edit_items.shop_id AND g.marketplace=product_edit_items.marketplace AND g.product_id=product_edit_items.card_id AND g.status IN ('VALIDATING','SENDING','AWAITING_VERIFICATION','RECONCILE_REQUIRED'))
          """)){q.setString(1,id);q.setString(2,item.key().cardId());if(q.executeUpdate()!=1){c.rollback();return false;}}
        event(c,id,item.with(CopyState.SENDING,null,List.of()));c.commit();return true;
    }catch(SQLException conflict){return false;}}
    public boolean claimAssets(String id,ProductBulkEditService.EditItem item,RemoteSubmission submission){var next=item.with(CopyState.SENDING,submission,List.of());try(var c=Database.getConnection()){
        c.setAutoCommit(false);try(var q=c.prepareStatement("UPDATE product_edit_items SET state='SENDING',phase='ASSETS_SENDING',payload=? WHERE job_id=? AND card_id=? AND phase='CARD' AND state IN ('REMOTE_PROCESSING','RECONCILE_REQUIRED')")){q.setString(1,ProductJson.GSON.toJson(next));q.setString(2,id);q.setString(3,item.key().cardId());if(q.executeUpdate()!=1){c.rollback();return false;}}event(c,id,next);c.commit();return true;
    }catch(SQLException e){throw new IllegalStateException("Cannot claim product documents");}}
    public boolean checkpoint(String id,ProductBulkEditService.EditItem item,Set<CopyState> expected){try(var c=Database.getConnection()){
        c.setAutoCommit(false);String marks=String.join(",",Collections.nCopies(expected.size(),"?"));String phase=item.submission()==null?"CARD":item.submission().phase();try(var q=c.prepareStatement("UPDATE product_edit_items SET state=?,payload=?,phase=? WHERE job_id=? AND card_id=? AND state IN ("+marks+") AND (phase='CARD' OR ?<>'CARD')")){q.setString(1,item.state().name());q.setString(2,ProductJson.GSON.toJson(item));q.setString(3,phase);q.setString(4,id);q.setString(5,item.key().cardId());int n=6;for(var s:expected)q.setString(n++,s.name());q.setString(n,phase);if(q.executeUpdate()!=1){c.rollback();return false;}}event(c,id,item);c.commit();return true;
    }catch(SQLException e){throw new IllegalStateException("Cannot checkpoint product edit");}}
    public void recoverIncomplete(){try(var c=Database.getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE product_edit_items SET state='RECONCILE_REQUIRED' WHERE state='SENDING'");}catch(SQLException e){throw new IllegalStateException("Cannot recover product edits");}}
    private static void event(Connection c,String id,ProductBulkEditService.EditItem item)throws SQLException{try(var q=c.prepareStatement("INSERT INTO product_edit_events(job_id,card_id,state,phase,created_at) VALUES(?,?,?,?,?)")){q.setString(1,id);q.setString(2,item.key().cardId());q.setString(3,item.state().name());q.setString(4,item.submission()==null?"CARD":item.submission().phase());q.setString(5,Instant.now().toString());q.executeUpdate();}}
}
