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
    public void insert(Job job){
        String payload=ProductJson.GSON.toJson(new Job(job.id(),job.shopId(),job.marketplace(),job.owner(),job.fields(),job.createdAt(),false,false,List.of()));
        var rows=job.rows().stream().map(ProductJson.GSON::toJson).toList();
        try{com.vncode.app.config.SqliteWriteTransaction.run(c->{
            try(var p=c.prepareStatement("INSERT INTO catalog_sync_jobs(id,shop_id,marketplace,owner,payload) VALUES(?,?,?,?,?)")){
                p.setString(1,job.id());p.setInt(2,job.shopId());p.setString(3,job.marketplace());p.setString(4,job.owner());p.setString(5,payload);p.executeUpdate();
            }
            try(var p=c.prepareStatement("INSERT INTO catalog_sync_rows VALUES(?,?,?)")){
                for(int i=0;i<job.rows().size();i++){p.setString(1,job.rows().get(i).id());p.setString(2,job.id());p.setString(3,rows.get(i));p.addBatch();}p.executeBatch();
            }return null;
        });}catch(SQLException e){throw new IllegalStateException("Cannot save Catalog sync",e);}
    }
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
    private void flag(Shop shop,String id,String field,boolean value){
        ListingIdentity.require(shop);
        try{com.vncode.app.config.SqliteWriteTransaction.run(c->{
            ListingIdentity.require(shop);try(var p=c.prepareStatement("UPDATE catalog_sync_jobs SET "+field+"=? WHERE id=? AND shop_id=? AND owner=?")){
                p.setInt(1,value?1:0);p.setString(2,id);p.setInt(3,shop.getId());p.setString(4,ListingIdentity.fingerprint(shop));if(p.executeUpdate()!=1)throw new SecurityException("Foreign Catalog sync");
            }return null;
        });}catch(SQLException e){throw new IllegalStateException("Cannot update Catalog sync",e);}
    }
    public boolean paused(Shop shop,String id){ListingIdentity.require(shop);try(var c=Database.getConnection();var p=c.prepareStatement("SELECT paused FROM catalog_sync_jobs WHERE id=? AND shop_id=? AND marketplace=? AND owner=?")){
        p.setString(1,id);p.setInt(2,shop.getId());p.setString(3,shop.getMarketplace().name());p.setString(4,ListingIdentity.fingerprint(shop));try(var r=p.executeQuery()){if(!r.next())throw new SecurityException("Foreign Catalog sync");return r.getInt(1)==1;}
    }catch(SQLException e){throw new IllegalStateException("Cannot read Catalog sync state");}}
    public void update(Shop shop,String id,Row row){updateBatch(shop,id,List.of(row));}
    /** Short bounded transactions keep the FBS writer responsive during a large GTIN job. */
    public void updateBatch(Shop shop,String id,List<Row> rows){
        ListingIdentity.require(shop);String owner=ListingIdentity.fingerprint(shop);
        for(int offset=0;offset<rows.size();offset+=250){var part=rows.subList(offset,Math.min(offset+250,rows.size()));var json=part.stream().map(ProductJson.GSON::toJson).toList();
            try{com.vncode.app.config.SqliteWriteTransaction.run(c->{
                try(var guard=c.prepareStatement("SELECT 1 FROM catalog_sync_jobs WHERE id=? AND shop_id=? AND marketplace=? AND owner=?")){
                    guard.setString(1,id);guard.setInt(2,shop.getId());guard.setString(3,shop.getMarketplace().name());guard.setString(4,owner);try(var r=guard.executeQuery()){if(!r.next())throw new SecurityException("Foreign Catalog sync");}
                }
                try(var p=c.prepareStatement("UPDATE catalog_sync_rows SET payload=? WHERE id=? AND job_id=?")){
                    for(int i=0;i<part.size();i++){p.setString(1,json.get(i));p.setString(2,part.get(i).id());p.setString(3,id);p.addBatch();}for(int n:p.executeBatch())if(n!=1&&n!=Statement.SUCCESS_NO_INFO)throw new SecurityException("Foreign Catalog sync row");
                }return null;
            });}catch(SQLException failure){throw new IllegalStateException("Cannot checkpoint Catalog sync",failure);}
        }
    }
}
