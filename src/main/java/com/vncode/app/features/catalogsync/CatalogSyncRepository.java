package com.vncode.app.features.catalogsync;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.ProductJson;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.util.*;
import static com.vncode.app.features.catalogsync.CatalogSyncModels.*;

/** Plan rows and child job references are committed before sending any marketplace request. */
public final class CatalogSyncRepository {
    public CatalogSyncRepository(){try(var c=Database.getConnection();var s=c.createStatement()){
        s.execute("CREATE TABLE IF NOT EXISTS catalog_sync_jobs(id TEXT PRIMARY KEY,shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,marketplace TEXT NOT NULL,owner TEXT NOT NULL,payload TEXT NOT NULL,confirmed INTEGER NOT NULL DEFAULT 0,paused INTEGER NOT NULL DEFAULT 0)");
        s.execute("CREATE TABLE IF NOT EXISTS catalog_sync_rows(id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES catalog_sync_jobs(id) ON DELETE CASCADE,payload TEXT NOT NULL)");
        s.execute("CREATE INDEX IF NOT EXISTS catalog_sync_shop ON catalog_sync_jobs(shop_id,marketplace,owner)");
    }catch(SQLException e){throw new IllegalStateException("Cannot initialize Catalog sync");}}
    public void insert(Job job){try(var c=Database.getConnection()){
        c.setAutoCommit(false);
        try(var p=c.prepareStatement("INSERT INTO catalog_sync_jobs(id,shop_id,marketplace,owner,payload) VALUES(?,?,?,?,?)")){
            p.setString(1,job.id());p.setInt(2,job.shopId());p.setString(3,job.marketplace());p.setString(4,job.owner());p.setString(5,ProductJson.GSON.toJson(new Job(job.id(),job.shopId(),job.marketplace(),job.owner(),job.fields(),job.createdAt(),false,false,List.of())));p.executeUpdate();
        }
        try(var p=c.prepareStatement("INSERT INTO catalog_sync_rows VALUES(?,?,?)")){
            for(var row:job.rows()){p.setString(1,row.id());p.setString(2,job.id());p.setString(3,ProductJson.GSON.toJson(row));p.addBatch();}p.executeBatch();
        }c.commit();
    }catch(SQLException e){throw new IllegalStateException("Cannot save Catalog sync");}}
    public Job read(Shop shop,String id){ListingIdentity.require(shop);try(var c=Database.getConnection();var p=c.prepareStatement("SELECT payload,confirmed,paused FROM catalog_sync_jobs WHERE id=? AND shop_id=? AND marketplace=? AND owner=?")){
        p.setString(1,id);p.setInt(2,shop.getId());p.setString(3,shop.getMarketplace().name());p.setString(4,ListingIdentity.fingerprint(shop));
        try(var r=p.executeQuery()){
            if(!r.next())throw new SecurityException("Foreign Catalog sync");var saved=ProductJson.GSON.fromJson(r.getString(1),Job.class);var rows=new ArrayList<Row>();
            try(var q=c.prepareStatement("SELECT payload FROM catalog_sync_rows WHERE job_id=? ORDER BY rowid")){q.setString(1,id);try(var values=q.executeQuery()){while(values.next())rows.add(ProductJson.GSON.fromJson(values.getString(1),Row.class));}}
            return new Job(id,saved.shopId(),saved.marketplace(),saved.owner(),saved.fields(),saved.createdAt(),r.getInt(2)==1,r.getInt(3)==1,rows);
        }
    }catch(SQLException e){throw new IllegalStateException("Cannot read Catalog sync");}}
    public List<String> list(Shop shop){ListingIdentity.require(shop);try(var c=Database.getConnection();var p=c.prepareStatement("SELECT id FROM catalog_sync_jobs WHERE shop_id=? AND marketplace=? AND owner=? ORDER BY rowid DESC LIMIT 100")){
        p.setInt(1,shop.getId());p.setString(2,shop.getMarketplace().name());p.setString(3,ListingIdentity.fingerprint(shop));var result=new ArrayList<String>();try(var r=p.executeQuery()){while(r.next())result.add(r.getString(1));}return List.copyOf(result);
    }catch(SQLException e){throw new IllegalStateException("Cannot list Catalog sync");}}
    public void confirm(Shop shop,String id){read(shop,id);flag(shop,id,"confirmed",true);}
    public void pause(Shop shop,String id,boolean value){read(shop,id);flag(shop,id,"paused",value);}
    private void flag(Shop shop,String id,String field,boolean value){try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE catalog_sync_jobs SET "+field+"=? WHERE id=? AND shop_id=? AND owner=?")){
        p.setInt(1,value?1:0);p.setString(2,id);p.setInt(3,shop.getId());p.setString(4,ListingIdentity.fingerprint(shop));if(p.executeUpdate()!=1)throw new SecurityException("Foreign Catalog sync");
    }catch(SQLException e){throw new IllegalStateException("Cannot update Catalog sync");}}
    public boolean paused(Shop shop,String id){ListingIdentity.require(shop);try(var c=Database.getConnection();var p=c.prepareStatement("SELECT paused FROM catalog_sync_jobs WHERE id=? AND shop_id=? AND marketplace=? AND owner=?")){
        p.setString(1,id);p.setInt(2,shop.getId());p.setString(3,shop.getMarketplace().name());p.setString(4,ListingIdentity.fingerprint(shop));try(var r=p.executeQuery()){if(!r.next())throw new SecurityException("Foreign Catalog sync");return r.getInt(1)==1;}
    }catch(SQLException e){throw new IllegalStateException("Cannot read Catalog sync state");}}
    public void update(Shop shop,String id,Row row){ListingIdentity.require(shop);try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE catalog_sync_rows SET payload=? WHERE id=? AND job_id=? AND EXISTS(SELECT 1 FROM catalog_sync_jobs j WHERE j.id=catalog_sync_rows.job_id AND j.shop_id=? AND j.marketplace=? AND j.owner=?)")){
        p.setString(1,ProductJson.GSON.toJson(row));p.setString(2,row.id());p.setString(3,id);p.setInt(4,shop.getId());p.setString(5,shop.getMarketplace().name());p.setString(6,ListingIdentity.fingerprint(shop));if(p.executeUpdate()!=1)throw new SecurityException("Foreign Catalog sync row");
    }catch(SQLException e){throw new IllegalStateException("Cannot checkpoint Catalog sync");}}
}
