package com.vncode.app.features.listingcopy;

import java.sql.*;
public final class ListingCopySchema {
    private ListingCopySchema(){}
    public static void ensureTables(Connection c)throws SQLException{try(var s=c.createStatement()){
        s.execute("""
          CREATE TABLE IF NOT EXISTS listing_copy_jobs(
            id TEXT PRIMARY KEY, source_shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
            destination_shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
            marketplace TEXT NOT NULL, owner TEXT NOT NULL, state TEXT NOT NULL, approved_hash TEXT,
            payload TEXT NOT NULL, updated_at TEXT NOT NULL)
          """);
        s.execute("CREATE INDEX IF NOT EXISTS listing_copy_owner ON listing_copy_jobs(destination_shop_id,owner,updated_at DESC)");
        var columns=new java.util.HashSet<String>();try(var r=s.executeQuery("PRAGMA table_info(listing_copy_jobs)")){while(r.next())columns.add(r.getString("name"));}
        if(!columns.contains("summary"))s.execute("ALTER TABLE listing_copy_jobs ADD COLUMN summary TEXT");
        if(!columns.contains("source_owner"))s.execute("ALTER TABLE listing_copy_jobs ADD COLUMN source_owner TEXT");
        s.execute("CREATE INDEX IF NOT EXISTS listing_copy_source ON listing_copy_jobs(source_shop_id,source_owner,updated_at DESC)");
        // Stream old jobs one at a time; never aggregate document payloads during additive migration.
        try(var r=s.executeQuery("SELECT id,payload FROM listing_copy_jobs WHERE summary IS NULL");var update=c.prepareStatement("UPDATE listing_copy_jobs SET summary=?,source_owner=? WHERE id=?")){
            while(r.next()){var job=com.vncode.app.features.products.ProductJson.GSON.fromJson(r.getString(2),CopyJob.class);update.setString(1,com.vncode.app.features.products.ProductJson.GSON.toJson(job.summary()));update.setString(2,job.sourceOwner());update.setString(3,r.getString(1));update.executeUpdate();}
        }
        s.execute("""
          CREATE TABLE IF NOT EXISTS listing_copy_articles(
            destination_shop_id INTEGER NOT NULL,marketplace TEXT NOT NULL,article TEXT NOT NULL,
            job_id TEXT NOT NULL REFERENCES listing_copy_jobs(id) ON DELETE CASCADE,
            PRIMARY KEY(destination_shop_id,marketplace,article))
          """);
        s.execute("""
          CREATE TABLE IF NOT EXISTS listing_copy_events(id INTEGER PRIMARY KEY,job_id TEXT NOT NULL REFERENCES listing_copy_jobs(id) ON DELETE CASCADE,state TEXT NOT NULL,phase TEXT NOT NULL,created_at TEXT NOT NULL)
          """);
    }}
}
